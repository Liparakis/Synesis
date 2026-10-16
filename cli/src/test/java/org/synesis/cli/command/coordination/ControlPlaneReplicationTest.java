package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationReplay;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.MemberReplication;
import org.synesis.coordination.replication.ReplicationJournal;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.ControlPlaneEventHub;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler;
import org.synesis.workspace.transport.control.ControlPlaneReadModel;
import org.synesis.workspace.transport.control.LinkRuntimeOwner;
import org.synesis.workspace.transport.control.network.LinkNetworkProjection;

/**
 * The replication of a project's log runs on the Link sessions that the control plane's invite and join establish
 * (ADR-0098 step 2, slice 4): two computers, each with the owner of its control-plane sessions as the launcher wires
 * it, driven through the control plane with the runtime command token as {@code synesis link} drives it.
 */
@Timeout(120)
final class ControlPlaneReplicationTest {

    private final UUID projectId = UUID.randomUUID();
    private final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
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

    private NodeIdentity identity(String name) throws Exception {
        return new IdentityBootstrap(temp.resolve(name + "/.synesis/local/profile/link")).loadOrCreate().identity();
    }

    /** One computer as the launcher composes it: a role, the link owner of its control plane, a loopback server. */
    private final class Computer {
        final Path coordination;
        final NodeIdentity node;
        final URI endpoint;
        final String commandToken;
        final LinkRuntimeOwner owner;
        private final List<AutoCloseable> mine = new ArrayList<>();

        Computer(String name, NodeIdentity node, ReplicationSettings settings) throws Exception {
            Path root = temp.resolve(name);
            Path synesis = root.resolve(".synesis");
            Path profile = synesis.resolve("local/profile");
            this.node = node;
            this.coordination = synesis.resolve("coordination");
            settings.write(coordination);
            MemberReplication member = settings.isMember() ? own(MemberReplication.open(coordination, projectId)) : null;
            LeaderReplication leader = settings.isMember() ? null : own(LeaderReplication.open(coordination, projectId));
            ControlPlaneEventHub eventHub = new ControlPlaneEventHub();
            this.owner = own(CoordinationServerLauncher.linkOwner(new Onboarding(profile.resolve("link"), eventHub::publish), node, eventHub,
                    new ProjectIdentity(projectId, createdAt), settings, member, leader, new ReplicationJournal(coordination), coordination));
            CoordinationService service = new CoordinationService(new PredictionEventStore(coordination, projectId), node);
            ProjectLocation location = new ProjectLocation(root, synesis, synesis.resolve("project.json"), profile, projectId, createdAt);
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, service, new ProviderApplicationService(), new DoctorService(), new LinkNetworkProjection(owner), null);
            ControlPlaneHttpHandler control = own(new ControlPlaneHttpHandler(readModel, service, owner, eventHub, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE, null));
            CoordinationHttpServer server = own(new CoordinationHttpServer(service, new InetSocketAddress("127.0.0.1", 0), null, control));
            server.start();
            if (member != null) {
                member.serve(server);
            }
            this.endpoint = URI.create("http://127.0.0.1:" + server.address().getPort());
            this.commandToken = control.runtimeCommandToken();
        }

        private <T extends AutoCloseable> T own(T resource) {
            mine.add(resource);
            return closing(resource);
        }

        /** Stops this computer's server and link owner as a restart of its process would, leaving its folders as they are. */
        void stop() throws Exception {
            for (int index = mine.size() - 1; index >= 0; index--) {
                mine.get(index).close();
            }
        }

