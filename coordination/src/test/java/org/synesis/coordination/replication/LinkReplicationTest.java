package org.synesis.coordination.replication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.coordination.replication.LeaderReplication.MemberConnection.State.CONNECTED;
import static org.synesis.coordination.replication.LeaderReplication.MemberConnection.State.DISCONNECTED;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProposalOutcome;
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
 * A leader and a member computer, each with its own project root, node identity and coordination server, joined by
 * a real authenticated QUIC Link session on loopback (ADR-0098 step 4). The processes of the member's
 * agents are the stores opened here: they know their role only from the persisted settings file.
 */
@Timeout(120)
final class LinkReplicationTest {

    private final UUID project = UUID.randomUUID();
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private Path temp;
    private Path leaderRoot;
    private Path memberRoot;
    private Path leaderProfile;
    private Path memberProfile;
    private NodeIdentity leader;
    private NodeIdentity member;

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

    /** A started loopback coordination server over a coordination folder, as the daemon runs one. */
    private CoordinationHttpServer server(Path root, NodeIdentity identity) throws Exception {
        CoordinationHttpServer server = closing(new CoordinationHttpServer(
                new CoordinationService(new PredictionEventStore(root, project), identity),
                new InetSocketAddress("127.0.0.1", 0)));
        server.start();
        return server;
    }

    /** Both computers' replication objects, started and joined by one Link session. */
    private final class Computers {
        final LeaderReplication leaderSide;
        final MemberReplication memberSide;
        final PeerSession leaderSession;
        final PeerSession memberSession;

        Computers(Set<String> members) throws Exception {
            this(members, () -> {
            });
        }

