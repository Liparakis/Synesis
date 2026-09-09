package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

class LinkRuntimeOwnerControlPlaneTest {

  @TempDir
  Path temp;

  private static void assertOverlay(Map<String, Object> network, String localNodeId,
      String peerNodeId) {
    @SuppressWarnings("unchecked")
    Map<String, Object> overlay = (Map<String, Object>) network.get("overlay");
    assertEquals("CURRENT", overlay.get("status"));
    assertEquals(2, ((Number) overlay.get("memberCount")).intValue());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> members = (List<Map<String, Object>>) overlay.get("members");
    assertTrue(members.stream().anyMatch(member -> peerNodeId.equals(member.get("nodeId"))));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> directEdges = (List<Map<String, Object>>) overlay.get("directEdges");
    assertTrue(directEdges.stream().anyMatch(edge -> localNodeId.equals(edge.get("from"))
        && peerNodeId.equals(edge.get("to")) && "DIRECT".equals(edge.get("status"))));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> routes = (List<Map<String, Object>>) network.get("routes");
    assertTrue(routes.stream().anyMatch(route -> peerNodeId.equals(route.get("destinationNodeId"))
        && "DIRECT".equals(route.get("kind"))));
  }

  private static NodeIdentity identity(Path root) throws Exception {
    return new IdentityBootstrap(root.resolve(".synesis/local/profile/link")).loadOrCreate()
        .identity();
  }

  private static Map<String, Object> login(RuntimeFixture fixture) throws Exception {
    HttpResponse<String> response = fixture.client.send(json(fixture, "/api/v1/session")
            .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(
                Map.of("bootstrapToken", fixture.handler.bootstrapToken())))).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(201, response.statusCode());
    return object(response.body());
  }

  private static Map<String, Object> command(RuntimeFixture fixture, Map<String, Object> session,
      String name, Map<String, Object> body) throws Exception {
    HttpResponse<String> response = fixture.client.send(
        commandRequest(fixture, session, name, body),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(name.equals("invite") || name.equals("join") ? 201 : 200, response.statusCode(),
        response.body());
    return object(response.body());
  }

  private static HttpRequest commandRequest(RuntimeFixture fixture, Map<String, Object> session,
      String name, Map<String, Object> body) {
    return json(fixture, "/api/v1/commands/" + name)
        .header(ControlPlaneHttpHandler.SESSION_HEADER, (String) session.get("sessionToken"))
        .header(ControlPlaneHttpHandler.CSRF_HEADER, (String) session.get("csrfToken"))
        .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))).build();
  }

  private static Map<String, Object> network(RuntimeFixture fixture, Map<String, Object> session)
      throws Exception {
    HttpResponse<String> response = fixture.client.send(
        HttpRequest.newBuilder(fixture.baseUri.resolve("/api/v1/network"))
            .header(ControlPlaneHttpHandler.SESSION_HEADER, (String) session.get("sessionToken"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) object(response.body()).get("network");
    return body;
  }

  private static HttpRequest.Builder json(RuntimeFixture fixture, String path) {
    return HttpRequest.newBuilder(fixture.baseUri.resolve(path))
        .header("Content-Type", "application/json; charset=utf-8");
  }

  private static Map<String, Object> object(String json) {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) ProviderJson.parse(json);
    return result;
  }

  @Test
  @Timeout(120)
  void retainedHttpSessionsProjectVerifiedMembershipAndDirectAdjacency() throws Exception {
    UUID projectId = UUID.randomUUID();
    Path hostRoot = temp.resolve("host");
    Path joinRoot = temp.resolve("join");
    NodeIdentity hostIdentity = identity(hostRoot);
    NodeIdentity joinIdentity = identity(joinRoot);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(projectId, 1,
        now.minusSeconds(1), now.plusSeconds(300), List.of(
            new OverlayMembershipSnapshot.Member(hostIdentity.nodeId(),
                OverlayMembershipSnapshot.STATUS_ACTIVE, hostIdentity.publicKeyEncoded()),
            new OverlayMembershipSnapshot.Member(joinIdentity.nodeId(),
                OverlayMembershipSnapshot.STATUS_ACTIVE, joinIdentity.publicKeyEncoded())),
        hostIdentity);

    try (RuntimeFixture host = new RuntimeFixture(hostRoot, projectId, hostIdentity, membership);
        RuntimeFixture join = new RuntimeFixture(joinRoot, projectId, joinIdentity, membership)) {
      Map<String, Object> hostSession = login(host);
      Map<String, Object> joinSession = login(join);
      Map<String, Object> invitation = command(host, hostSession, "invite", Map.of(
          "expectedPeer", joinIdentity.nodeId()));
      Map<String, Object> answer = command(join, joinSession, "join", Map.of(
          "inviteUri", invitation.get("inviteUri")));

      HttpRequest hostAnswer = commandRequest(host, hostSession, "answer", Map.of(
          "operationId", invitation.get("operationId"), "answerUri", answer.get("answerUri")));
      HttpRequest joinConnect = commandRequest(join, joinSession, "connect", Map.of(
          "operationId", answer.get("operationId")));
      CompletableFuture<HttpResponse<String>> hostResult = host.client.sendAsync(hostAnswer,
          HttpResponse.BodyHandlers.ofString());
      CompletableFuture<HttpResponse<String>> joinResult = join.client.sendAsync(joinConnect,
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, hostResult.get(90, TimeUnit.SECONDS).statusCode());
      assertEquals(200, joinResult.get(90, TimeUnit.SECONDS).statusCode());

      Map<String, Object> hostNetwork = network(host, hostSession);
      Map<String, Object> joinNetwork = network(join, joinSession);
      assertEquals("HEALTHY", hostNetwork.get("status"));
      assertEquals("HEALTHY", joinNetwork.get("status"));
      assertEquals(1, ((List<?>) hostNetwork.get("peers")).size());
      assertEquals(1, ((List<?>) joinNetwork.get("peers")).size());
      assertOverlay(hostNetwork, hostIdentity.nodeId(), joinIdentity.nodeId());
      assertOverlay(joinNetwork, joinIdentity.nodeId(), hostIdentity.nodeId());
    }
  }

  private static final class RuntimeFixture implements AutoCloseable {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ControlPlaneHttpHandler handler;
    private final CoordinationHttpServer server;
    private final LinkRuntimeOwner owner;
    private final URI baseUri;

    private RuntimeFixture(Path root, UUID projectId, NodeIdentity identity,
        OverlayMembershipSnapshot membership) throws Exception {
      Path synesis = root.resolve(".synesis");
      Path profile = synesis.resolve("local/profile");
      Files.createDirectories(profile);
      ProjectApplicationService.ProjectLocation location = new ProjectApplicationService.ProjectLocation(
          root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
      CoordinationService coordination = new CoordinationService(
          new PredictionEventStore(synesis.resolve("coordination"), projectId), identity);
      ControlPlaneEventHub eventHub = new ControlPlaneEventHub();
      Onboarding onboarding = new Onboarding(profile.resolve("link"), eventHub::publish);
      owner = new LinkRuntimeOwner(onboarding, identity, Optional.of(membership),
          eventHub::publish);
      ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, coordination,
          new ProviderApplicationService(), new DoctorService(), new LinkNetworkProjection(owner));
      handler = new ControlPlaneHttpHandler(readModel, coordination, owner, eventHub);
      server = new CoordinationHttpServer(coordination, new InetSocketAddress("127.0.0.1", 0), null,
          handler);
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
