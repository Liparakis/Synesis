package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.overlay.OverlayMembershipSnapshot;

import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.network.LinkNetworkProjection;

class LinkRuntimeOwnerControlPlaneTest {

    @TempDir
    Path temp;

    private static void assertOverlay(Map<String, Object> network, String localNodeId, String peerNodeId) {
        @SuppressWarnings("unchecked") Map<String, Object> overlay = (Map<String, Object>) network.get("overlay");
        assertEquals("CURRENT", overlay.get("status"));
        assertEquals(2, ((Number) overlay.get("memberCount")).intValue());
        @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) overlay.get("members");
        assertTrue(members.stream().anyMatch(member -> peerNodeId.equals(member.get("nodeId"))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> directEdges = (List<Map<String, Object>>) overlay.get("directEdges");
        assertTrue(directEdges.stream().anyMatch(edge -> localNodeId.equals(edge.get("from")) && peerNodeId.equals(edge.get("to")) && "DIRECT".equals(edge.get("status"))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) network.get("routes");
        assertTrue(routes.stream().anyMatch(route -> peerNodeId.equals(route.get("destinationNodeId")) && "DIRECT".equals(route.get("kind"))));
    }

    private static NodeIdentity identity(Path root) throws Exception {
        return new IdentityBootstrap(root.resolve(".synesis/local/profile/link")).loadOrCreate().identity();
    }

    private static Map<String, Object> login(RuntimeFixture fixture) throws Exception {
        HttpResponse<String> response = fixture.client.send(json(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("bootstrapToken", fixture.handler.bootstrapToken()))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        return object(response.body());
    }

    private static Map<String, Object> command(RuntimeFixture fixture, Map<String, Object> session, String name, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = fixture.client.send(commandRequest(fixture, session, name, body), HttpResponse.BodyHandlers.ofString());
        assertEquals(name.equals("invite") || name.equals("join") ? 201 : 200, response.statusCode(), response.body());
        return object(response.body());
    }

    private static HttpRequest commandRequest(RuntimeFixture fixture, Map<String, Object> session, String name, Map<String, Object> body) {
        return json(fixture, "/api/v1/commands/" + name).header(ControlPlaneHttpHandler.SESSION_HEADER, (String) session.get("sessionToken")).header(ControlPlaneHttpHandler.CSRF_HEADER, (String) session.get("csrfToken"))
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))).build();
    }

    private static Map<String, Object> network(RuntimeFixture fixture, Map<String, Object> session) throws Exception {
        HttpResponse<String> response = fixture.client.send(HttpRequest.newBuilder(fixture.baseUri.resolve("/api/v1/network")).header(ControlPlaneHttpHandler.SESSION_HEADER, (String) session.get("sessionToken")).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) object(response.body()).get("network");
        return body;
    }

