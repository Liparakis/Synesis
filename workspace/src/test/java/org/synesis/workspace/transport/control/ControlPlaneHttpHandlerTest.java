package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayTopologyView;

import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.network.LinkNetworkProjection;
import org.synesis.workspace.transport.control.network.LinkState;
import org.synesis.workspace.transport.control.network.NetworkSnapshot;
import org.synesis.workspace.transport.control.network.RelayState;

class ControlPlaneHttpHandlerTest {

    @TempDir
    Path temp;

    /**
     * Link peers exchange their project context after authentication, so fixtures that connect to
     * each other must describe the same project.
     */
    private final UUID sharedProjectId = UUID.randomUUID();
    private final Instant sharedCreatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void healthIsPublicButSnapshotRequiresOneTimeSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HttpResponse<String> health = fixture.client.send(request(fixture, "/api/v1/health").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"loopbackOnly\":true"));

            HttpResponse<String> unauthenticated = fixture.client.send(request(fixture, "/api/v1/snapshot").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthenticated.statusCode());

            HttpResponse<String> invalidSession = fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("bootstrapToken", "wrong")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, invalidSession.statusCode());

            HttpResponse<String> session = fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("bootstrapToken", fixture.handler.bootstrapToken()))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, session.statusCode());
            Map<String, Object> credentials = object(session.body());
            String sessionToken = (String) credentials.get("sessionToken");
            String csrfToken = (String) credentials.get("csrfToken");

            HttpResponse<String> reusedBootstrap = fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("bootstrapToken",
                    fixture.handler.bootstrapToken())))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, reusedBootstrap.statusCode());

            HttpResponse<String> snapshot = fixture.client.send(request(fixture, "/api/v1/snapshot").header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, snapshot.statusCode());
            assertTrue(snapshot.body().contains("\"project\""));
            assertTrue(snapshot.body().contains("\"diagnostics\""));
            assertFalse(snapshot.body().contains(fixture.handler.bootstrapToken()));

            HttpResponse<String> mutationWithoutCsrf = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/answer").header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken)
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("operationId", "missing", "answerUri", "missing")))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, mutationWithoutCsrf.statusCode());

            HttpResponse<String> mutationWithCsrf = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/answer").header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken)
                            .header(ControlPlaneHttpHandler.CSRF_HEADER, csrfToken).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("operationId", "missing", "answerUri", "missing")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, mutationWithCsrf.statusCode());
        }
    }

    @Test
    void authenticatedSelectionRouteExposesSafeCandidatesAndAcceptsOnlyIds() throws Exception {
        UUID selectionId = UUID.randomUUID();
        UUID selectedProjectId = UUID.randomUUID();
        AtomicReference<UUID> selected = new AtomicReference<>();
        try (Fixture fixture = new Fixture("selection", false, null, new ControlPlaneHttpHandler.SelectionGateway() {
            @Override
            public Map<String, Object> describe(UUID requested) {
                assertEquals(selectionId, requested);
                return Map.of("ok",
                        true,
                        "state",
                        "PROJECT_SELECTION_REQUIRED",
                        "selectionId",
                        selectionId.toString(),
                        "expiresAt",
                        Instant.now().plusSeconds(60).toString(),
                        "projects",
                        List.of(Map.of("projectId", selectedProjectId.toString(), "displayName", "Safe name")));
            }

            @Override
            public Map<String, Object> select(UUID requestedSelection, UUID requestedProject) {
                assertEquals(selectionId, requestedSelection);
                selected.set(requestedProject);
                return Map.of("ok", true, "state", "DISPATCHED");
            }
        })) {
            Map<String, Object> credentials = login(fixture);
            String session = (String) credentials.get("sessionToken");
            String csrf = (String) credentials.get("csrfToken");
            HttpResponse<String> projection = fixture.client.send(request(fixture, "/api/v1/selection/" + selectionId).header(ControlPlaneHttpHandler.SESSION_HEADER, session).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, projection.statusCode());
            assertTrue(projection.body().contains("Safe name"));
            assertFalse(projection.body().contains("synesis://"));

            HttpResponse<String> withoutCsrf = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/select-project").header(ControlPlaneHttpHandler.SESSION_HEADER, session)
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("selectionId", selectionId.toString(), "projectId", selectedProjectId.toString(), "uri", "synesis://join/raw-must-not-be-used"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, withoutCsrf.statusCode());

            HttpResponse<String> selectedResponse = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/select-project").header(ControlPlaneHttpHandler.SESSION_HEADER, session)
                    .header(ControlPlaneHttpHandler.CSRF_HEADER, csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("selectionId", selectionId.toString(), "projectId", selectedProjectId.toString())))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, selectedResponse.statusCode());
            assertEquals(selectedProjectId, selected.get());
        }
    }

    @Test
    void snapshotExposesDurableProjectStateThroughHttp() throws Exception {
        try (Fixture fixture = new Fixture("durable-project", true)) {
            Map<String, Object> credentials = login(fixture);
            HttpResponse<String> response = fixture.client.send(request(fixture, "/api/v1/snapshot").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            Map<String, Object> snapshot = object(response.body());
            @SuppressWarnings("unchecked") Map<String, Object> project = (Map<String, Object>) snapshot.get("project");
            assertEquals(fixture.projectId.toString(), project.get("id"));

            @SuppressWarnings("unchecked") List<Map<String, Object>> workgroups = (List<Map<String, Object>>) snapshot.get("workgroups");
            assertEquals(1, workgroups.size());
            assertEquals(fixture.seededGroupId.toString(), workgroups.getFirst().get("id"));
            assertEquals(List.of("agt_http"), workgroups.getFirst().get("participants"));

            @SuppressWarnings("unchecked") List<Map<String, Object>> claims = (List<Map<String, Object>>) snapshot.get("claims");
            assertEquals(1, claims.size());
            assertEquals(fixture.seededIntentId.toString(), claims.getFirst().get("intentId"));
            assertEquals("agt_http", claims.getFirst().get("participant"));
        }
    }

    private HttpResponse<String> trashCommand(Fixture fixture, Map<String, Object> credentials, String command, Map<String, Object> body) throws Exception {
        return fixture.client.send(jsonRequest(fixture, "/api/v1/commands/" + command).header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken"))
                .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) credentials.get("csrfToken")).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** ADR-0098 step 7: the Trash tab confirms an empty, and this endpoint refuses one that was not confirmed. */
    @Test
    void emptyingTheTrashNeedsConfirmationAndRestoringAnUnknownItemIsRefused() throws Exception {
        try (Fixture fixture = new Fixture("trash-commands", true)) {
            Map<String, Object> credentials = login(fixture);
            String lane = UUID.randomUUID().toString();

            HttpResponse<String> unconfirmed = trashCommand(fixture, credentials, "empty-trash", Map.of("laneId", lane, "epoch", 1));
            assertEquals(400, unconfirmed.statusCode());
            assertTrue(unconfirmed.body().contains("CONFIRMATION_REQUIRED"), unconfirmed.body());

            HttpResponse<String> notConfirmedTrue = trashCommand(fixture, credentials, "empty-trash", Map.of("laneId", lane, "epoch", 1, "confirmed", "yes"));
            assertEquals(400, notConfirmedTrue.statusCode());

            HttpResponse<String> badEpoch = trashCommand(fixture, credentials, "restore-trash", Map.of("laneId", lane, "epoch", -1));
            assertEquals(400, badEpoch.statusCode());

            HttpResponse<String> badLane = trashCommand(fixture, credentials, "restore-trash", Map.of("laneId", "not-a-uuid", "epoch", 1));
            assertEquals(400, badLane.statusCode());

            HttpResponse<String> unknown = trashCommand(fixture, credentials, "restore-trash", Map.of("laneId", lane, "epoch", 1));
            assertEquals(409, unknown.statusCode(), unknown.body());
            assertTrue(unknown.body().contains("TRASH_REFUSED"), unknown.body());

            HttpResponse<String> confirmedUnknown = trashCommand(fixture, credentials, "empty-trash", Map.of("laneId", lane, "epoch", 1, "confirmed", true));
            assertEquals(409, confirmedUnknown.statusCode(), confirmedUnknown.body());
        }
    }

    private Map<String, Object> snapshotOf(Fixture fixture, Map<String, Object> credentials) throws Exception {
        return object(fixture.client.send(request(fixture, "/api/v1/snapshot").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    /** ADR-0103 stage 3: the page closes what an agent asked, runs the commands' own rule for what Synesis raised, and checks again. */
    @Test
    void anOperatorRequestALaneRaisedIsClosedFromThePageAndOneSynesisRaisedIsNot() throws Exception {
        try (Fixture fixture = new Fixture("operator-commands", true)) {
            Map<String, Object> credentials = login(fixture);
            NodeIdentity writer = NodeIdentity.generate();
            PredictionEventStore store = new PredictionEventStore(fixture.coordinationDirectory, fixture.projectId);
            OperatorRequest asked = OperatorRequest.askedByLane("opr_lane000000000001", UUID.randomUUID().toString(), "Which license?", "participant agt_x");
            OperatorRequest raised = OperatorRequest.raised("opr_synesis000000001", OperatorRequest.BOUND_CONSUMER_ABSENT, "a consumer is absent", "");
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, writer.nodeId(), asked.encodeRaised(), writer);
            store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, writer.nodeId(), raised.encodeRaised(), writer);
            assertEquals(2, ((List<?>) snapshotOf(fixture, credentials).get("operatorRequests")).size());

            assertEquals(400, trashCommand(fixture, credentials, "close-operator-request", Map.of()).statusCode());
            HttpResponse<String> refused = trashCommand(fixture, credentials, "close-operator-request", Map.of("requestId", raised.id()));
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("OPERATOR_REQUEST_NOT_CLOSED"), refused.body());
            assertEquals(409, trashCommand(fixture, credentials, "close-operator-request", Map.of("requestId", "opr_unknown")).statusCode());

            HttpResponse<String> closed = trashCommand(fixture, credentials, "close-operator-request", Map.of("requestId", asked.id(), "because", "answered by phone"));
            assertEquals(200, closed.statusCode(), closed.body());
            assertEquals("CLOSED", object(closed.body()).get("state"));
            List<?> open = (List<?>) snapshotOf(fixture, credentials).get("operatorRequests");
            assertEquals(1, open.size());
            assertEquals(raised.id(), ((Map<?, ?>) open.getFirst()).get("id"));
            assertEquals(409, trashCommand(fixture, credentials, "close-operator-request", Map.of("requestId", asked.id())).statusCode(), "a request closes once");
        }
    }

    @Test
    void recheckingTheOperatorRequestsRunsTheOperatorsPassAndSaysWhatIsStillOpen() throws Exception {
        try (Fixture fixture = new Fixture("operator-recheck", true)) {
            Map<String, Object> credentials = login(fixture);
            HttpResponse<String> rechecked = trashCommand(fixture, credentials, "recheck-operator-requests", Map.of());
            assertEquals(200, rechecked.statusCode(), rechecked.body());
            Map<String, Object> answer = object(rechecked.body());
            assertTrue(answer.get("state") instanceof String, rechecked.body());
            assertEquals(0L, ((Number) answer.get("openRequests")).longValue());
            assertEquals(403, fixture.client.send(jsonRequest(fixture, "/api/v1/commands/recheck-operator-requests").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "a command needs the page's CSRF token");
        }
    }

    /** ADR-0103 stage 3: the owner removes a member from the page by the path of project remove-member, after confirming. */
    @Test
    void theOwnerRemovesAMemberFromThePageOnlyAfterConfirmingAndTheMembersListFollows() throws Exception {
        try (Fixture fixture = new Fixture("member-removal")) {
            Map<String, Object> credentials = login(fixture);
            NodeIdentity owner = fixture.location.loadOrCreateIdentity();
            String member = NodeIdentity.generate().nodeId();
            ProjectSharing.admit(fixture.location.root(), fixture.coordinationDirectory, new MembershipService(fixture.location.openCoordinationStore(), owner), member, null);
            @SuppressWarnings("unchecked") Map<String, Object> before = (Map<String, Object>) snapshotOf(fixture, credentials).get("membership");
            assertEquals(true, before.get("selfIsOwner"));
            assertEquals(2, ((List<?>) before.get("members")).size());

            HttpResponse<String> unconfirmed = trashCommand(fixture, credentials, "remove-member", Map.of("nodeId", member));
            assertEquals(400, unconfirmed.statusCode());
            assertTrue(unconfirmed.body().contains("CONFIRMATION_REQUIRED"), unconfirmed.body());
            assertEquals(400, trashCommand(fixture, credentials, "remove-member", Map.of("nodeId", "not-a-node", "confirmed", true)).statusCode());
            HttpResponse<String> itself = trashCommand(fixture, credentials, "remove-member", Map.of("nodeId", owner.nodeId(), "confirmed", true));
            assertEquals(409, itself.statusCode(), itself.body());
            assertTrue(itself.body().contains("MEMBER_IS_THIS_COMPUTER"), itself.body());
            HttpResponse<String> stranger = trashCommand(fixture, credentials, "remove-member", Map.of("nodeId", NodeIdentity.generate().nodeId(), "confirmed", true));
            assertEquals(409, stranger.statusCode(), stranger.body());
            assertTrue(stranger.body().contains("NOT_A_MEMBER"), stranger.body());
            assertEquals(2, ((List<?>) ((Map<?, ?>) snapshotOf(fixture, credentials).get("membership")).get("members")).size(), "a refusal removes nobody");

            HttpResponse<String> removed = trashCommand(fixture, credentials, "remove-member", Map.of("nodeId", member, "confirmed", true));
            assertEquals(200, removed.statusCode(), removed.body());
            assertEquals("REMOVED", object(removed.body()).get("state"));
            List<?> after = (List<?>) ((Map<?, ?>) snapshotOf(fixture, credentials).get("membership")).get("members");
            assertEquals(1, after.size());
            assertEquals(owner.nodeId(), ((Map<?, ?>) after.getFirst()).get("nodeId"));
        }
    }

    @Test
    void aComputerThatFollowsAnotherLeaderCannotRemoveAMember() throws Exception {
        try (Fixture fixture = new Fixture("member-follower")) {
            Map<String, Object> credentials = login(fixture);
            String leader = NodeIdentity.generate().nodeId();
            new ReplicationSettings(leader, Set.of()).write(fixture.coordinationDirectory);

            HttpResponse<String> refused = trashCommand(fixture, credentials, "remove-member", Map.of("nodeId", NodeIdentity.generate().nodeId(), "confirmed", true));

            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("ALREADY_A_MEMBER"), refused.body());
        }
    }

    @Test
    void networkEndpointProjectsRealLinkMembership() throws Exception {
        AtomicReference<LinkState> state = new AtomicReference<>();
        LinkNetworkProjection projection = new LinkNetworkProjection(state::get);
        try (Fixture fixture = new Fixture("link-network", false, projection)) {
            NodeIdentity local = NodeIdentity.generate();
            NodeIdentity destination = NodeIdentity.generate();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(fixture.projectId,
                    7,
                    now.minusSeconds(1),
                    now.plusSeconds(300),
                    List.of(new OverlayMembershipSnapshot.Member(local.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE, local.publicKeyEncoded()),
                            new OverlayMembershipSnapshot.Member(destination.nodeId(), OverlayMembershipSnapshot.STATUS_ACTIVE, destination.publicKeyEncoded())),
                    local);
            state.set(new LinkState(local.nodeId(), List.of(), Optional.of(new OverlayMembershipView(membership)), Optional.of(new OverlayTopologyView()), Set.of(), RelayState.disabled()));

            Map<String, Object> credentials = login(fixture);
            HttpResponse<String> response = fixture.client.send(request(fixture, "/api/v1/network").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            Map<String, Object> body = object(response.body());
            @SuppressWarnings("unchecked") Map<String, Object> network = (Map<String, Object>) body.get("network");
            assertEquals("HEALTHY", network.get("status"));
            @SuppressWarnings("unchecked") Map<String, Object> overlay = (Map<String, Object>) network.get("overlay");
            assertEquals("CURRENT", overlay.get("status"));
            assertEquals("2", String.valueOf(overlay.get("memberCount")));
            @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) overlay.get("members");
            assertTrue(members.stream().anyMatch(member -> member.get("nodeId").equals(destination.nodeId())));
        }
    }

    @Test
    void rejectsUntrustedBrowserOriginBeforeAuthentication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HttpResponse<String> response = fixture.client.send(request(fixture, "/api/v1/health").header("Origin", "http://evil.example:" + fixture.server.address().getPort()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.body().contains("ORIGIN_NOT_ALLOWED"));

            HttpResponse<String> allowedOrigin = fixture.client.send(request(fixture, "/api/v1/health").header("Origin", "http://localhost:" + fixture.server.address().getPort()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowedOrigin.statusCode());
            assertEquals("http://localhost:" + fixture.server.address().getPort(), allowedOrigin.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        }
    }

    @Test
    void rejectsBadHostMalformedJsonAndOversizedBodies() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (Socket socket = new Socket("127.0.0.1", fixture.server.address().getPort())) {
                socket.getOutputStream().write(("GET /api/v1/health HTTP/1.1\r\n" + "Host: evil.example:" + fixture.server.address().getPort() + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(response.contains(" 403 "));
                assertTrue(response.contains("HOST_NOT_ALLOWED"));
            }

            HttpResponse<String> malformed = fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString("{")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());

            String oversized = "x".repeat(129 * 1024);
            HttpResponse<String> tooLarge = fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(oversized)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, tooLarge.statusCode());
        }
    }

    @Test
    void liveStreamSendsSnapshotThenSafeCoordinationDelta() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> credentials = login(fixture);
            HttpRequest streamRequest = request(fixture, "/api/v1/events").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build();
            HttpResponse<java.io.InputStream> stream = fixture.client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body()))) {
                assertEquals("event: snapshot", reader.readLine());
                assertTrue(reader.readLine().startsWith("id: "));
                assertTrue(reader.readLine().contains("\"snapshot\""));
                assertEquals("", reader.readLine());

                fixture.appendPrediction();

                assertEquals("event: coordination.updated", reader.readLine());
                assertEquals("id: 1", reader.readLine());
                assertTrue(reader.readLine().contains("\"type\":\"coordination.updated\""));
                assertEquals("", reader.readLine());
            }
        }
    }

    @Test
    @Timeout(20)
    void liveStreamFansOutToMultipleClients() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> credentials = login(fixture);
            String token = (String) credentials.get("sessionToken");
            HttpResponse<java.io.InputStream> first = fixture.client.send(request(fixture, "/api/v1/events").header(ControlPlaneHttpHandler.SESSION_HEADER, token).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<java.io.InputStream> second = fixture.client.send(request(fixture, "/api/v1/events").header(ControlPlaneHttpHandler.SESSION_HEADER, token).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            try (BufferedReader firstReader = new BufferedReader(new InputStreamReader(first.body()));
                    BufferedReader secondReader = new BufferedReader(new InputStreamReader(second.body()))) {
                assertSnapshotEvent(firstReader);
                assertSnapshotEvent(secondReader);

                fixture.appendPrediction();

                assertCoordinationEvent(firstReader);
                assertCoordinationEvent(secondReader);
            }
        }
    }

    @Test
    @Timeout(20)
    void liveStreamReportsEventsAnotherProcessAppendedToTheProjectLog() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> credentials = login(fixture);
            HttpResponse<java.io.InputStream> stream = fixture.client.send(request(fixture, "/api/v1/events").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body()))) {
                assertSnapshotEvent(reader);

                fixture.appendAsAnotherProcess();

                assertCoordinationEvent(reader);
            }
        }
    }

    @Test
    @Timeout(20)
    void liveStreamMapsPeerLifecycleToSemanticEvents()throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> credentials = login(fixture);
            HttpResponse<java.io.InputStream> stream = fixture.client.send(request(fixture, "/api/v1/events").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body()))) {
                assertSnapshotEvent(reader);
                fixture.eventHub.publish(new OnboardingEvent(OnboardingEventType.PEER_CONNECTED, ""));
                assertEquals("event: peer.connected", reader.readLine());
                assertTrue(reader.readLine().startsWith("id: "));
                assertTrue(reader.readLine().contains("\"type\":\"peer.connected\""));
                assertEquals("", reader.readLine());
            }
        }
    }

    @Test
    @Timeout(20)
    void closingControlPlaneTerminatesLiveStream() throws Exception {
        Fixture fixture = new Fixture();
        try {
            Map<String, Object> credentials = login(fixture);
            HttpResponse<java.io.InputStream> stream = fixture.client.send(request(fixture, "/api/v1/events").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) credentials.get("sessionToken")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body()))) {
                assertSnapshotEvent(reader);
                CompletableFuture<String> terminalRead = CompletableFuture.supplyAsync(() -> {
                    try {
                        return reader.readLine();
                    } catch (java.io.IOException closed) {
                        return null;
                    }
                });
                fixture.close();
                assertNull(terminalRead.get(5, TimeUnit.SECONDS));
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    @Timeout(90)
    void onboardingCommandsUseRealLinkInviteAndOneWayJoinFlow() throws Exception {
        try (Fixture host = new Fixture("host");
                Fixture join = new Fixture("join")) {
            Map<String, Object> hostCredentials = login(host);
            Map<String, Object> joinCredentials = login(join);
            HttpResponse<String> invitation = host.client.send(jsonRequest(host, "/api/v1/commands/invite").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) hostCredentials.get("sessionToken"))
                            .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) hostCredentials.get("csrfToken")).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of()))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, invitation.statusCode());
            Map<String, Object> invitationBody = object(invitation.body());
            assertTrue(((String) invitationBody.get("inviteUri")).startsWith("synesis://join/SLB3-"));
            assertEquals("WAITING_FOR_CONNECTION", invitationBody.get("state"));

            HttpResponse<String> connection = join.client.send(jsonRequest(join, "/api/v1/commands/join").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) joinCredentials.get("sessionToken"))
                    .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) joinCredentials.get("csrfToken"))
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("inviteUri", invitationBody.get("inviteUri"))))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, connection.statusCode());
            Map<String, Object> connectionBody = object(connection.body());
            assertEquals("CONNECTING", connectionBody.get("state"));
            assertTrue(((String) connectionBody.get("answerUri")).startsWith("synesis://answer/SLA3-"));

            String link = (String) invitationBody.get("inviteUri");
            int dash = link.indexOf('-') + 1;
            byte[] payload = java.util.Base64.getUrlDecoder().decode(link.substring(dash));
            payload[1] = (byte) (payload[1] + 1);
            String newer = link.substring(0, dash) + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            HttpResponse<String> refused = join.client.send(jsonRequest(join, "/api/v1/commands/join").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) joinCredentials.get("sessionToken"))
                    .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) joinCredentials.get("csrfToken"))
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("inviteUri", newer)))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, refused.statusCode());
            assertTrue(refused.body().contains("INCOMPATIBLE_VERSION"));
            assertTrue(refused.body().contains("This computer is older: update Synesis on this computer, then ask for a new invitation."));
            assertFalse(refused.body().contains("INVITE_INVALID"));
        }
    }

    @Test
    @Timeout(90)
    void runtimeCommandDeepLinkUsesExistingOneWayBootstrapAuthority() throws Exception {
        try (Fixture host = new Fixture("deep-link-host");
                Fixture join = new Fixture("deep-link-join")) {
            Map<String, Object> hostCredentials = login(host);
            HttpResponse<String> invitation = host.client.send(jsonRequest(host, "/api/v1/commands/invite").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) hostCredentials.get("sessionToken"))
                            .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) hostCredentials.get("csrfToken")).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of()))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, invitation.statusCode());
            Map<String, Object> invitationBody = object(invitation.body());

            HttpResponse<String> unauthorized = join.client.send(jsonRequest(join, "/api/v1/commands/deep-link").header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, "wrong")
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("uri", invitationBody.get("inviteUri"))))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> malformed = join.client.send(jsonRequest(join, "/api/v1/commands/deep-link").header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, join.handler.runtimeCommandToken())
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("uri", "synesis://join/SLO1-invalid")))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
            assertTrue(malformed.body().contains("INVITE_INVALID"));

            HttpResponse<String> joinResult = join.client.send(jsonRequest(join, "/api/v1/commands/deep-link").header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, join.handler.runtimeCommandToken())
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("uri", invitationBody.get("inviteUri"))))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, joinResult.statusCode());
            Map<String, Object> joinBody = object(joinResult.body());
            assertEquals("CONNECTING", joinBody.get("state"));
            assertTrue(((String) joinBody.get("answerUri")).startsWith("synesis://answer/SLA3-"));

            // The join dials in the background and leaves the pending list once it has connected.
            Map<String, Object> joinCredentials = login(join);
            List<?> pendingJoins = List.of(joinBody);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!pendingJoins.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
                HttpResponse<String> pendingSnapshot = join.client.send(request(join, "/api/v1/snapshot").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) joinCredentials.get("sessionToken")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, pendingSnapshot.statusCode());
                Map<?, ?> onboarding = (Map<?, ?>) object(pendingSnapshot.body()).get("onboarding");
                pendingJoins = (List<?>) onboarding.get("pendingJoins");
            }
            assertTrue(pendingJoins.isEmpty());

            HttpResponse<String> cancel = host.client.send(jsonRequest(host, "/api/v1/commands/cancel").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) hostCredentials.get("sessionToken"))
                    .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) hostCredentials.get("csrfToken"))
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("operationId", invitationBody.get("operationId"))))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, cancel.statusCode());
            assertTrue(cancel.body().contains("OPERATION_NOT_FOUND"));
        }
    }

    /** The runtime outlives the browser tab, so its owner can open the UI again and again. */
    @Test
    void runtimeOwnerGetsAFreshOneTimeBootstrapForEachOpening() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HttpResponse<String> stranger = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/bootstrap").header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, "wrong")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, stranger.statusCode());
            HttpResponse<String> browser = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/bootstrap").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, browser.statusCode());

            Map<String, Object> first = login(fixture);
            String second = issueBootstrap(fixture);
            String third = issueBootstrap(fixture);
            assertFalse(second.equals(third));
            assertFalse(third.equals(fixture.handler.bootstrapToken()));

            assertEquals(401, session(fixture, fixture.handler.bootstrapToken()).statusCode(), "the spent first token opened the UI again");
            assertEquals(401, session(fixture, second).statusCode(), "a token superseded by a newer one was still redeemable");
            HttpResponse<String> reopened = session(fixture, third);
            assertEquals(201, reopened.statusCode());
            assertEquals(401, session(fixture, third).statusCode(), "a fresh token was redeemable twice");

            String reopenedSession = (String) object(reopened.body()).get("sessionToken");
            assertEquals(200, fixture.client.send(request(fixture, "/api/v1/snapshot").header(ControlPlaneHttpHandler.SESSION_HEADER, reopenedSession).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, fixture.client.send(request(fixture, "/api/v1/snapshot").header(ControlPlaneHttpHandler.SESSION_HEADER, (String) first.get("sessionToken")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "the earlier tab's session outlived its replacement");
        }
    }

    private String issueBootstrap(Fixture fixture) throws Exception {
        HttpResponse<String> issued = fixture.client.send(jsonRequest(fixture, "/api/v1/commands/bootstrap").header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, fixture.handler.runtimeCommandToken())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, issued.statusCode());
        return (String) object(issued.body()).get("bootstrapToken");
    }

    private HttpResponse<String> session(Fixture fixture, String bootstrapToken) throws Exception {
        return fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("bootstrapToken", bootstrapToken)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(Fixture fixture, String path) {
        return HttpRequest.newBuilder(fixture.baseUri.resolve(path));
    }

    private HttpRequest.Builder jsonRequest(Fixture fixture, String path) {
        return request(fixture, path).header("Content-Type", "application/json; charset=utf-8");
    }

    private Map<String, Object> object(String json) {
        Object parsed = ProviderJson.parse(json);
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) parsed;
        return result;
    }

    private Map<String, Object> login(Fixture fixture) throws Exception {
        HttpResponse<String> session = fixture.client.send(jsonRequest(fixture, "/api/v1/session").POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("bootstrapToken", fixture.handler.bootstrapToken()))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, session.statusCode());
        return object(session.body());
    }

    private void assertSnapshotEvent(BufferedReader reader) throws Exception {
        assertEquals("event: snapshot", reader.readLine());
        assertTrue(reader.readLine().startsWith("id: "));
        assertTrue(reader.readLine().contains("\"snapshot\""));
        assertEquals("", reader.readLine());
    }

    private void assertCoordinationEvent(BufferedReader reader) throws Exception {
        assertEquals("event: coordination.updated", reader.readLine());
        assertEquals("id: 1", reader.readLine());
        assertTrue(reader.readLine().contains("\"type\":\"coordination.updated\""));
        assertEquals("", reader.readLine());
    }

    private final class Fixture implements AutoCloseable {

        private final HttpClient client = HttpClient.newHttpClient();
        private final ControlPlaneHttpHandler handler;
        private final CoordinationHttpServer server;
        private final CoordinationService coordination;
        private final ControlPlaneEventHub eventHub;
        private final UUID projectId;
        private final Path coordinationDirectory;
        private final URI baseUri;
        private final ProjectLocation location;
        private UUID seededGroupId;
        private UUID seededIntentId;
        private boolean closed;

        private Fixture() throws Exception {
            this("project");
        }

        private Fixture(String name) throws Exception {
            this(name, false, null);
        }

        private Fixture(String name, boolean seedProject) throws Exception {
            this(name, seedProject, null, null);
        }

        private Fixture(String name, boolean seedProject, java.util.function.Supplier<NetworkSnapshot> network) throws Exception {
            this(name, seedProject, network, null);
        }

        private Fixture(String name, boolean seedProject, java.util.function.Supplier<NetworkSnapshot> network, ControlPlaneHttpHandler.SelectionGateway selectionGateway) throws Exception {
            Path root = temp.resolve(name);
            Path synesis = root.resolve(".synesis");
            Path profile = synesis.resolve("local/profile");
            Files.createDirectories(profile);
            projectId = sharedProjectId;
            location = new ProjectLocation(root, synesis, root.resolve("project.json"), profile, projectId, sharedCreatedAt);
            Path coordinationRoot = location.coordinationDirectory();
            coordinationDirectory = coordinationRoot;
            NodeIdentity signer = NodeIdentity.generate();
            if (seedProject) {
                seedProject(coordinationRoot, signer);
            }
            coordination = new CoordinationService(new PredictionEventStore(coordinationRoot, projectId), signer);
            eventHub = new ControlPlaneEventHub();
            Onboarding onboarding = new Onboarding(profile, eventHub::publish);
            LinkRuntimeOwner linkOwner = new LinkRuntimeOwner(onboarding, signer, null, eventHub::publish, new ProjectIdentity(projectId, sharedCreatedAt));
            java.util.function.Supplier<NetworkSnapshot> networkSource = network == null ? NetworkSnapshot::empty : network;
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, coordination, new ProviderApplicationService(), new DoctorService(), networkSource, null);
            handler = new ControlPlaneHttpHandler(readModel, coordination, linkOwner, eventHub, selectionGateway == null ? ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE : selectionGateway, null);
            server = new CoordinationHttpServer(coordination, new InetSocketAddress("127.0.0.1", 0), null, handler);
            server.start();
            baseUri = URI.create("http://127.0.0.1:" + server.address().getPort());
        }

        private CoordinationCommand predictionCommand() throws Exception {
            NodeIdentity requester = NodeIdentity.generate();
            UUID prediction = UUID.randomUUID();
            PredictionContract contract = new PredictionContract(prediction,
                    projectId,
                    requester.nodeId(),
                    "supervisor-a",
                    "worker-a",
                    UUID.randomUUID(),
                    "workspace.control-plane-test",
                    requester.nodeId(),
                    "supervisor-a",
                    List.of("workspace/**"),
                    0,
                    "HEAD",
                    List.of("workspace/**=absent"),
                    0,
                    "purpose",
                    "inputs",
                    "outputs",
                    "behavior",
                    "errors",
                    "none",
                    "invariants",
                    "compatible",
                    "normal",
                    "single-threaded",
                    List.of("test"),
                    80,
                    20,
                    1_900_000_000_000L);
            CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), projectId, prediction, PredictionEventType.PREDICTION_CREATED, requester.nodeId(), contract.encoded(), requester);
            return command;
        }

        private void appendPrediction() throws Exception {
            coordination.submit(predictionCommand());
        }

        /** Appends the way an agent process does: straight to the project log, never through the server. */
        private void appendAsAnotherProcess() throws Exception {
            CoordinationCommand command = predictionCommand();
            NodeIdentity agent = NodeIdentity.generate();
            new PredictionEventStore(coordinationDirectory, projectId).append(command.predictionId(), command.type(), agent.nodeId(), command.encoded(), agent);
        }

        private void seedProject(Path coordinationRoot, NodeIdentity signer) throws Exception {
            PredictionEventStore writer = new PredictionEventStore(coordinationRoot, projectId);
            seededGroupId = UUID.randomUUID();
            new WorkGroupService(writer, signer).create(new WorkGroup(seededGroupId, projectId, "http goal", "http acceptance", 1, WorkGroup.Status.ACTIVE));
            seededIntentId = UUID.randomUUID();
            WorkIntent intent = new WorkIntent(seededIntentId,
                    projectId,
                    "agt_http",
                    "codex",
                    UUID.randomUUID(),
                    "http implementation",
                    "http tests",
                    "HEAD",
                    List.of(ResourceSelector.pathExact("src/App.java")),
                    1,
                    seededGroupId,
                    WorkIntent.defaultAuthorityLineage(seededIntentId),
                    WorkIntent.Status.ANNOUNCED,
                    WorkIntent.Role.PRODUCER);
            new WorkIntentService(new PredictionEventStore(coordinationRoot, projectId), signer).announce(intent);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            handler.close();
            server.close();
        }
    }
}
