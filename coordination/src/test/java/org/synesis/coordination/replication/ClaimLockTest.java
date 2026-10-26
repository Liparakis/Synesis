package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

/**
 * The claim lock between connected computers (ADR-0098 step 4, "Claims are locks wherever they can be checked"): an
 * owner and a member computer joined by a real Link session ask each other about a claim before its agent is
 * answered. Claims asked about directly are signed in a scratch log, so the question is the only thing that decides.
 */
@Timeout(180)
final class ClaimLockTest {

    private static final String OVERLAP = "OVERLAPPING_CLAIM";
    private static final int RACES = 10;

    private final UUID project = UUID.randomUUID();
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private Path temp;
    private Path leaderRoot;
    private Path memberRoot;
    private Path leaderProfile;
    private Path memberProfile;
    private NodeIdentity leader;
    private NodeIdentity member;
    private int scratches;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        this.temp = temp;
        leaderRoot = temp.resolve("leader/coordination");
        memberRoot = temp.resolve("member/coordination");
        leaderProfile = temp.resolve("leader/profile");
        memberProfile = temp.resolve("member/profile");
        leader = new IdentityBootstrap(leaderProfile).loadOrCreate().identity();
        member = new IdentityBootstrap(memberProfile).loadOrCreate().identity();
        ReplicationSettings.leader(Set.of(member.nodeId())).write(leaderRoot);
        ReplicationSettings.member(leader.nodeId()).write(memberRoot);
    }

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

    private CoordinationHttpServer server(Path root, NodeIdentity identity) throws Exception {
        CoordinationHttpServer server = closing(new CoordinationHttpServer(
                new CoordinationService(new PredictionEventStore(root, project), identity),
                new InetSocketAddress("127.0.0.1", 0)));
        server.start();
        return server;
    }

    /** Both computers, started and joined by one Link session; the member's server is the loopback relay. */
    private final class Computers {
        final LeaderReplication leaderSide;
        final MemberReplication memberSide;
        final PeerSession leaderSession;
        final PeerSession memberSession;

        Computers() throws Exception {
            leaderSide = closing(new LeaderReplication(leaderRoot, project, Set.of(member.nodeId()), () -> {
            }));
            memberSide = closing(MemberReplication.open(memberRoot, project));
            server(leaderRoot, leader);
            memberSide.serve(server(memberRoot, member));
            Onboarding.PreparedHost host = closing(new Onboarding(leaderProfile, ignored -> {
            }).createCompactInvitation(null, leaderSide.handler(), ignored -> {
            }));
            CompletableFuture<PeerSession> hosted = CompletableFuture.supplyAsync(() -> {
                try {
                    return host.awaitConnectionAndKeepAlive();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            Onboarding.PreparedJoin join = closing(new Onboarding(memberProfile, ignored -> {
            }).importInvitation(host.invitationLink(), memberSide.handler(), ignored -> {
            }));
            memberSession = join.connectAndKeepAlive();
            leaderSession = hosted.get(30, TimeUnit.SECONDS);
            closing(() -> memberSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST));
            closing(() -> leaderSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST));
        }

        void attach() {
            leaderSide.attach(leaderSession);
            memberSide.attach(memberSession);
        }
    }

    private static WorkIntent lane(UUID project, String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, project, participant, "codex", UUID.randomUUID(), "Build a board",
                "CRUD and persistence", "base-commit", List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(),
                WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
    }

    private PredictionEventStore store(Path root) throws Exception {
        return new PredictionEventStore(root, project);
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached in " + seconds + " s");
    }

    private boolean applied(Path root, int count) {
        try {
            return store(root).events().size() == count;
        } catch (Exception busy) {
            return false;
        }
    }

    /** The signed announcement a computer would send, written in a scratch log that no exchange ever reads. */
    private ReplicationMessage.ClaimSeen claimBy(NodeIdentity identity, String participant, String path)
            throws Exception {
        Path scratchRoot = temp.resolve("scratch" + scratches++);
        assertTrue(new WorkIntentService(store(scratchRoot), identity).announce(lane(project, participant, path))
                .acquired());
        PredictionEvent announced = store(scratchRoot).events().getLast();
        assertEquals(PredictionEventType.WORK_INTENT_ANNOUNCED, announced.type());
        return new ReplicationMessage.ClaimSeen(project, announced.encoded());
    }

    private ReplicationMessage.ClaimIntent registrationOf(ReplicationMessage.ClaimSeen seen) throws Exception {
        return new ReplicationMessage.ClaimIntent(project, PredictionEvent.decode(seen.entry()).payload());
    }

    private static String codeOf(byte[] answer) {
        try {
            return ((ReplicationMessage.ClaimAnswer) ReplicationCodec.decode(answer)).code();
        } catch (org.synesis.coordination.exception.CoordinationException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private String askMember(Computers computers, ReplicationMessage.ClaimSeen seen) {
        return codeOf(computers.memberSide.relay(ReplicationCodec.encode(seen)));
    }

    private void leaderHolds(String participant, String path) throws Exception {
        assertTrue(new WorkIntentService(store(leaderRoot), leader).announce(lane(project, participant, path))
                .acquired());
    }

    @Test
    void anOverlappingClaimGivesWayToTheOwnersActiveLaneThatTheMemberHasPulled() throws Exception {
        Computers computers = new Computers();
        computers.attach();
        leaderHolds("agt-a", "src/a.txt");
        await(() -> applied(memberRoot, 2), 8);

        assertEquals(OVERLAP, askMember(computers, claimBy(member, "agt-b", "src/a.txt")));
        assertEquals("", askMember(computers, claimBy(member, "agt-b", "src/other.txt")));
    }

    @Test
    void anOverlappingClaimGivesWayToTheOwnersActiveLaneRightAfterItWasMadeWhetherOrNotThePullHasHappened()
            throws Exception {
        Computers computers = new Computers();
        computers.attach();
        ReplicationMessage.ClaimSeen overlapping = claimBy(member, "agt-b", "src/a.txt");
        ReplicationMessage.ClaimSeen apart = claimBy(member, "agt-b", "src/other.txt");
        leaderHolds("agt-a", "src/a.txt");

        // No wait for the member's pull: the asked computer answers from its own log, not from what was pulled.
        assertEquals(OVERLAP, askMember(computers, overlapping));
        assertEquals("", askMember(computers, apart));
    }

    @Test
    void aLaneOfTheClaimantsOwnComputerIsNotCountedAgainByTheAskedComputer() throws Exception {
        Computers computers = new Computers();
        computers.attach();
        assertTrue(new WorkIntentService(store(memberRoot), member).announce(lane(project, "agt-b", "src/a.txt"))
                .acquired());
        await(() -> applied(leaderRoot, 2), 8);

        assertEquals("", askMember(computers, claimBy(member, "agt-c", "src/a.txt")));
    }

    @Test
    void withNoConnectedComputerAClaimStandsAtOnce() throws Exception {
        Computers computers = new Computers();
        ReplicationMessage.ClaimSeen seen = claimBy(member, "agt-b", "src/a.txt");
        leaderHolds("agt-a", "src/a.txt");

        long started = System.nanoTime();
        String answer = askMember(computers, seen);

        assertEquals("", answer);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 2, "must not wait for anyone");
    }

    @Test
    void ofTwoOverlappingClaimsMadeAtTheSameMomentExactlyTheLowerNodesStands() throws Exception {
        Computers computers = new Computers();
        computers.attach();
        boolean leaderIsLower = leader.nodeId().compareTo(member.nodeId()) < 0;
        for (int race = 0; race < RACES; race++) {
            String path = "src/race" + race + ".txt";
            ReplicationMessage.ClaimSeen fromLeader = claimBy(leader, "agt-a", path);
            ReplicationMessage.ClaimSeen fromMember = claimBy(member, "agt-b", path);
            // As the agent's process does before it appends (ClaimLock.announce): each computer knows its own claim
            // is coming, so whichever check starts or finishes first, the other's question finds it.
            computers.leaderSide.exchange().registerClaim(registrationOf(fromLeader));
            computers.memberSide.exchange().registerClaim(registrationOf(fromMember));
            CountDownLatch together = new CountDownLatch(1);
            CompletableFuture<String> leaders = CompletableFuture.supplyAsync(() -> {
                await(together);
                return codeOf(computers.leaderSide.exchange().checkClaim(fromLeader));
            });
            CompletableFuture<String> members = CompletableFuture.supplyAsync(() -> {
                await(together);
                return codeOf(computers.memberSide.exchange().checkClaim(fromMember));
            });
            together.countDown();

            String leaderAnswer = leaders.get(20, TimeUnit.SECONDS);
            String memberAnswer = members.get(20, TimeUnit.SECONDS);

            assertEquals(leaderIsLower ? "" : OVERLAP, leaderAnswer, "race " + race + " leader");
            assertEquals(leaderIsLower ? OVERLAP : "", memberAnswer, "race " + race + " member");
        }
    }

    @Test
    void ofTwoAgentsClaimingAtTheSameMomentThroughTheServicesOnlyOneClaimStandsAndTheOtherIsReleased()
            throws Exception {
        Computers computers = new Computers();
        computers.attach();
        computers.leaderSide.serve(server(leaderRoot, leader));
        for (int race = 0; race < RACES; race++) {
            String path = "src/service" + race + ".txt";
            WorkIntent fromLeader = lane(project, "agt-a", path);
            WorkIntent fromMember = lane(project, "agt-b", path);
            CountDownLatch together = new CountDownLatch(1);
            CompletableFuture<Boolean> leaders = CompletableFuture.supplyAsync(() -> {
                await(together);
                return stands(leaderRoot, leader, fromLeader);
            });
            CompletableFuture<Boolean> members = CompletableFuture.supplyAsync(() -> {
                await(together);
                return stands(memberRoot, member, fromMember);
            });
            together.countDown();

            boolean leaderStands = leaders.get(30, TimeUnit.SECONDS);
            boolean memberStands = members.get(30, TimeUnit.SECONDS);

            assertTrue(leaderStands ^ memberStands,
                    "race " + race + ": leader " + leaderStands + ", member " + memberStands + " " + outcomes);
        }
    }

    private final java.util.Queue<String> outcomes = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private boolean stands(Path root, NodeIdentity identity, WorkIntent intent) {
        try {
            boolean acquired = new WorkIntentService(store(root), identity).announce(intent).acquired();
            outcomes.add(identity.nodeId().substring(0, 12) + (acquired ? " stands" : " refused locally"));
            return acquired;
        } catch (Exception refused) {
            outcomes.add(identity.nodeId().substring(0, 12) + " " + refused);
            if (!String.valueOf(refused.getMessage()).contains(OVERLAP)) {
                throw new IllegalStateException(refused);
            }
            return false;
        }
    }

    @Test
    void anAgentWhoseClaimGivesWayIsToldSoAndItsLogHoldsTheAnnouncementThenItsRelease() throws Exception {
        Computers computers = new Computers();
        computers.attach();
        for (int attempt = 0; attempt < 20; attempt++) {
            String path = "src/e2e" + attempt + ".txt";
            // The member claims before the owner's lane reaches it, so only the lock between computers can refuse.
            leaderHolds("agt-a", path);
            WorkIntent mine = lane(project, "agt-b", path);
            Exception refusal = null;
            try {
                new WorkIntentService(store(memberRoot), member).announce(mine);
            } catch (Exception thrown) {
                refusal = thrown;
            }
            if (refusal == null) {
                continue;
            }
            assertTrue(String.valueOf(refusal.getMessage()).contains(OVERLAP), refusal.toString());
            List<PredictionEvent> own = store(memberRoot).events().stream()
                    .filter(event -> event.actorNodeId().equals(member.nodeId())).toList();
            int announced = indexOf(own, PredictionEventType.WORK_INTENT_ANNOUNCED);
            if (announced < 0) {
                // The pull brought the owner's lane in first, so the member's own log refused the append: not the lock between computers. A slow machine makes this likelier; try again.
                continue;
            }
            int released = indexOf(own, PredictionEventType.WORK_INTENT_RELEASED);
            assertTrue(announced >= 0 && released > announced, "announced " + announced + ", released " + released);
            assertEquals(released, own.size() - 1, "the release is the last entry of the member's log");
            return;
        }
        fail("the member pulled the owner's lane before every attempt; the lock between computers was never reached");
    }

    private static int indexOf(List<PredictionEvent> events, PredictionEventType type) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).type() == type) {
                return index;
            }
        }
        return -1;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