        Computers(Set<String> members, Runnable afterArrivals) throws Exception {
            leaderSide = closing(new LeaderReplication(leaderRoot, project, members, afterArrivals));
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

    private static WorkIntent lane(UUID project, UUID group, String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, project, participant, "codex", UUID.randomUUID(), "Build a board",
                "CRUD and persistence", "base-commit", List.of(ResourceSelector.pathExact(path)), 1, group,
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

    /** Whether a fresh store over the folder holds this many entries that took effect; false while it cannot open. */
    private boolean applied(Path root, int count) {
        try {
            return store(root).events().size() == count;
        } catch (Exception busy) {
            return false;
        }
    }

    private static List<String> encodedSet(PredictionEventStore store) {
        return store.events().stream().map(event -> java.util.HexFormat.of().formatHex(event.encoded())).sorted()
                .toList();
    }

    private ReplicationMessage sendFromMember(Computers computers, ReplicationMessage request) throws Exception {
        return ReplicationCodec.decode(computers.memberSession.requestApplication(ReplicationCodec.encode(request))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    private ReplicationMessage sendFromLeader(Computers computers, ReplicationMessage request) throws Exception {
        return ReplicationCodec.decode(computers.leaderSession.requestApplication(ReplicationCodec.encode(request))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    private static ReplicationMessage refused(String code) {
        return new ReplicationMessage.Outcome(new ProposalOutcome.Refused(code));
    }

    @Test
    void aClaimMadeOfflineIsPulledByTheLeaderOnceConnectedAndTheLeadersLaterEntryReachesTheMember()
            throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        // The service is the one a leader uses; this store resolved its role from replication.properties, and no
        // session exists yet: the entry goes to the member's own log and nothing waits for the leader.
        assertTrue(new WorkIntentService(store(memberRoot), member).announce(lane).acquired());
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
        assertEquals(2, store(memberRoot).events().size());

        computers.attach();
        await(() -> PredictionEventStore.recordedEvents(leaderRoot) == 2, 8);

        await(() -> applied(leaderRoot, 2), 8);
        PredictionEventStore onLeader = store(leaderRoot);
        assertTrue(onLeader.events().stream().allMatch(event -> event.actorNodeId().equals(member.nodeId())));
        assertEquals(PredictionEventType.WORK_INTENT_ANNOUNCED, onLeader.events().getLast().type());
        for (long sequence = 1; sequence <= 2; sequence++) {
            String name = String.format("%020d.sce", sequence);
            assertArrayEquals(Files.readAllBytes(memberRoot.resolve("events").resolve(name)),
                    Files.readAllBytes(leaderRoot.resolve("peers").resolve(member.nodeId()).resolve("events")
                            .resolve(name)));
        }
        assertTrue(onLeader.events().stream().allMatch(event -> {
            try {
                return event.verify();
            } catch (java.security.GeneralSecurityException failure) {
                return false;
            }
        }));

        // The leader's own agent appends; the member learns of it by notice, well before the 15 second pull.
        WorkIntent leaders = lane(project, UUID.randomUUID(), "agt-a", "src/a.py");
        assertTrue(new WorkIntentService(store(leaderRoot), leader).announce(leaders).acquired());
        await(() -> PredictionEventStore.recordedEvents(memberRoot) == 4, 8);
        await(() -> applied(memberRoot, 4), 8);
        assertEquals(encodedSet(store(leaderRoot)), encodedSet(store(memberRoot)));
        assertEquals(LogExchange.State.CONNECTED, computers.memberSide.exchange().state(leader.nodeId()));
        await(() -> computers.memberSide.state() == MemberReplication.State.CONNECTED, 8);
        assertEquals("CONNECTED", MemberReplication.recordedState(memberRoot).orElseThrow());
    }

    @Test
    void aMemberThatHasTheLeadersClaimRefusesAnOverlappingOneAndNothingReachesEitherLog() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        WorkIntent held = lane(project, UUID.randomUUID(), "agt-a", "src/shared.py");
        assertTrue(new WorkIntentService(store(leaderRoot), leader).announce(held).acquired());
        await(() -> applied(memberRoot, 2), 8);
        WorkIntent overlapping = lane(project, held.group().value(), "agt-b", "src/shared.py");

        IOException refusal = assertThrows(IOException.class,
                () -> store(memberRoot).append(overlapping.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                        member.nodeId(), CollaborationCodec.encodeIntent(overlapping), member));

        assertEquals("OVERLAPPING_CLAIM", refusal.getMessage());
        assertEquals(0L, store(memberRoot).logHeads().get(PredictionEventStore.OWN_LOG));
        assertEquals(2, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void aProposalOfAStepTwoMemberIsAnsweredUnsupportedAndNothingIsPlaced() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        byte[] entry = {1, 2, 3};

        assertEquals(refused("REPLICATION_REQUEST_UNSUPPORTED"),
                sendFromMember(computers, new ReplicationMessage.Propose(project, entry)));
        assertEquals(refused("REPLICATION_REQUEST_UNSUPPORTED"),
                ReplicationCodec.decode(computers.memberSide.relay(ReplicationCodec.encode(
                        new ReplicationMessage.Propose(project, entry)))));
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void theLeaderAnswersWhatItHoldsUnderItsNodeIdToAdmittedNodes() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        assertTrue(new WorkIntentService(store(leaderRoot), leader)
                .announce(lane(project, UUID.randomUUID(), "agt-a", "src/a.py")).acquired());

        // The leader's own log is named by its node ID in the heads it answers, never by a local alias.
        ReplicationMessage heads = sendFromMember(computers, new ReplicationMessage.Heads(project));
        assertTrue(heads instanceof ReplicationMessage.HeadList list && list.heads().get(leader.nodeId()) == 2L,
                heads.toString());
        ReplicationMessage page = sendFromMember(computers,
                new ReplicationMessage.Pull(project, leader.nodeId(), 0, 10, 100_000));
        assertEquals(2, ((ReplicationMessage.Entries) page).entries().size());
        assertEquals(List.of(), ((ReplicationMessage.Entries) sendFromMember(computers,
                new ReplicationMessage.Pull(project, leader.nodeId(), 2, 10, 100_000))).entries());
        // A log it does not hold is an empty page.
        assertEquals(List.of(), ((ReplicationMessage.Entries) sendFromMember(computers,
                new ReplicationMessage.Pull(project, "sl1-nobody", 0, 10, 100_000))).entries());
    }

    @Test
    void aNodeThatIsNotAMemberKeepsItsSessionButGetsNeitherTheHeadsNorTheLogsAndIsNotRecordedAsAMember() throws Exception {
        Computers computers = new Computers(Set.of("sl1-someone-else"));
        // The session is kept for a joiner the owner accepts later; it is served nothing until the settings name it.
        computers.leaderSide.attach(computers.leaderSession);
        assertFalse(computers.leaderSide.isMember(member.nodeId()));
        assertTrue(recorded(member.nodeId()).isEmpty());
        assertTrue(new WorkIntentService(store(leaderRoot), leader)
                .announce(lane(project, UUID.randomUUID(), "agt-a", "src/a.py")).acquired());

        assertEquals(refused("PROPOSER_NOT_A_MEMBER"), sendFromMember(computers, new ReplicationMessage.Heads(project)));
        assertEquals(refused("PROPOSER_NOT_A_MEMBER"),
                sendFromMember(computers, new ReplicationMessage.Pull(project, leader.nodeId(), 0, 10, 1000)));
        assertEquals(refused("PROPOSER_NOT_A_MEMBER"),
                sendFromMember(computers, new ReplicationMessage.Notice(project, 5)));
        assertEquals(2, PredictionEventStore.recordedEvents(leaderRoot));
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
    }

    @Test
    void anotherProjectsRequestsAreRefusedAndWhatAMembersHandlerDoesNotServeIsUnsupported() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        UUID other = UUID.randomUUID();

        assertEquals(refused("PROPOSAL_PROJECT_MISMATCH"),
                sendFromMember(computers, new ReplicationMessage.Pull(other, member.nodeId(), 0, 10, 1000)));
        assertEquals(refused("PROPOSAL_PROJECT_MISMATCH"), sendFromMember(computers, new ReplicationMessage.Heads(other)));
        // A notice only makes a pull happen; one for another project is heard and ignored.
        assertEquals(new ReplicationMessage.Noticed(), sendFromMember(computers, new ReplicationMessage.Notice(other, 5)));
        assertEquals(new ReplicationMessage.Noticed(),
                sendFromLeader(computers, new ReplicationMessage.Notice(project, 5)));
        assertEquals(refused("REPLICATION_REQUEST_UNSUPPORTED"),
                sendFromLeader(computers, new ReplicationMessage.Propose(project, new byte[] {1})));
        // The member's loopback relay serves nothing since step 4.
        assertEquals(refused("REPLICATION_REQUEST_UNSUPPORTED"), ReplicationCodec.decode(
                computers.memberSide.relay(ReplicationCodec.encode(new ReplicationMessage.Heads(project)))));
    }

    @Test
    void aMemberWithNoLeaderSessionAppendsToItsOwnLogAtOnce() throws Exception {
        // The member's coordination server is up and relays, but no Link session was ever attached.
        MemberReplication replication = closing(MemberReplication.open(memberRoot, project));
        replication.serve(server(memberRoot, member));
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        long started = System.nanoTime();
        assertTrue(new WorkIntentService(store(memberRoot), member).announce(lane).acquired());

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5, "must not wait for a leader");
        assertEquals(2, store(memberRoot).events().size());
        assertEquals(MemberReplication.State.DISCONNECTED, replication.state());
    }

    @Test
    void aMemberProcessWithNoCoordinationServerAppendsToItsOwnLogToo() throws Exception {
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        assertTrue(new WorkIntentService(store(memberRoot), member).announce(lane).acquired());

        assertEquals(2, PredictionEventStore.recordedEvents(memberRoot));
        assertFalse(Files.exists(memberRoot.resolve("replication.endpoint")));
    }

    @Test
    void aLostSessionDoesNotStopAMembersAppends() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        computers.leaderSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        await(() -> computers.memberSession.terminalCompletion().toCompletableFuture().isDone(), 10);
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        long started = System.nanoTime();
        assertTrue(new WorkIntentService(store(memberRoot), member).announce(lane).acquired());

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5, "must not wait for a session");
        assertEquals(2, store(memberRoot).events().size());
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void theLeaderRecordsWhetherEachMemberIsConnectedAndSinceWhenAndRemovesTheRecordWhenItStops() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        await(() -> recorded(member.nodeId()).filter(found -> found.state() == DISCONNECTED).isPresent(), 8);
        Instant serving = recorded(member.nodeId()).orElseThrow().since();

        computers.attach();
        await(() -> recorded(member.nodeId()).filter(found -> found.state() == CONNECTED).isPresent(), 8);
        Instant connected = recorded(member.nodeId()).orElseThrow().since();
        assertFalse(connected.isBefore(serving));

        computers.memberSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        await(() -> recorded(member.nodeId()).filter(found -> found.state() == DISCONNECTED).isPresent(), 10);
        assertFalse(recorded(member.nodeId()).orElseThrow().since().isBefore(connected));

        computers.leaderSide.close();
        assertEquals(Optional.empty(), LeaderReplication.recordedMembers(leaderRoot));
    }

    @Test
    void theLeadersRecordNamesOnlyCurrentMembersAndSkipsLinesItCannotRead() throws Exception {
        Files.writeString(leaderRoot.resolve(LeaderReplication.MEMBERS_FILE),
                "sl1-removed CONNECTED 2026-10-17T10:00:00Z\nsl1-garbled CONNECTED yesterday\nhalf-a-line\n");
        assertEquals(Map.of("sl1-removed", new LeaderReplication.MemberConnection(CONNECTED, Instant.parse("2026-10-17T10:00:00Z"))),
                LeaderReplication.recordedMembers(leaderRoot).orElseThrow());

        closing(new LeaderReplication(leaderRoot, project, Set.of(member.nodeId())));

        await(() -> recorded(member.nodeId()).isPresent(), 8);
        assertEquals(Set.of(member.nodeId()), LeaderReplication.recordedMembers(leaderRoot).orElseThrow().keySet());
    }

    private Optional<LeaderReplication.MemberConnection> recorded(String nodeId) {
        return LeaderReplication.recordedMembers(leaderRoot).map(members -> members.get(nodeId));
    }

    @Test
    void aCopyThatDiffersFromTheLeadersLogStopsThePullingLoudlyAndNothingMoreIsPlaced() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        UUID group = UUID.randomUUID();
        assertTrue(new WorkIntentService(store(leaderRoot), leader)
                .announce(lane(project, group, "agt-a", "src/a.py")).acquired());
        await(() -> PredictionEventStore.recordedEvents(memberRoot) == 2, 8);
        // The member's copy of the leader's second entry becomes another valid entry the leader never wrote.
        Path scratch = temp.resolve("scratch/coordination");
        Path copy = memberRoot.resolve("peers").resolve(leader.nodeId()).resolve("events");
        Files.createDirectories(scratch.resolve("events"));
        String first = String.format("%020d.sce", 1);
        String second = String.format("%020d.sce", 2);
        Files.copy(copy.resolve(first), scratch.resolve("events").resolve(first));
        assertTrue(new WorkIntentService(new PredictionEventStore(scratch, project), leader)
                .announce(lane(project, group, "agt-z", "src/z.py")).acquired());
        Files.copy(scratch.resolve("events").resolve(second), copy.resolve(second),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(new WorkIntentService(store(leaderRoot), leader)
                .announce(lane(project, group, "agt-c", "src/c.py")).acquired());

        await(() -> computers.memberSide.state() == MemberReplication.State.DIVERGED, 8);

        assertEquals("DIVERGED", MemberReplication.recordedState(memberRoot).orElseThrow());
        assertEquals(LogExchange.State.DIVERGED, computers.memberSide.exchange().state(leader.nodeId()));
        assertEquals(2, PredictionEventStore.recordedEvents(memberRoot), "nothing more is placed in the diverged copy");
    }

    @Test
    void eachSideIsOpenedFromItsOwnPersistedRoleAndFromNoOther() throws Exception {
        assertThrows(IOException.class, () -> LeaderReplication.open(memberRoot, project));
        assertThrows(IOException.class, () -> MemberReplication.open(leaderRoot, project));
        assertThrows(IOException.class, () -> MemberReplication.open(temp.resolve("none"), project));
        Files.writeString(leaderRoot.resolve(ReplicationSettings.FILE), "role=boss\n");
        assertThrows(IOException.class, () -> LeaderReplication.open(leaderRoot, project));
        ReplicationSettings.leader(Set.of(member.nodeId())).write(leaderRoot);

        LeaderReplication opened = closing(LeaderReplication.open(leaderRoot, project));
        assertTrue(opened.handler() != null);
    }

    @Test
    void aSessionToANodeThatIsNotTheLeaderCannotBeAttachedToAMember() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        MemberReplication other = closing(new MemberReplication(memberRoot, project, "sl1-not-the-leader"));
        assertThrows(IllegalArgumentException.class, () -> other.attach(computers.memberSession));
        assertFalse(other.state() == MemberReplication.State.CONNECTED);
    }