    private static Map<String, Object> awaitPeer(RuntimeFixture fixture, Map<String, Object> session, String peerNodeId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<String, Object> latest = network(fixture, session);
        while (System.nanoTime() < deadline) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> peers = (List<Map<String, Object>>) latest.get("peers");
            if ("HEALTHY".equals(latest.get("status")) && peers.stream().anyMatch(peer -> peerNodeId.equals(peer.get("nodeId")))) {
                return latest;
            }
            Thread.sleep(100);
            latest = network(fixture, session);
        }
        return latest;
    }

    private static HttpRequest.Builder json(RuntimeFixture fixture, String path) {
        return HttpRequest.newBuilder(fixture.baseUri.resolve(path)).header("Content-Type", "application/json; charset=utf-8");
    }

    private static Map<String, Object> object(String json) {
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) ProviderJson.parse(json);
        return result;
    }

    @Test
    @Timeout(120)
    void retainedHttpSessionsProjectVerifiedMembershipAndDirectAdjacency() throws Exception {
        UUID projectId = UUID.randomUUID();
        Path hostRoot = temp.resolve("host");
        Path joinRoot = temp.resolve("join");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        NodeIdentity hostIdentity = identity(hostRoot);
        NodeIdentity joinIdentity = identity(joinRoot);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(projectId,
                1,
                now.minusSeconds(1),
                now.plusSeconds(300),
                List.of(new OverlayMembershipSnapshot.Member(hostIdentity.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE, hostIdentity.publicKeyEncoded()),
                        new OverlayMembershipSnapshot.Member(joinIdentity.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE, joinIdentity.publicKeyEncoded())),
                hostIdentity);

        try (RuntimeFixture host = new RuntimeFixture(hostRoot, projectId, createdAt, hostIdentity, membership);
                RuntimeFixture join = new RuntimeFixture(joinRoot, projectId, createdAt, joinIdentity, membership)) {
            Map<String, Object> hostSession = login(host);
            Map<String, Object> joinSession = login(join);
            Map<String, Object> invitation = command(host, hostSession, "invite", Map.of("expectedPeer", joinIdentity.nodeId()));
            assertTrue(((String) invitation.get("inviteUri")).startsWith("synesis://join/SLB3-"));
            assertEquals("WAITING_FOR_CONNECTION", invitation.get("state"));
            Map<String, Object> connection = command(join, joinSession, "join", Map.of("inviteUri", invitation.get("inviteUri")));
            assertEquals("CONNECTED", connection.get("state"));
            assertTrue(!connection.containsKey("answerUri"));

            Map<String, Object> hostNetwork = awaitPeer(host, hostSession, joinIdentity.nodeId());
            Map<String, Object> joinNetwork = awaitPeer(join, joinSession, hostIdentity.nodeId());
            assertEquals("HEALTHY", hostNetwork.get("status"));
            assertEquals("HEALTHY", joinNetwork.get("status"));
            assertEquals(1, ((List<?>) hostNetwork.get("peers")).size());
            assertEquals(1, ((List<?>) joinNetwork.get("peers")).size());
            assertOverlay(hostNetwork, hostIdentity.nodeId(), joinIdentity.nodeId());
            assertOverlay(joinNetwork, joinIdentity.nodeId(), hostIdentity.nodeId());
        }
    }

    @Test
    void rejectsMembershipBoundToAnotherProjectBeforeSessionAdmission() throws Exception {
        Path root = temp.resolve("mismatch");
        NodeIdentity local = identity(root);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(UUID.randomUUID(),
                1,
                now.minusSeconds(1),
                now.plusSeconds(300),
                List.of(new OverlayMembershipSnapshot.Member(local.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE, local.publicKeyEncoded())),
                local);

        assertThrows(IllegalArgumentException.class, () -> new LinkRuntimeOwner(new Onboarding(root.resolve(".synesis/local/profile/link"), ignored -> {
        }), local, membership, ignored -> {
        }, new ProjectIdentity(UUID.randomUUID(), now)));
    }

    private static final class RuntimeFixture implements AutoCloseable {

        private final HttpClient client = HttpClient.newHttpClient();
        private final ControlPlaneHttpHandler handler;
        private final CoordinationHttpServer server;
        private final LinkRuntimeOwner owner;
        private final URI baseUri;

        private RuntimeFixture(Path root, UUID projectId, Instant createdAt, NodeIdentity identity, OverlayMembershipSnapshot membership) throws Exception {
            Path synesis = root.resolve(".synesis");
            Path profile = synesis.resolve("local/profile");
            Files.createDirectories(profile);
            ProjectLocation location = new ProjectLocation(root, synesis, root.resolve("project.json"), profile, projectId, createdAt);
            CoordinationService coordination = new CoordinationService(new PredictionEventStore(synesis.resolve("coordination"), projectId), identity);
            ControlPlaneEventHub eventHub = new ControlPlaneEventHub();
            Onboarding onboarding = new Onboarding(profile.resolve("link"), eventHub::publish);
            owner = new LinkRuntimeOwner(onboarding, identity, membership, eventHub::publish, new ProjectIdentity(projectId, createdAt));
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, coordination, new ProviderApplicationService(), new DoctorService(), new LinkNetworkProjection(owner), null);
            handler = new ControlPlaneHttpHandler(readModel, coordination, owner, eventHub, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE, null);
            server = new CoordinationHttpServer(coordination, new InetSocketAddress("127.0.0.1", 0), null, handler);
            server.start();
            baseUri = URI.create("http://127.0.0.1:" + server.address().getPort());
        }

        @Override
        public void close() {
            handler.close();
            server.close();
            owner.close();
        }
    }
}
