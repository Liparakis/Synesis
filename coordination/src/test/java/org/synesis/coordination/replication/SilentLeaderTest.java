package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

/**
 * A leader that holds a session open but stops answering (a stalled process, a long pause) costs a member's pulls
 * their wait and nothing else: since ADR-0098 step 4 a member appends to its own log and never waits for the leader,
 * so the agent's append is as fast as with no leader at all, and the loopback relay answers at once.
 */
@Timeout(120)
final class SilentLeaderTest {

    private final UUID project = UUID.randomUUID();
    private PeerSession memberSession;
    private MemberReplication member;
    private Onboarding.PreparedHost host;
    private Onboarding.PreparedJoin join;

    @AfterEach
    void close() {
        if (memberSession != null) {
            memberSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
        }
        for (AutoCloseable resource : new AutoCloseable[] {member, join, host}) {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception ignored) {
                // Best effort.
            }
        }
    }

    @Test
    void aLeaderThatNeverAnswersDoesNotHoldUpAMembersAppendOrTheRelay(@TempDir Path temp) throws Exception {
        NodeIdentity leader = new IdentityBootstrap(temp.resolve("leader/profile")).loadOrCreate().identity();
        NodeIdentity self = new IdentityBootstrap(temp.resolve("member/profile")).loadOrCreate().identity();
        Path memberRoot = temp.resolve("member/coordination");
        ReplicationSettings.member(leader.nodeId()).write(memberRoot);
        member = MemberReplication.open(memberRoot, project);
        host = new Onboarding(temp.resolve("leader/profile"), ignored -> {
        }).createCompactInvitation(null, (remote, payload) -> new CompletableFuture<>(), ignored -> {
        });
        CompletableFuture.supplyAsync(() -> {
            try {
                return host.awaitConnectionAndKeepAlive();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        join = new Onboarding(temp.resolve("member/profile"), ignored -> {
        }).importInvitation(host.invitationLink(), member.handler(), ignored -> {
        });
        memberSession = join.connectAndKeepAlive();
        member.attach(memberSession);
        UUID intentId = UUID.randomUUID();
        WorkIntent lane = new WorkIntent(intentId, project, "agt-b", "codex", UUID.randomUUID(), "Build a board",
                "CRUD and persistence", "base-commit", List.of(ResourceSelector.pathExact("src/b.py")), 1,
                UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());

        long started = System.nanoTime();
        boolean acquired = new WorkIntentService(new PredictionEventStore(memberRoot, project), self).announce(lane)
                .acquired();
        Duration appendTook = Duration.ofNanos(System.nanoTime() - started);
        started = System.nanoTime();
        ReplicationMessage relayed = ReplicationCodec.decode(member.relay(ReplicationCodec.encode(
                new ReplicationMessage.Propose(project, new byte[] {1, 2, 3}))));
        Duration relayTook = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(acquired);
        assertEquals(2, new PredictionEventStore(memberRoot, project).events().size());
        assertTrue(appendTook.compareTo(Duration.ofSeconds(3)) < 0, "the append did not wait for the leader: " + appendTook);
        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("REPLICATION_REQUEST_UNSUPPORTED")),
                relayed);
        assertTrue(relayTook.compareTo(Duration.ofSeconds(1)) < 0, "the relay did not wait: " + relayTook);
        assertEquals(leader.nodeId(), memberSession.remoteNodeId());
        assertNotEquals(MemberReplication.State.CONNECTED, member.state(), "a leader that never answers is not in step");
    }
}