    @Test
    void theOwnersHookRunsAfterAMembersEntriesArrivedAndNotWhenNothingDid() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Computers computers = new Computers(Set.of(member.nodeId()), calls::incrementAndGet);
        computers.attach();
        await(() -> computers.leaderSide.exchange().state(member.nodeId()) == LogExchange.State.CONNECTED, 8);
        assertEquals(0, calls.get(), "a pull that placed nothing is not news");

        assertTrue(new WorkIntentService(store(memberRoot), member)
                .announce(lane(project, UUID.randomUUID(), "agt-b", "src/b.py")).acquired());

        await(() -> calls.get() >= 1, 10);
        assertEquals(2, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void theOwnersCodeIsToldAfterEveryPullThatReachedTheMemberEvenWhenNothingNewCameSoALateCommitIsRetried() throws Exception {
        AtomicInteger told = new AtomicInteger();
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.leaderSide.whenPulled(told::incrementAndGet);
        computers.attach();
        await(() -> computers.leaderSide.exchange().state(member.nodeId()) == LogExchange.State.CONNECTED, 8);
        await(() -> told.get() >= 1, 10);
        int first = told.get();

        // No entry arrives, so only the periodic pull can tell the code again (the commit may be what is late).
        await(() -> told.get() > first, LogExchange.PULL_INTERVAL_SECONDS + 10);

        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot), "no entry was placed");
    }

    @Test
    void aHookThatFailsDoesNotUndoTheCopyOrStopTheExchange() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()), () -> {
            throw new IllegalStateException("the merge driver is down");
        });
        computers.attach();

        assertTrue(new WorkIntentService(store(memberRoot), member)
                .announce(lane(project, UUID.randomUUID(), "agt-b", "src/b.py")).acquired());

        await(() -> PredictionEventStore.recordedEvents(leaderRoot) == 2, 10);
        await(() -> applied(leaderRoot, 2), 10);
        assertEquals(LogExchange.State.CONNECTED, computers.leaderSide.exchange().state(member.nodeId()));
    }
}
