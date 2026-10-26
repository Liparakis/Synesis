package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.SharedSettingsMigration;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationReplay;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.ReplicationJournal;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
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

        /**
         * @param settings the step-5 file to start with: a leader's names the members its log is given at start (the
         *                 launcher's migration), a member's names the leader it trusts until the owner's entries
         *                 arrive; {@code null} for a restart, which keeps the folder as it is
         */
        Computer(String name, NodeIdentity node, ReplicationSettings settings) throws Exception {
            Path root = temp.resolve(name);
            Path synesis = root.resolve(".synesis");
            Path profile = synesis.resolve("local/profile");
            this.node = node;
            this.coordination = synesis.resolve("coordination");
            if (settings != null) {
                settings.write(coordination);
            }
            // As the launcher starts: a step-5 file moves into the log, and every computer plays the same shared side.
            SharedSettingsMigration.migrate(coordination, new PredictionEventStore(coordination, projectId), node);
            LeaderReplication leader = LeaderReplication.shared(coordination, projectId, node.nodeId(), () -> {
            });
            // The side closes the shared side it holds.
            ReplicationSide side = own(new ReplicationSide(coordination, projectId, root, node.nodeId(), leader, null));
            ControlPlaneEventHub eventHub = new ControlPlaneEventHub();
            this.owner = own(CoordinationServerLauncher.linkOwner(new Onboarding(profile.resolve("link"), eventHub::publish), node, eventHub,
                    new ProjectIdentity(projectId, createdAt), side, new ReplicationJournal(coordination), coordination, null));
            CoordinationService service = new CoordinationService(new PredictionEventStore(coordination, projectId), node);
            ProjectLocation location = new ProjectLocation(root, synesis, synesis.resolve("project.json"), profile, projectId, createdAt);
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, service, new ProviderApplicationService(), new DoctorService(), new LinkNetworkProjection(owner), null);
            ControlPlaneHttpHandler control = own(new ControlPlaneHttpHandler(readModel, service, owner, eventHub, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE, null));
            CoordinationHttpServer server = own(new CoordinationHttpServer(service, new InetSocketAddress("127.0.0.1", 0), null, control));
            server.start();
            leader.serve(server);
            side.serveCode(server);
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

        /** How many entries have taken effect here, its own and those of copies; -1 while the log cannot be read. */
        int applied() {
            try {
                return store().events().size();
            } catch (Exception unreadable) {
                return -1;
            }
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

    /** The leader invites the member, the member joins, and the member's side reports its session with the leader. */
    private void connect(Computer leader, Computer member) throws Exception {
        Map<String, Object> invitation = leader.post("invite", Map.of("expectedPeer", member.node.nodeId()));
        member.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> connectedOn(member, leader.node));
    }

    /** The entries of {@code author} that took effect in {@code holder}'s log, its own or copied; -1 while the log cannot be read. */
    private static long entriesOf(Computer holder, NodeIdentity author) {
        try {
            return holder.store().events().stream().filter(event -> event.actorNodeId().equals(author.nodeId())).count();
        } catch (Exception unreadable) {
            return -1;
        }
    }

    /** The owner's computer removes a member by an entry of its log, as {@code synesis project remove-member} does. */
    private void remove(Computer owner, NodeIdentity member) throws Exception {
        new MembershipService(owner.store(), owner.node).remove(member.nodeId());
    }

    /** The owner's computer lets a member in by entries of its log, as {@code synesis project add-member} does. */
    private void add(Computer owner, NodeIdentity member) throws Exception {
        MembershipService membership = new MembershipService(owner.store(), owner.node);
        membership.requestJoin(member.nodeId());
        membership.answer(member.nodeId(), true);
    }

    @Test
    void aMembersEntryReachesTheLeaderOverTheSessionTheControlPlaneEstablished() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        connect(leader, member);

        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());

        await(() -> entriesOf(leader, memberNode) == 2);
        // The leader's own entries (its membership entries from the migration at start, and now a lane) reach the member
        // by the notice the attached session carries, well before the 15 s pull.
        assertTrue(new WorkIntentService(leader.store(), leaderNode).announce(lane("agt-a", "src/a.py")).acquired());
        await(() -> entriesOf(member, leaderNode) == entriesOf(leader, leaderNode));
        assertEquals(leader.store().events().size(), member.store().events().size(), "each holds the other's entries and its own");
        assertEquals(leaderNode.nodeId(), member.store().membershipProjection().owner().orElseThrow(), "the member learned the owner from the log");
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
        await(() -> connectedOn(member, leaderNode));
        await(() -> connectedOn(leader, memberNode));
        Instant firstConnected = LeaderReplication.recordedMembers(leader.coordination).orElseThrow().get(memberNode.nodeId()).since();
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());
        List<PeerSession> firstSessions = List.copyOf(leader.owner.get().sessions());

        firstSessions.forEach(session -> session.closeGracefully(SessionCloseReason.LOCAL_REQUEST));

        await(() -> leader.owner.get().sessions().stream().anyMatch(session -> !firstSessions.contains(session) && session.isUsable())
                && member.owner.get().sessions().stream().anyMatch(PeerSession::isUsable));
        // The leader says the member is connected again, from the redialed session's start.
        await(() -> connectedOn(leader, memberNode)
                && LeaderReplication.recordedMembers(leader.coordination).orElseThrow().get(memberNode.nodeId()).since().isAfter(firstConnected));
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        await(() -> entriesOf(leader, memberNode) == 4);
        assertTrue(ReplicationJournal.lastDisconnect(member.coordination).orElseThrow().startsWith("REMOTE_REQUEST "));
        assertTrue(ReplicationJournal.lastDisconnect(leader.coordination).orElseThrow().startsWith("LOCAL_REQUEST "));
        assertEquals(2, ReplicationJournal.read(member.coordination).stream().filter(line -> line.contains(" CONNECTED ")).count());
    }

    /** Whether {@code holder} records a live session with {@code other}. */
    private static boolean connectedOn(Computer holder, NodeIdentity other) {
        return LeaderReplication.recordedMembers(holder.coordination)
                .map(members -> members.get(other.nodeId())).filter(found -> found.state() == LeaderReplication.MemberConnection.State.CONNECTED).isPresent();
    }

    private boolean hasUsableSessionOtherThan(Computer computer, List<PeerSession> earlier) {
        return computer.owner.get().sessions().stream().anyMatch(session -> !earlier.contains(session) && session.isUsable());
    }

    private static boolean hostRecorded(Computer computer) {
        try {
            return !ReplicationPeers.read(computer.coordination).hosted().isEmpty();
        } catch (java.io.IOException notWrittenYet) {
            return false;
        }
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
        await(() -> connectedOn(member, leaderNode));
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());
        List<PeerSession> before = List.copyOf(member.owner.get().sessions());

        leader.stop();
        Computer restarted = new Computer("leader", leaderNode, null);

        await(() -> hasUsableSessionOtherThan(member, before) && restarted.hasPeer());
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        await(() -> entriesOf(restarted, memberNode) == 4);
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
        await(() -> connectedOn(member, leaderNode));
        // The host writes down whom it admitted just after the session is up, so the record can trail the CONNECTED state.
        await(() -> hostRecorded(member));
        assertEquals(Set.of(leaderNode.nodeId()), ReplicationPeers.read(member.coordination).hosted().values().iterator().next());
        List<PeerSession> before = List.copyOf(leader.owner.get().sessions());

        member.stop();
        Computer restarted = new Computer("member", memberNode, null);

        await(() -> hasUsableSessionOtherThan(leader, before) && restarted.hasPeer());
        assertTrue(new WorkIntentService(restarted.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        await(() -> entriesOf(leader, memberNode) == 2);
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
        await(() -> connectedOn(a, leaderNode));
        List<String> bJournal = new CopyOnWriteArrayList<>();
        LinkRuntimeOwner b = strangerOwner("b", bNode, node -> true, bJournal);
        List<PeerSession> before = List.copyOf(a.owner.get().sessions());

        leader.stop();
        Computer restarted = new Computer("leader", leaderNode, null);
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
        // Both are removed while it is down, by entries of its log (ADR-0098 step 6); neither record is used.
        MembershipService owner = new MembershipService(new PredictionEventStore(leader.coordination, projectId), leaderNode);
        owner.remove(aNode.nodeId());
        owner.remove(memberNode.nodeId());

        Computer restarted = new Computer("leader", leaderNode, null);
        // The stopped leader's sessions take longer to end on Windows than on Linux; wait for that, not a fixed time.
        await(() -> a.owner.get().sessions().stream().noneMatch(PeerSession::isUsable) && member.owner.get().sessions().stream().noneMatch(PeerSession::isUsable)
                && journaled(restarted, "HOST_RESUME_SKIPPED") && journaled(restarted, "DIAL_RESUME_SKIPPED"));
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
        await(() -> connectedOn(member, leaderNode));
        leader.stop();
        Files.writeString(leader.coordination.resolve(ReplicationPeers.FILE), "dial=not a record\n");

        Computer restarted = new Computer("leader", leaderNode, null);
        // The stopped leader's session ends when its end is noticed, which a loaded machine delays; wait for that, then settle for what must not happen.
        await(() -> member.owner.get().sessions().stream().noneMatch(PeerSession::isUsable));
        settle();

        assertTrue(restarted.owner.get().sessions().isEmpty());
        assertTrue(member.owner.get().sessions().stream().noneMatch(PeerSession::isUsable));
        assertTrue(journaled(restarted, "PEERS_IGNORED"));
    }

    private Computer hostedAndConnected(NodeIdentity leaderNode, NodeIdentity memberNode) throws Exception {
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        leader.post("join", Map.of("inviteUri", member.post("invite", Map.of("expectedPeer", leaderNode.nodeId())).get("inviteUri")));
        await(() -> connectedOn(member, leaderNode));
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
        Computer restarted = new Computer("member", memberNode, null);

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

        Computer restarted = new Computer("member", memberNode, null);
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

        Computer restarted = new Computer("member", memberNode, null);
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
        await(() -> connectedOn(member, leaderNode));
        int port = ReplicationPeers.read(member.coordination).hosted().keySet().iterator().next();
        member.stop();

        try (DatagramSocket taken = new DatagramSocket(port)) {
            Computer restarted = new Computer("member", memberNode, null);
            assertTrue(taken.isBound());
            assertTrue(journaled(restarted, "HOST_RESUME_FAILED"), ReplicationJournal.read(restarted.coordination).toString());
            assertTrue(restarted.owner.get().sessions().isEmpty());
        }
    }

    /**
     * Code over Link end to end (ADR-0098 step 3): a process of the member asks its own server on the loopback
     * {@code /code} route, and the server brings the leader's branch over the Link session the control plane made,
     * with no Git remote anywhere.
     */
    @Test
    void aMembersProcessBringsTheLeadersBranchOverTheSessionWithNoRemote() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Path leaderRoot = temp.resolve("leader");
        Path memberRoot = temp.resolve("member");
        GitProcessRunner.run(leaderRoot, "init", "-q");
        Files.writeString(leaderRoot.resolve("board.txt"), "first\n");
        GitProcessRunner.run(leaderRoot, "add", "board.txt");
        GitProcessRunner.run(leaderRoot, "commit", "-q", "-m", "first");
        String branch = GitProcessRunner.run(leaderRoot, "symbolic-ref", "--short", "HEAD");
        GitProcessRunner.run(memberRoot, "init", "-q");
        GitProcessRunner.run(memberRoot, "fetch", "-q", leaderRoot.toString(), branch);
        GitProcessRunner.run(memberRoot, "checkout", "-q", "-B", branch, "FETCH_HEAD");
        Files.writeString(leaderRoot.resolve("board.txt"), "second\n");
        GitProcessRunner.run(leaderRoot, "commit", "-q", "-am", "second");
        String leaderHead = GitProcessRunner.run(leaderRoot, "rev-parse", "HEAD");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of(memberNode.nodeId())));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        connect(leader, member);
        // An agent's process is not the server's: it has no code side of its own and asks over loopback HTTP.
        CodeExchange.register(member.coordination, null);

        assertTrue(CodeExchange.followLeaderIfMember(member.coordination, projectId, memberRoot, memberNode.nodeId()));

        assertEquals(leaderHead, GitProcessRunner.run(memberRoot, "rev-parse", "HEAD"), "the member's branch followed the leader's");
        assertEquals("second", Files.readString(memberRoot.resolve("board.txt")).strip());
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
    void aMemberAddedOrRemovedByEntriesOfTheOwnersLogIsServedOrRefusedWithoutRestartingTheOwner() throws Exception {
        NodeIdentity leaderNode = identity("leader");
        NodeIdentity memberNode = identity("member");
        Computer leader = new Computer("leader", leaderNode, ReplicationSettings.leader(Set.of()));
        Computer member = new Computer("member", memberNode, ReplicationSettings.member(leaderNode.nodeId()));
        Map<String, Object> invitation = leader.post("invite", Map.of());
        member.post("join", Map.of("inviteUri", invitation.get("inviteUri")));
        await(() -> member.hasPeer() && leader.hasPeer());

        // The member always writes its own log; the owner takes copies only from a node its log names a member.
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-b", "src/b.py")).acquired());
        settle();
        assertEquals(0, entriesOf(leader, memberNode), "a node the log does not name gets nothing from the owner");

        add(leader, memberNode);
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-c", "src/c.py")).acquired());
        await(() -> entriesOf(leader, memberNode) == 4);

        remove(leader, memberNode);
        assertTrue(new WorkIntentService(member.store(), memberNode).announce(lane("agt-d", "src/d.py")).acquired());
        settle();
        assertEquals(4, entriesOf(leader, memberNode), "a removed node's later entries do not reach the owner");
    }
}
