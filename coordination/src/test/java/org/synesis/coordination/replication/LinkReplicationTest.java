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
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
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
 * a real authenticated QUIC Link session on loopback (ADR-0098 step 2, slice 2). The processes of the member's
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
            leaderSide = closing(new LeaderReplication(leaderRoot, project, members));
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

    private static void assertSameLog(PredictionEventStore left, PredictionEventStore right) {
        assertEquals(left.events().size(), right.events().size());
        for (int index = 0; index < left.events().size(); index++) {
            assertArrayEquals(left.events().get(index).encoded(), right.events().get(index).encoded());
        }
    }

    private byte[] proposalAtLeaderHead(PredictionEventType type, UUID prediction, byte[] payload, NodeIdentity signer)
            throws Exception {
        List<PredictionEvent> events = store(leaderRoot).events();
        byte[] digest = events.isEmpty() ? new byte[32] : events.getLast().digest();
        return PredictionEvent.create(new ProjectId(project), new PredictionId(prediction),
                new EventSequence(events.size() + 1L), type, new NodeId(signer.nodeId()), payload, digest, signer,
                System.currentTimeMillis()).encoded();
    }

    private ReplicationMessage sendFromMember(Computers computers, ReplicationMessage request) throws Exception {
        return ReplicationCodec.decode(computers.memberSession.requestApplication(ReplicationCodec.encode(request))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    @Test
    void aClaimMadeByAProcessThatOnlyKnowsThePersistedRoleLandsOnTheLeaderAndTheLeadersLaterEntryReachesTheMember()
            throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        // The service is the one a leader uses; this store resolved its role from replication.properties.
        assertTrue(new WorkIntentService(store(memberRoot), member).announce(lane).acquired());

        PredictionEventStore onLeader = store(leaderRoot);
        assertEquals(2, onLeader.events().size());
        assertTrue(onLeader.events().stream().allMatch(event -> event.actorNodeId().equals(member.nodeId())));
        assertEquals(PredictionEventType.WORK_INTENT_ANNOUNCED, onLeader.events().getLast().type());
        assertSameLog(onLeader, store(memberRoot));
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
        assertSameLog(store(leaderRoot), store(memberRoot));
        assertEquals(MemberReplication.State.CONNECTED, computers.memberSide.state());
        assertEquals("CONNECTED", MemberReplication.recordedState(memberRoot).orElseThrow());
    }

    @Test
    void aClaimOverlappingTheLeadersIsRefusedAcrossTheLinkAndNothingIsPlacedOnEitherLog() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        WorkIntent held = lane(project, UUID.randomUUID(), "agt-a", "src/shared.py");
        assertTrue(new WorkIntentService(store(leaderRoot), leader).announce(held).acquired());
        WorkIntent overlapping = lane(project, held.group().value(), "agt-b", "src/shared.py");

        IOException refusal = assertThrows(IOException.class,
                () -> store(memberRoot).append(overlapping.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                        member.nodeId(), CollaborationCodec.encodeIntent(overlapping), member));
        assertEquals("OVERLAPPING_CLAIM", refusal.getMessage());

        // The leader decides the same when the member's entry reaches it at the leader's head.
        byte[] atHead = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_ANNOUNCED, overlapping.intentId(),
                CollaborationCodec.encodeIntent(overlapping), member);
        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("OVERLAPPING_CLAIM")),
                sendFromMember(computers, new ReplicationMessage.Propose(project, atHead)));
        assertEquals(2, store(leaderRoot).events().size());
    }

    @Test
    void theLeaderTakesTheSenderFromTheSessionNotFromTheEntry() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        NodeIdentity stranger = new IdentityBootstrap(leaderProfile.resolveSibling("stranger")).loadOrCreate().identity();
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-x", "src/x.py");
        byte[] signedByStranger = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_ANNOUNCED, lane.intentId(),
                CollaborationCodec.encodeIntent(lane), stranger);

        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("PROPOSAL_SENDER_MISMATCH")),
                sendFromMember(computers, new ReplicationMessage.Propose(project, signedByStranger)));
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void aNodeThatIsNotAMemberGetsNeitherItsProposalsNorTheLogAndCannotBeAttached() throws Exception {
        Computers computers = new Computers(Set.of("sl1-someone-else"));
        assertThrows(IllegalArgumentException.class, () -> computers.leaderSide.attach(computers.leaderSession));
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");
        byte[] own = proposalAtLeaderHead(PredictionEventType.WORK_INTENT_ANNOUNCED, lane.intentId(),
                CollaborationCodec.encodeIntent(lane), member);

        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("PROPOSER_NOT_A_MEMBER")),
                sendFromMember(computers, new ReplicationMessage.Propose(project, own)));
        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("PROPOSER_NOT_A_MEMBER")),
                sendFromMember(computers, new ReplicationMessage.Pull(project, 0, 10, 1000)));
        assertEquals(0, PredictionEventStore.recordedEvents(leaderRoot));
    }

    @Test
    void anotherProjectsRequestsAreRefusedAndNoticesFromANonLeaderAreNotActedOn() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        UUID other = UUID.randomUUID();

        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("PROPOSAL_PROJECT_MISMATCH")),
                sendFromMember(computers, new ReplicationMessage.Pull(other, 0, 10, 1000)));
        // A notice goes leader to member only: the leader refuses one, and so does a member for the wrong project.
        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("REPLICATION_REQUEST_UNSUPPORTED")),
                sendFromMember(computers, new ReplicationMessage.Notice(project, 5)));
        byte[] wrongProject = computers.leaderSession.requestApplication(
                ReplicationCodec.encode(new ReplicationMessage.Notice(other, 5))).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("REPLICATION_REQUEST_UNSUPPORTED")),
                ReplicationCodec.decode(wrongProject));
    }

    @Test
    void aMemberWithNoLeaderSessionRefusesAtOnceAndNeverAppendsLocally() throws Exception {
        // The member's coordination server is up and relays, but no Link session was ever attached.
        MemberReplication replication = closing(MemberReplication.open(memberRoot, project));
        replication.serve(server(memberRoot, member));
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        long started = System.nanoTime();
        IOException refusal = assertThrows(IOException.class,
                () -> new WorkIntentService(store(memberRoot), member).announce(lane));

        assertEquals("LEADER_UNREACHABLE", refusal.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5, "must not wait for a timeout");
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
    }

    @Test
    void aMemberProcessWithNoCoordinationServerOrAStaleEndpointRefusesAndNeverAppendsLocally() throws Exception {
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");
        IOException noServer = assertThrows(IOException.class,
                () -> new WorkIntentService(store(memberRoot), member).announce(lane));
        assertEquals("LEADER_UNREACHABLE", noServer.getMessage());

        // A server that crashed left its endpoint behind: nothing listens there any more.
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        Files.writeString(memberRoot.resolve("replication.endpoint"), "127.0.0.1:" + closedPort + "\n");
        IOException stale = assertThrows(IOException.class,
                () -> new WorkIntentService(store(memberRoot), member).announce(lane));
        assertEquals("LEADER_UNREACHABLE", stale.getMessage());

        // An endpoint that is not loopback is never connected to.
        Files.writeString(memberRoot.resolve("replication.endpoint"), "203.0.113.7:48123\n");
        IOException elsewhere = assertThrows(IOException.class,
                () -> new WorkIntentService(store(memberRoot), member).announce(lane));
        assertEquals("LEADER_UNREACHABLE", elsewhere.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
    }

    @Test
    void aLostSessionRefusesTheNextProposalAsUnreachableWithoutWaiting() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        computers.leaderSession.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        await(() -> computers.memberSession.terminalCompletion().toCompletableFuture().isDone(), 10);
        WorkIntent lane = lane(project, UUID.randomUUID(), "agt-b", "src/b.py");

        IOException refusal = assertThrows(IOException.class,
                () -> new WorkIntentService(store(memberRoot), member).announce(lane));

        assertEquals("LEADER_UNREACHABLE", refusal.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(memberRoot));
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
    void aLogThatDiffersFromTheLeadersStopsThePullingAndTheProposalsLoudly() throws Exception {
        Computers computers = new Computers(Set.of(member.nodeId()));
        computers.attach();
        UUID group = UUID.randomUUID();
        assertTrue(new WorkIntentService(store(leaderRoot), leader)
                .announce(lane(project, group, "agt-a", "src/a.py")).acquired());
        await(() -> PredictionEventStore.recordedEvents(memberRoot) == 2, 8);
        // The member's second entry becomes another valid entry the leader never wrote.
        Path scratch = temp.resolve("scratch/coordination");
        Files.createDirectories(scratch.resolve("events"));
        String first = String.format("%020d.sce", 1);
        String second = String.format("%020d.sce", 2);
        Files.copy(memberRoot.resolve("events").resolve(first), scratch.resolve("events").resolve(first));
        assertTrue(new WorkIntentService(new PredictionEventStore(scratch, project), member)
                .announce(lane(project, group, "agt-z", "src/z.py")).acquired());
        Files.copy(scratch.resolve("events").resolve(second), memberRoot.resolve("events").resolve(second),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(new WorkIntentService(store(leaderRoot), leader)
                .announce(lane(project, group, "agt-c", "src/c.py")).acquired());

        await(() -> computers.memberSide.state() == MemberReplication.State.DIVERGED, 8);

        assertEquals("DIVERGED", MemberReplication.recordedState(memberRoot).orElseThrow());
        WorkIntent lane = lane(project, group, "agt-b", "src/b.py");
        IOException refusal = assertThrows(IOException.class,
                () -> new WorkIntentService(store(memberRoot), member).announce(lane));
        assertEquals("LEADER_LOG_DIVERGED", refusal.getMessage());
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
}
