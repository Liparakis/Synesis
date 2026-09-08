package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

class ControlPlaneHttpHandlerTest {

    @TempDir
    Path temp;

    @Test
    void healthIsPublicButSnapshotRequiresOneTimeSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HttpResponse<String> health = fixture.client.send(request("/api/v1/health").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"loopbackOnly\":true"));

            HttpResponse<String> unauthenticated = fixture.client.send(request("/api/v1/snapshot").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthenticated.statusCode());

            HttpResponse<String> invalidSession = fixture.client.send(jsonRequest("/api/v1/session")
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                                    Map.of("bootstrapToken", "wrong")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, invalidSession.statusCode());

            HttpResponse<String> session = fixture.client.send(jsonRequest("/api/v1/session")
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                                    Map.of("bootstrapToken", fixture.handler.bootstrapToken())))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, session.statusCode());
            Map<String, Object> credentials = object(session.body());
            String sessionToken = (String) credentials.get("sessionToken");
            String csrfToken = (String) credentials.get("csrfToken");

            HttpResponse<String> reusedBootstrap = fixture.client.send(jsonRequest("/api/v1/session")
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                                    Map.of("bootstrapToken", fixture.handler.bootstrapToken())))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, reusedBootstrap.statusCode());

            HttpResponse<String> snapshot = fixture.client.send(request("/api/v1/snapshot")
                            .header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, snapshot.statusCode());
            assertTrue(snapshot.body().contains("\"project\""));
            assertTrue(snapshot.body().contains("\"diagnostics\""));
            assertFalse(snapshot.body().contains(fixture.handler.bootstrapToken()));

            HttpResponse<String> mutationWithoutCsrf = fixture.client.send(jsonRequest("/api/v1/commands/answer")
                            .header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken)
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                                    Map.of("operationId", "missing", "answerUri", "missing")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, mutationWithoutCsrf.statusCode());

            HttpResponse<String> mutationWithCsrf = fixture.client.send(jsonRequest("/api/v1/commands/answer")
                            .header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken)
                            .header(ControlPlaneHttpHandler.CSRF_HEADER, csrfToken)
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                                    Map.of("operationId", "missing", "answerUri", "missing")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, mutationWithCsrf.statusCode());
        }
    }

    @Test
    void rejectsUntrustedBrowserOriginBeforeAuthentication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HttpResponse<String> response = fixture.client.send(request("/api/v1/health")
                            .header("Origin", "http://evil.example:" + fixture.server.address().getPort())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.body().contains("ORIGIN_NOT_ALLOWED"));

            HttpResponse<String> allowedOrigin = fixture.client.send(request("/api/v1/health")
                            .header("Origin", "http://localhost:" + fixture.server.address().getPort())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowedOrigin.statusCode());
            assertEquals("http://localhost:" + fixture.server.address().getPort(),
                    allowedOrigin.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        }
    }

    @Test
    void rejectsBadHostMalformedJsonAndOversizedBodies() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (Socket socket = new Socket("127.0.0.1", fixture.server.address().getPort())) {
                socket.getOutputStream().write(("GET /api/v1/health HTTP/1.1\r\n"
                        + "Host: evil.example:" + fixture.server.address().getPort() + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(response.contains(" 403 "));
                assertTrue(response.contains("HOST_NOT_ALLOWED"));
            }

            HttpResponse<String> malformed = fixture.client.send(jsonRequest("/api/v1/session")
                            .POST(HttpRequest.BodyPublishers.ofString("{" )).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());

            String oversized = "x".repeat(129 * 1024);
            HttpResponse<String> tooLarge = fixture.client.send(jsonRequest("/api/v1/session")
                            .POST(HttpRequest.BodyPublishers.ofString(oversized)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, tooLarge.statusCode());
        }
    }

    @Test
    void liveStreamSendsSnapshotThenSafeCoordinationDelta() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> credentials = login(fixture);
            HttpRequest streamRequest = request("/api/v1/events")
                    .header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken"))
                    .GET().build();
            HttpResponse<java.io.InputStream> stream = fixture.client.send(streamRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body()))) {
                assertEquals("event: snapshot", reader.readLine());
                assertTrue(reader.readLine().startsWith("id: "));
                assertTrue(reader.readLine().contains("\"snapshot\""));
                assertEquals("", reader.readLine());

                fixture.appendPrediction();

                assertEquals("event: coordination.updated", reader.readLine());
                assertEquals("id: 1", reader.readLine());
                assertTrue(reader.readLine().contains("PREDICTION_CREATED"));
                assertEquals("", reader.readLine());
            }
        }
    }

    @Test
    @Timeout(90)
    void onboardingCommandsUseRealLinkInviteJoinAndAnswerFlow() throws Exception {
        try (Fixture host = new Fixture("host"); Fixture join = new Fixture("join")) {
            Map<String, Object> hostCredentials = login(host);
            Map<String, Object> joinCredentials = login(join);
            HttpResponse<String> invitation = host.client.send(jsonRequest(host, "/api/v1/commands/invite")
                            .header(ControlPlaneHttpHandler.SESSION_HEADER,
                                    (String) hostCredentials.get("sessionToken"))
                            .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) hostCredentials.get("csrfToken"))
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of())))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, invitation.statusCode());
            Map<String, Object> invitationBody = object(invitation.body());

            HttpResponse<String> answer = join.client.send(jsonRequest(join, "/api/v1/commands/join")
                            .header(ControlPlaneHttpHandler.SESSION_HEADER,
                                    (String) joinCredentials.get("sessionToken"))
                            .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) joinCredentials.get("csrfToken"))
                            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                                    "inviteUri", invitationBody.get("inviteUri")))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, answer.statusCode());
            Map<String, Object> answerBody = object(answer.body());

            HttpRequest hostAnswerRequest = jsonRequest(host, "/api/v1/commands/answer")
                    .header(ControlPlaneHttpHandler.SESSION_HEADER,
                            (String) hostCredentials.get("sessionToken"))
                    .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) hostCredentials.get("csrfToken"))
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                            "operationId", invitationBody.get("operationId"),
                            "answerUri", answerBody.get("answerUri")))))
                    .build();
            HttpRequest joinConnectRequest = jsonRequest(join, "/api/v1/commands/connect")
                    .header(ControlPlaneHttpHandler.SESSION_HEADER,
                            (String) joinCredentials.get("sessionToken"))
                    .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) joinCredentials.get("csrfToken"))
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                            "operationId", answerBody.get("operationId")))))
                    .build();
            CompletableFuture<HttpResponse<String>> hostResult = host.client.sendAsync(hostAnswerRequest,
                    HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> joinResult = join.client.sendAsync(joinConnectRequest,
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, hostResult.get(60, TimeUnit.SECONDS).statusCode());
            assertEquals(200, joinResult.get(60, TimeUnit.SECONDS).statusCode());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort() + path));
    }

    private HttpRequest.Builder request(Fixture fixture, String path) {
        return HttpRequest.newBuilder(fixture.baseUri.resolve(path));
    }

    private HttpRequest.Builder jsonRequest(String path) {
        return request(path).header("Content-Type", "application/json; charset=utf-8");
    }

    private HttpRequest.Builder jsonRequest(Fixture fixture, String path) {
        return request(fixture, path).header("Content-Type", "application/json; charset=utf-8");
    }

    private int serverPort() {
        return activeServer.address().getPort();
    }

    private CoordinationHttpServer activeServer;

    private Map<String, Object> object(String json) {
        Object parsed = ProviderJson.parse(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) parsed;
        return result;
    }

    private Map<String, Object> login(Fixture fixture) throws Exception {
        HttpResponse<String> session = fixture.client.send(jsonRequest(fixture, "/api/v1/session")
                        .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                                Map.of("bootstrapToken", fixture.handler.bootstrapToken())))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, session.statusCode());
        return object(session.body());
    }

    private final class Fixture implements AutoCloseable {

        private final HttpClient client = HttpClient.newHttpClient();
        private final ControlPlaneHttpHandler handler;
        private final CoordinationHttpServer server;
        private final CoordinationService coordination;
        private final UUID projectId;

        private final URI baseUri;

        private Fixture() throws Exception {
            this("project");
        }

        private Fixture(String name) throws Exception {
            Path root = temp.resolve(name);
            Path synesis = root.resolve(".synesis");
            Path profile = synesis.resolve("local/profile");
            Files.createDirectories(profile);
            projectId = UUID.randomUUID();
            ProjectApplicationService.ProjectLocation location = new ProjectApplicationService.ProjectLocation(
                    root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
            coordination = new CoordinationService(
                    new PredictionEventStore(synesis.resolve("coordination"), projectId), NodeIdentity.generate());
            ControlPlaneEventHub eventHub = new ControlPlaneEventHub();
            Onboarding onboarding = new Onboarding(profile, eventHub::publish);
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, coordination,
                    new ProviderApplicationService(), new DoctorService());
            handler = new ControlPlaneHttpHandler(readModel, coordination, onboarding, eventHub);
            server = new CoordinationHttpServer(coordination, new InetSocketAddress("127.0.0.1", 0), null, handler);
            activeServer = server;
            server.start();
            baseUri = URI.create("http://127.0.0.1:" + server.address().getPort());
        }

        private void appendPrediction() throws Exception {
            NodeIdentity requester = NodeIdentity.generate();
            UUID prediction = UUID.randomUUID();
            PredictionContract contract = new PredictionContract(prediction, projectId, requester.nodeId(),
                    "supervisor-a", "worker-a", UUID.randomUUID(), "workspace.control-plane-test", requester.nodeId(),
                    "supervisor-a", List.of("workspace/**"), 0, "HEAD", List.of("workspace/**=absent"), 0,
                    "purpose", "inputs", "outputs", "behavior", "errors", "none", "invariants", "compatible",
                    "normal", "single-threaded", List.of("test"), 80, 20, 1_900_000_000_000L);
            CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), projectId, prediction,
                    PredictionEventType.PREDICTION_CREATED, requester.nodeId(), contract.encoded(), requester);
            coordination.submit(command);
        }

        @Override
        public void close() {
            handler.close();
            server.close();
            activeServer = null;
        }
    }
}
