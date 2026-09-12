package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
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
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

class ControlPlaneHttpHandlerTest {

  @TempDir
  Path temp;
  private CoordinationHttpServer activeServer;

  @Test
  void healthIsPublicButSnapshotRequiresOneTimeSession() throws Exception {
    try (Fixture fixture = new Fixture()) {
      HttpResponse<String> health = fixture.client.send(request("/api/v1/health").GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      assertTrue(health.body().contains("\"loopbackOnly\":true"));

      HttpResponse<String> unauthenticated = fixture.client.send(
          request("/api/v1/snapshot").GET().build(),
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

      HttpResponse<String> mutationWithoutCsrf = fixture.client.send(
          jsonRequest("/api/v1/commands/answer")
              .header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken)
              .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                  Map.of("operationId", "missing", "answerUri", "missing")))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(403, mutationWithoutCsrf.statusCode());

      HttpResponse<String> mutationWithCsrf = fixture.client.send(
          jsonRequest("/api/v1/commands/answer")
              .header(ControlPlaneHttpHandler.SESSION_HEADER, sessionToken)
              .header(ControlPlaneHttpHandler.CSRF_HEADER, csrfToken)
              .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                  Map.of("operationId", "missing", "answerUri", "missing")))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(404, mutationWithCsrf.statusCode());
    }
  }

  @Test
  void authenticatedSelectionRouteExposesSafeCandidatesAndAcceptsOnlyIds() throws Exception {
    UUID selectionId = UUID.randomUUID();
    UUID selectedProjectId = UUID.randomUUID();
    AtomicReference<UUID> selected = new AtomicReference<>();
    try (Fixture fixture = new Fixture("selection", false, null,
        new ControlPlaneHttpHandler.SelectionGateway() {
          @Override
          public Map<String, Object> describe(UUID requested) {
            assertEquals(selectionId, requested);
            return Map.of("ok", true, "state", "PROJECT_SELECTION_REQUIRED",
                "selectionId", selectionId.toString(), "expiresAt", Instant.now().plusSeconds(60).toString(),
                "projects", List.of(Map.of("projectId", selectedProjectId.toString(),
                    "displayName", "Safe name")));
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
      HttpResponse<String> projection = fixture.client.send(request(fixture,
              "/api/v1/selection/" + selectionId).header(ControlPlaneHttpHandler.SESSION_HEADER,
                  session).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, projection.statusCode());
      assertTrue(projection.body().contains("Safe name"));
      assertFalse(projection.body().contains("synesis://"));

      HttpResponse<String> withoutCsrf = fixture.client.send(jsonRequest(fixture,
              "/api/v1/commands/select-project").header(ControlPlaneHttpHandler.SESSION_HEADER,
                  session).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                      "selectionId", selectionId.toString(), "projectId", selectedProjectId.toString(),
                      "uri", "synesis://join/raw-must-not-be-used")))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(403, withoutCsrf.statusCode());

      HttpResponse<String> selectedResponse = fixture.client.send(jsonRequest(fixture,
              "/api/v1/commands/select-project").header(ControlPlaneHttpHandler.SESSION_HEADER,
                  session).header(ControlPlaneHttpHandler.CSRF_HEADER, csrf)
          .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
              "selectionId", selectionId.toString(), "projectId", selectedProjectId.toString())))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, selectedResponse.statusCode());
      assertEquals(selectedProjectId, selected.get());
    }
  }

  @Test
  void snapshotExposesDurableProjectStateThroughHttp() throws Exception {
    try (Fixture fixture = new Fixture("durable-project", true)) {
      Map<String, Object> credentials = login(fixture);
      HttpResponse<String> response = fixture.client.send(request(fixture, "/api/v1/snapshot")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) credentials.get("sessionToken"))
              .GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());

      Map<String, Object> snapshot = object(response.body());
      @SuppressWarnings("unchecked")
      Map<String, Object> project = (Map<String, Object>) snapshot.get("project");
      assertEquals(fixture.projectId.toString(), project.get("id"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> workgroups = (List<Map<String, Object>>) snapshot.get("workgroups");
      assertEquals(1, workgroups.size());
      assertEquals(fixture.seededGroupId.toString(), workgroups.getFirst().get("id"));
      assertEquals(List.of("agt_http"), workgroups.getFirst().get("participants"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> claims = (List<Map<String, Object>>) snapshot.get("claims");
      assertEquals(1, claims.size());
      assertEquals(fixture.seededIntentId.toString(), claims.getFirst().get("intentId"));
      assertEquals("agt_http", claims.getFirst().get("participant"));
    }
  }

  @Test
  void networkEndpointProjectsRealLinkMembership() throws Exception {
    AtomicReference<LinkNetworkProjection.LinkState> state = new AtomicReference<>();
    LinkNetworkProjection projection = new LinkNetworkProjection(state::get);
    try (Fixture fixture = new Fixture("link-network", false, projection)) {
      NodeIdentity local = NodeIdentity.generate();
      NodeIdentity destination = NodeIdentity.generate();
      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(fixture.projectId, 7,
          now.minusSeconds(1), now.plusSeconds(300), List.of(
              new OverlayMembershipSnapshot.Member(local.nodeId(),
                  OverlayMembershipSnapshot.STATUS_ACTIVE, local.publicKeyEncoded()),
              new OverlayMembershipSnapshot.Member(destination.nodeId(),
                  OverlayMembershipSnapshot.STATUS_ACTIVE, destination.publicKeyEncoded())),
          local);
      state.set(new LinkNetworkProjection.LinkState(local.nodeId(), List.of(),
          Optional.of(new OverlayMembershipView(membership)),
          Optional.of(new OverlayTopologyView()),
          Set.of(), LinkNetworkProjection.RelayState.disabled()));

      Map<String, Object> credentials = login(fixture);
      HttpResponse<String> response = fixture.client.send(request(fixture, "/api/v1/network")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) credentials.get("sessionToken"))
              .GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());

      Map<String, Object> body = object(response.body());
      @SuppressWarnings("unchecked")
      Map<String, Object> network = (Map<String, Object>) body.get("network");
      assertEquals("HEALTHY", network.get("status"));
      @SuppressWarnings("unchecked")
      Map<String, Object> overlay = (Map<String, Object>) network.get("overlay");
      assertEquals("CURRENT", overlay.get("status"));
      assertEquals("2", String.valueOf(overlay.get("memberCount")));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> members = (List<Map<String, Object>>) overlay.get("members");
      assertTrue(
          members.stream().anyMatch(member -> member.get("nodeId").equals(destination.nodeId())));
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
              .POST(HttpRequest.BodyPublishers.ofString("{")).build(),
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
      HttpResponse<java.io.InputStream> first = fixture.client.send(
          request(fixture, "/api/v1/events")
              .header(ControlPlaneHttpHandler.SESSION_HEADER, token).GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
      HttpResponse<java.io.InputStream> second = fixture.client.send(
          request(fixture, "/api/v1/events")
              .header(ControlPlaneHttpHandler.SESSION_HEADER, token).GET().build(),
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
  void liveStreamMapsPeerLifecycleToSemanticEvents() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Map<String, Object> credentials = login(fixture);
      HttpResponse<java.io.InputStream> stream = fixture.client.send(
          request(fixture, "/api/v1/events")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) credentials.get("sessionToken"))
              .GET().build(),
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
      HttpResponse<java.io.InputStream> stream = fixture.client.send(
          request(fixture, "/api/v1/events")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) credentials.get("sessionToken"))
              .GET().build(),
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
  void onboardingCommandsUseRealLinkInviteJoinAndAnswerFlow() throws Exception {
    try (Fixture host = new Fixture("host"); Fixture join = new Fixture("join")) {
      Map<String, Object> hostCredentials = login(host);
      Map<String, Object> joinCredentials = login(join);
      HttpResponse<String> invitation = host.client.send(
          jsonRequest(host, "/api/v1/commands/invite")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) hostCredentials.get("sessionToken"))
              .header(ControlPlaneHttpHandler.CSRF_HEADER,
                  (String) hostCredentials.get("csrfToken"))
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

  @Test
  @Timeout(90)
  void runtimeCommandDeepLinkUsesExistingJoinAndAnswerAuthority() throws Exception {
    try (Fixture host = new Fixture("deep-link-host"); Fixture join = new Fixture("deep-link-join")) {
      Map<String, Object> hostCredentials = login(host);
      HttpResponse<String> invitation = host.client.send(
          jsonRequest(host, "/api/v1/commands/invite")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) hostCredentials.get("sessionToken"))
              .header(ControlPlaneHttpHandler.CSRF_HEADER,
                  (String) hostCredentials.get("csrfToken"))
              .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of())))
              .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(201, invitation.statusCode());
      Map<String, Object> invitationBody = object(invitation.body());

      HttpResponse<String> unauthorized = join.client.send(
          jsonRequest(join, "/api/v1/commands/deep-link")
              .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, "wrong")
              .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                  "uri", invitationBody.get("inviteUri")))))
              .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(401, unauthorized.statusCode());

      HttpResponse<String> malformed = join.client.send(
          jsonRequest(join, "/api/v1/commands/deep-link")
              .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER,
                  join.handler.runtimeCommandToken())
              .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                  "uri", "synesis://join/SLO1-invalid")))).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(400, malformed.statusCode());
      assertTrue(malformed.body().contains("INVITE_INVALID"));

      HttpResponse<String> joinResult = join.client.send(
          jsonRequest(join, "/api/v1/commands/deep-link")
              .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER,
                  join.handler.runtimeCommandToken())
              .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
                  "uri", invitationBody.get("inviteUri")))))
              .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(201, joinResult.statusCode());
      Map<String, Object> joinBody = object(joinResult.body());

      Map<String, Object> joinCredentials = login(join);
      HttpResponse<String> pendingSnapshot = join.client.send(
          request(join, "/api/v1/snapshot")
              .header(ControlPlaneHttpHandler.SESSION_HEADER,
                  (String) joinCredentials.get("sessionToken"))
              .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, pendingSnapshot.statusCode());
      Map<String, Object> snapshot = object(pendingSnapshot.body());
      Map<?, ?> onboarding = (Map<?, ?>) snapshot.get("onboarding");
      List<?> pendingJoins = (List<?>) onboarding.get("pendingJoins");
      assertEquals(1, pendingJoins.size());
      Map<?, ?> pendingJoin = (Map<?, ?>) pendingJoins.getFirst();
      assertEquals(joinBody.get("operationId"), pendingJoin.get("operationId"));
      assertEquals("WAITING_FOR_CONNECT", pendingJoin.get("state"));

      HttpRequest answerRequest = jsonRequest(host, "/api/v1/commands/deep-link")
          .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER,
              host.handler.runtimeCommandToken())
          .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
              "uri", joinBody.get("answerUri"))))).build();
      HttpRequest connectRequest = jsonRequest(join, "/api/v1/commands/connect")
          .header(ControlPlaneHttpHandler.SESSION_HEADER,
              (String) joinCredentials.get("sessionToken"))
          .header(ControlPlaneHttpHandler.CSRF_HEADER,
              (String) joinCredentials.get("csrfToken"))
          .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of(
              "operationId", joinBody.get("operationId"))))).build();
      CompletableFuture<HttpResponse<String>> hostResult = host.client.sendAsync(answerRequest,
          HttpResponse.BodyHandlers.ofString());
      CompletableFuture<HttpResponse<String>> joinConnection = join.client.sendAsync(connectRequest,
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, hostResult.get(60, TimeUnit.SECONDS).statusCode());
      assertEquals(200, joinConnection.get(60, TimeUnit.SECONDS).statusCode());

      HttpResponse<String> replay = host.client.send(answerRequest,
          HttpResponse.BodyHandlers.ofString());
      assertEquals(404, replay.statusCode());
      assertTrue(replay.body().contains("OPERATION_NOT_FOUND"));
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
    private final URI baseUri;
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

    private Fixture(String name, boolean seedProject,
        java.util.function.Supplier<ControlPlaneReadModel.NetworkSnapshot> network)
        throws Exception {
      this(name, seedProject, network, null);
    }

    private Fixture(String name, boolean seedProject,
        java.util.function.Supplier<ControlPlaneReadModel.NetworkSnapshot> network,
        ControlPlaneHttpHandler.SelectionGateway selectionGateway)
        throws Exception {
      Path root = temp.resolve(name);
      Path synesis = root.resolve(".synesis");
      Path profile = synesis.resolve("local/profile");
      Files.createDirectories(profile);
      projectId = UUID.randomUUID();
      ProjectApplicationService.ProjectLocation location = new ProjectApplicationService.ProjectLocation(
          root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
      Path coordinationRoot = synesis.resolve("coordination");
      NodeIdentity signer = NodeIdentity.generate();
      if (seedProject) {
        seedProject(coordinationRoot, signer);
      }
      coordination = new CoordinationService(
          new PredictionEventStore(coordinationRoot, projectId), signer);
      eventHub = new ControlPlaneEventHub();
      Onboarding onboarding = new Onboarding(profile, eventHub::publish);
      ControlPlaneReadModel readModel = network == null
          ? new ControlPlaneReadModel(location, coordination,
          new ProviderApplicationService(), new DoctorService())
          : new ControlPlaneReadModel(location, coordination,
              new ProviderApplicationService(), new DoctorService(), network);
      handler = selectionGateway == null
          ? new ControlPlaneHttpHandler(readModel, coordination, onboarding, eventHub)
          : new ControlPlaneHttpHandler(readModel, coordination, onboarding, eventHub,
              selectionGateway);
      server = new CoordinationHttpServer(coordination, new InetSocketAddress("127.0.0.1", 0), null,
          handler);
      activeServer = server;
      server.start();
      baseUri = URI.create("http://127.0.0.1:" + server.address().getPort());
    }

    private void appendPrediction() throws Exception {
      NodeIdentity requester = NodeIdentity.generate();
      UUID prediction = UUID.randomUUID();
      PredictionContract contract = new PredictionContract(prediction, projectId,
          requester.nodeId(),
          "supervisor-a", "worker-a", UUID.randomUUID(), "workspace.control-plane-test",
          requester.nodeId(),
          "supervisor-a", List.of("workspace/**"), 0, "HEAD", List.of("workspace/**=absent"), 0,
          "purpose", "inputs", "outputs", "behavior", "errors", "none", "invariants", "compatible",
          "normal", "single-threaded", List.of("test"), 80, 20, 1_900_000_000_000L);
      CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), projectId,
          prediction,
          PredictionEventType.PREDICTION_CREATED, requester.nodeId(), contract.encoded(),
          requester);
      coordination.submit(command);
    }

    private void seedProject(Path coordinationRoot, NodeIdentity signer) throws Exception {
      PredictionEventStore writer = new PredictionEventStore(coordinationRoot, projectId);
      seededGroupId = UUID.randomUUID();
      new WorkGroupService(writer, signer).create(new WorkGroup(seededGroupId, projectId,
          "http goal", "http acceptance", 1, WorkGroup.Status.ACTIVE));
      seededIntentId = UUID.randomUUID();
      WorkIntent intent = new WorkIntent(seededIntentId, projectId, "agt_http", "codex",
          UUID.randomUUID(), "http implementation", "http tests", "HEAD",
          List.of(ResourceSelector.pathExact("src/App.java")), 1, seededGroupId,
          WorkIntent.defaultAuthorityLineage(seededIntentId), WorkIntent.Status.ANNOUNCED,
          WorkIntent.Role.PRODUCER);
      new WorkIntentService(new PredictionEventStore(coordinationRoot, projectId), signer)
          .announce(intent);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      handler.close();
      server.close();
      activeServer = null;
    }
  }
}
