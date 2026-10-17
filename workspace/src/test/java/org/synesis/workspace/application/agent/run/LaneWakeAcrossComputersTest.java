package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.MemberReplication;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Two computers of one project, joined by a real Link session on loopback (ADR-0098): an agent on the member is told
 * WAIT and ends its turn, and the request that an agent on the leader then makes of its lane reaches the member's log
 * by replication and wakes it there (ADR-0099). Nothing but the replicated log carries the wake.
 */
@Timeout(120)
class LaneWakeAcrossComputersTest {

    private final UUID project = UUID.randomUUID();
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        for (int index = toClose.size() - 1; index >= 0; index--) {
            try {
                toClose.get(index).close();
            } catch (Exception ignored) {
                // Best effort.
            }
        }
    }

    private <T extends AutoCloseable> T closing(T resource) {
        toClose.add(resource);
        return resource;
    }

    private ProjectLocation computer(String name) {
        Path root = temp.resolve(name);
        return new ProjectLocation(root, root.resolve(".synesis"), root.resolve(".synesis/project.json"), temp.resolve(name + "-profile"), project, Instant.now());
    }

    private CoordinationHttpServer server(Path coordination, NodeIdentity identity) throws Exception {
        CoordinationHttpServer server = closing(new CoordinationHttpServer(new CoordinationService(new PredictionEventStore(coordination, project), identity),
                new InetSocketAddress("127.0.0.1", 0)));
        server.start();
        return server;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "not reached in 20 s");
    }

    @Test
    void aRequestMadeOnTheLeaderWakesTheMembersWaitingAgentThroughReplication() throws Exception {
        ProjectLocation leaderComputer = computer("leader");
        ProjectLocation memberComputer = computer("member");
        Path leaderLog = leaderComputer.coordinationDirectory();
        Path memberLog = memberComputer.coordinationDirectory();
        NodeIdentity leader = new IdentityBootstrap(leaderComputer.profile()).loadOrCreate().identity();
        NodeIdentity member = new IdentityBootstrap(memberComputer.profile()).loadOrCreate().identity();
        ReplicationSettings.leader(Set.of(member.nodeId())).write(leaderLog);
        ReplicationSettings.member(leader.nodeId()).write(memberLog);

        LeaderReplication leaderSide = closing(new LeaderReplication(leaderLog, project, Set.of(member.nodeId())));
        MemberReplication memberSide = closing(MemberReplication.open(memberLog, project));
        server(leaderLog, leader);
        memberSide.serve(server(memberLog, member));
        Onboarding.PreparedHost host = closing(new Onboarding(leaderComputer.profile(), ignored -> {
        }).createCompactInvitation(null, leaderSide.handler(), ignored -> {
        }));
        CompletableFuture<PeerSession> hosted = CompletableFuture.supplyAsync(() -> {
            try {
                return host.awaitConnectionAndKeepAlive();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        Onboarding.PreparedJoin join = closing(new Onboarding(memberComputer.profile(), ignored -> {
        }).importInvitation(host.invitationLink(), memberSide.handler(), ignored -> {
        }));
        PeerSession memberSession = join.connectAndKeepAlive();
        PeerSession leaderSession = hosted.get(30, TimeUnit.SECONDS);
        closing(() -> memberSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST));
        closing(() -> leaderSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST));
        leaderSide.attach(leaderSession);
        memberSide.attach(memberSession);

        // The member's writer claims through its own computer; the leader's reviewer claims on the leader.
        WorkIntent writer = LaneWakeTest.lane(project, "agt-writer", "src/slug.py");
        assertTrue(new WorkIntentService(new PredictionEventStore(memberLog, project), member).announce(writer).acquired());
        WorkIntent reviewer = LaneWakeTest.lane(project, "agt-reviewer", "src/review.txt");
        assertTrue(new WorkIntentService(new PredictionEventStore(leaderLog, project), leader).announce(reviewer).acquired());
        await(() -> PredictionEventStore.recordedEvents(memberLog) == PredictionEventStore.recordedEvents(leaderLog));

        // The writer was told WAIT on the member and ended its turn; its Stop hook holds it there.
        LaneWake.noteWait(memberComputer, "conn-writer", "agt-writer", null, java.util.OptionalLong.empty());
        CompletableFuture<Optional<String>> held = CompletableFuture.supplyAsync(() -> LaneWake.awaitAddressed(memberComputer, "conn-writer", Duration.ofSeconds(60)));
        Thread.sleep(1_000);
        assertTrue(!held.isDone(), "held while nothing is addressed to it");

        long asked = System.nanoTime();
        new WorkIntentService(new PredictionEventStore(leaderLog, project), leader).request("agt-reviewer", writer.intentId(), CoordinationRequest.Kind.CONTRACT, "May I review slug.py?");
        String reason = held.get(30, TimeUnit.SECONDS).orElseThrow();
        long latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asked);

        assertTrue(reason.contains("coordination request"), reason);
        assertEquals(MemberReplication.State.CONNECTED, memberSide.state());
        System.out.println("WAKE_LATENCY_ACROSS_REPLICATION_MS=" + latencyMillis);
        // The leader's notice, not the member's 15 s pull, carried it.
        assertTrue(latencyMillis < MemberReplication.PULL_INTERVAL_SECONDS * 1000L / 3, "woken in " + latencyMillis + " ms");
    }
}
