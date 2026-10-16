package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

        Computer(String name, NodeIdentity node, ReplicationSettings settings) throws Exception {
            Path root = temp.resolve(name);
            Path synesis = root.resolve(".synesis");
            Path profile = synesis.resolve("local/profile");
            this.node = node;
            this.coordination = synesis.resolve("coordination");
            settings.write(coordination);
            MemberReplication member = settings.isMember() ? closing(MemberReplication.open(coordination, projectId)) : null;
            LeaderReplication leader = settings.isMember() ? null : closing(LeaderReplication.open(coordination, projectId));
            ControlPlaneEventHub eventHub = new ControlPlaneEventHub();
            this.owner = closing(CoordinationServerLauncher.linkOwner(new Onboarding(profile.resolve("link"), eventHub::publish), node, eventHub,
                    new ProjectIdentity(projectId, createdAt), settings, member, leader, new ReplicationJournal(coordination)));
            CoordinationService service = new CoordinationService(new PredictionEventStore(coordination, projectId), node);
            ProjectLocation location = new ProjectLocation(root, synesis, synesis.resolve("project.json"), profile, projectId, createdAt);
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, service, new ProviderApplicationService(), new DoctorService(), new LinkNetworkProjection(owner), null);
            ControlPlaneHttpHandler control = closing(new ControlPlaneHttpHandler(readModel, service, owner, eventHub, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE, null));
            CoordinationHttpServer server = closing(new CoordinationHttpServer(service, new InetSocketAddress("127.0.0.1", 0), null, control));
            server.start();
            if (member != null) {
                member.serve(server);
            }
            this.endpoint = URI.create("http://127.0.0.1:" + server.address().getPort());
            this.commandToken = control.runtimeCommandToken();
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