        Map<String, Object> post(String command, Map<String, Object> body) throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint.resolve("/api/v1/commands/" + command))
                    .header("Content-Type", "application/json; charset=utf-8").header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken)
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) ProviderJson.parse(response.body());
            return parsed;
        }

        boolean hasPeer() {
            return !peers().isEmpty();
        }

        List<?> peers() {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint.resolve("/api/v1/network"))
                        .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                @SuppressWarnings("unchecked") Map<String, Object> network = (Map<String, Object>) ((Map<String, Object>) ProviderJson.parse(response.body())).get("network");
                return (List<?>) network.get("peers");
            } catch (IOException | InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
        }

        PredictionEventStore store() throws Exception {
            return new PredictionEventStore(coordination, projectId);
        }
    }

    private WorkIntent lane(String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, projectId, participant, "codex", UUID.randomUUID(), "Build a board", "CRUD and persistence", "base-commit",
                List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    /** The leader invites the member, the member joins, and the member's side reports a pull from the leader. */
    private void connect(Computer leader, Computer member) throws Exception {
        Map<String, Object> invitation = leader.post("invite", Map.of("expectedPeer", member.node.nodeId()));
        member.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
    }

    @Test
    void aMembersEntryReachesTheLeaderOverTheSessionTheControlPlaneEstablished() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        connect(leader, member);

        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());

        assertEquals(2, leader.store().events().size());
        assertTrue(leader.store().events().stream().allMatch(event -> event.actorNodeId().equals(memberNode.nodeId())));
        // The leader's own entry reaches the member by the notice the attached session carries, well before the 15 s pull.
        assertTrue(new WorkIntentService(leader.store(), leaderNode).announce(lane("agt-a", "src/a.py")).acquired());
        await(() -> PredictionEventStore.recordedEvents(member.coordination) == 4);
    }

    /**
     * The shape of the first real run: the member hosts the invitation and the leader joins it, so the leader (which
     * can only dial out) is the one that dials again. A member's proposal after the session dropped and came back is
     * accepted, with no new invitation, and the journal says why the session ended.
     */
    @Test
    void aDroppedSessionIsDialedAgainByTheJoinerAndTheMembersNextProposalIsAccepted() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        Map<String, Object> invitation = member.post("invite", Map.of("expectedPeer", leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());
        List<PeerSession> firstSessions = List.copyOf(leader.owner.get().sessions());

        firstSessions.forEach(session -> session.closeGracefully(SessionCloseReason.LOCAL_REQUEST));

        await(() -> leader.owner.get().sessions().stream().anyMatch(session -> !firstSessions.contains(session) && session.isUsable())
                && member.owner.get().sessions().stream().anyMatch(PeerSession::isUsable));
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        assertEquals(4, leader.store().events().size());
        assertTrue(ReplicationJournal.lastDisconnect(member.coordination).orElseThrow().startsWith("REMOTE_REQUEST "));
        assertTrue(ReplicationJournal.lastDisconnect(leader.coordination).orElseThrow().startsWith("LOCAL_REQUEST "));
        assertEquals(2, ReplicationJournal.read(member.coordination).stream().filter(line -> line.contains(" CONNECTED ")).count());
    }

    private boolean hasUsableSessionOtherThan(Computer computer, List<PeerSession> earlier) {
        return computer.owner.get().sessions().stream().anyMatch(session -> !earlier.contains(session) && session.isUsable());
    }

    private static boolean journaled(Computer computer, String event) {
        return ReplicationJournal.read(computer.coordination).stream().anyMatch(line -> line.contains(" " + event));
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(5_000);
    }

    private LinkRuntimeOwner strangerOwner(String name, NodeIdentity node, java.util.function.Predicate<String> returning, List<String> journal) {
        return closing(new LinkRuntimeOwner(new Onboarding(temp.resolve(name + "/.synesis/local/profile/link"), ignored -> {
        }), node, ignored -> {
        }, new ProjectIdentity(projectId, createdAt), (remote, payload) -> CompletableFuture.completedFuture(new byte[0]), session -> {
        }, returning, journal::add));
    }

    /**
     * The first real run's restart test failed here: the leader dialed the member's invitation, its coordination server was
     * restarted, and nothing reconnected until a person made a new invitation. Now the leader writes down whom it dialed and
     * dials again by itself.
     */
    @Test
    void aRestartedJoinerDialsItsHostAgainWithoutAnInvitationAndTheMembersNextProposalIsAccepted() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        Map<String, Object> invitation = member.post("invite", Map.of("expectedPeer", leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());
        List<PeerSession> before = List.copyOf(member.owner.get().sessions());

        leader.stop();
        Computer restarted = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));

        await(() -> hasUsableSessionOtherThan(member, before) && restarted.hasPeer());
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        assertEquals(4, restarted.store().events().size());
        assertTrue(journaled(restarted, "DIAL_RESUMED"));
    }

    /** The other half: the member hosted the invitation, its server restarts, and the leader's redial finds the same port. */
    @Test
    void aRestartedHostListensOnItsOldPortAndAdmitsTheNodeItAdmittedBefore() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        Map<String, Object> invitation = member.post("invite", Map.of("expectedPeer", leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
        assertEquals(Set.of(leaderNode.nodeId()), ReplicationPeers.read(member.coordination).hosted().values().iterator().next());
        List<PeerSession> before = List.copyOf(leader.owner.get().sessions());

        member.stop();
        Computer restarted = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));

        await(() -> hasUsableSessionOtherThan(leader, before) && restarted.hasPeer());
        assertTrue(new WorkIntentService(restarted.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        assertEquals(2, leader.store().events().size());
        assertTrue(journaled(restarted, "HOST_RESUMED"));
    }

    /** A node the role names, which the host never admitted, gets nothing from a restarted host: not by link, not by redial. */
    @Test
    void aRestartedHostRefusesANodeItNeverAdmittedEvenWhenTheRoleNamesIt() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity aNode = identity("a");
        NodeIdentity bNode = identity("b");
        Set<String> both = Set.of(aNode.nodeId(), bNode.nodeId());
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(both));
        Computer a = new Computer("a", aNode, ReplicationSettings.member(leaderNode.nodeId()));
        Map<String, Object> invitation = leader.post("invite", Map.of());
        a.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> MemberReplication.recordedState(a.coordination).filter("CONNECTED"::equals).isPresent());
        List<String> bJournal = new CopyOnWriteArrayList<>();
        LinkRuntimeOwner b = strangerOwner("b", bNode, node -> true, bJournal);
        List<PeerSession> before = List.copyOf(a.owner.get().sessions());

        leader.stop();
        Computer restarted = new Computer("leader", leaderNode, ReplicationSettings.leader(both));
        await(() -> hasUsableSessionOtherThan(a, before) && restarted.hasPeer());
        b.resumeDialing((String) invitation.get("inviteUri"));
        // A refused attempt takes longer to give up on Windows than on Linux; wait for it rather than for a fixed time.
        await(() -> bJournal.stream().anyMatch(line -> line.startsWith("REDIAL_FAILED")));

        assertTrue(b.get().sessions().isEmpty(), bJournal.toString());
        assertTrue(restarted.owner.get().sessions().stream().noneMatch(session -> session.remoteNodeId().equals(bNode.nodeId())));
    }

    @Test
    void aMemberRemovedBeforeTheRestartIsNeitherAdmittedByTheRestartedHostNorRedialed() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity aNode = identity("a");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(aNode.nodeId(), memberNode.nodeId())));
        Computer a = new Computer("a", aNode, ReplicationSettings.member(leaderNode.nodeId()));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        a.post("join", Map.of("inviteUri", leader.post("invite", Map.of()).get("inviteUri")));
        leader.post("join", Map.of("inviteUri", member.post("invite", Map.of()).get("inviteUri")));
        await(() -> a.hasPeer() && member.hasPeer() && leader.peers().size() == 2);
        leader.stop();
        // The leader's list loses both while it is down; neither record is used.
        ReplicationSettings.leader(Set.of()).write(leader.coordination);

        Computer restarted = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of()));
        settle();

        assertTrue(restarted.owner.get().sessions().isEmpty());
        assertTrue(a.owner.get().sessions().stream().noneMatch(PeerSession::isUsable));
        assertTrue(member.owner.get().sessions().stream().noneMatch(PeerSession::isUsable));
        assertTrue(journaled(restarted, "HOST_RESUME_SKIPPED"));
        assertTrue(journaled(restarted, "DIAL_RESUME_SKIPPED"));
    }

    @Test
    void aPeersFileThatCannotBeReadResumesNothingAndTheServerStarts() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", member.post("invite", Map.of("expectedPeer", leaderNode.nodeId())).get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
        leader.stop();
        Files.writeString(leader.coordination.resolve(ReplicationPeers.FILE), "dial=not a record\n");

        Computer restarted = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        settle();

        assertTrue(restarted.owner.get().sessions().isEmpty());
        assertTrue(member.owner.get().sessions().stream().noneMatch(PeerSession::isUsable));
        assertTrue(journaled(restarted, "PEERS_IGNORED"));
    }

    private Computer hostedAndConnected(NodeIdentity leaderNode, NodeIdentity memberNode) throws Exception {
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", member.post("invite", Map.of("expectedPeer", leaderNode.nodeId())).get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
        return member;
    }

    /** The handshake that admitted the leader is on disk before the host restarts, and the restarted host adds to it instead of starting over. */
    @Test
    void aHostKeepsItsAcceptedHandshakesAcrossARestartAndAddsTheNextOne() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer member = hostedAndConnected(leaderNode, memberNode);
        Set<String> before = ReplicationReplay.read(member.coordination, 8_192).orElseThrow();
        assertEquals(1, before.size());

        member.stop();
        Computer restarted = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));

        await(() -> journaled(restarted, "HOST_RESUMED") && restarted.hasPeer());
        Set<String> after = ReplicationReplay.read(restarted.coordination, 8_192).orElseThrow();
        assertEquals(2, after.size());
        assertTrue(after.containsAll(before));
    }

    @Test
    void aReplayFileThatCannotBeReadMeansTheHostDoesNotResumeAndForgetsItsHostingRecord() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer member = hostedAndConnected(leaderNode, memberNode);
        member.stop();
        Files.writeString(member.coordination.resolve(ReplicationReplay.FILE), "not a fingerprint\n");

        Computer restarted = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        settle();

        assertTrue(journaled(restarted, "REPLAY_GUARD_UNREADABLE"), ReplicationJournal.read(restarted.coordination).toString());
        assertTrue(!journaled(restarted, "HOST_RESUMED"));
        assertTrue(restarted.owner.get().sessions().isEmpty());
        assertTrue(ReplicationPeers.read(restarted.coordination).hosted().isEmpty());
    }

    @Test
    void aMissingReplayFileWhileAHostingRecordExistsMeansTheHostDoesNotResume() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer member = hostedAndConnected(leaderNode, memberNode);
        member.stop();
        Files.delete(member.coordination.resolve(ReplicationReplay.FILE));

        Computer restarted = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        settle();

        assertTrue(journaled(restarted, "REPLAY_GUARD_UNREADABLE"));
        assertTrue(!journaled(restarted, "HOST_RESUMED"));
        assertTrue(restarted.owner.get().sessions().isEmpty());
    }

    @Test
    void aHostWhoseOldPortIsTakenSaysSoAndStillStarts() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", member.post("invite", Map.of("expectedPeer", leaderNode.nodeId())).get("inviteUri")));
        await(() -> MemberReplication.recordedState(member.coordination).filter("CONNECTED"::equals).isPresent());
        int port = ReplicationPeers.read(member.coordination).hosted().keySet().iterator().next();
        member.stop();

        try (DatagramSocket taken = new DatagramSocket(port)) {
            Computer restarted = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
            assertTrue(taken.isBound());
            assertTrue(journaled(restarted, "HOST_RESUME_FAILED"), ReplicationJournal.read(restarted.coordination).toString());
            assertTrue(restarted.owner.get().sessions().isEmpty());
        }
    }

    @Test
    void theRuntimeCommandTokenIsTheOnlyCredentialBesidesASessionTheControlPlaneAccepts() throws Exception {
        NodeIdentity node = identity("leader");
        Computer leader = new Computer("leader", node, ReplicationSettings.leader(Set.of()));
        for (String header : new String[] {null, "wrong"}) {
            HttpRequest.Builder request = HttpRequest.newBuilder(leader.endpoint.resolve("/api/v1/network"));
            if (header != null) {
                request.header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, header);
            }
            assertEquals(401, client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(List.of(), leader.peers());
    }

    @Test
    void aMemberAddedOrRemovedInTheSettingsIsServedOrRefusedWithoutRestartingTheLeader() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of()));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        Map<String, Object> invitation = leader.post("invite", Map.of());
        member.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> member.hasPeer() && leader.hasPeer());

        IOException refused = assertThrows(IOException.class, () -> new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")));
        assertEquals("PROPOSER_NOT_A_MEMBER", refused.getMessage());

        ReplicationSettings.leader(Set.of(memberNode.nodeId())).write(leader.coordination);
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());
        assertEquals(2, leader.store().events().size());

        ReplicationSettings.leader(Set.of()).write(leader.coordination);
        IOException removed = assertThrows(IOException.class, () -> new WorkIntentService(member.store(), memberNode).announce(lane("agt-c", "src/c.py")));
        assertEquals("PROPOSER_NOT_A_MEMBER", removed.getMessage());
        assertEquals(2, leader.store().events().size());
    }
}
