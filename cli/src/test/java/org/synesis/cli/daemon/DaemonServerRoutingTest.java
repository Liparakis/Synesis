package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.ReturnTarget;
import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.link.protocol.TraversalOffer;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/** Verifies deterministic target routing and the local selection handoff. */
final class DaemonServerRoutingTest {

  @TempDir
  Path temp;

  @Test
  @Timeout(15)
  void v2AnswerRoutesOnlyToSignedTargetAndUnknownTargetFailsClosed() throws Exception {
    ProjectApplicationService service = new ProjectApplicationService();
    var first = service.init(Files.createDirectories(temp.resolve("one")), false).location();
    var second = service.init(Files.createDirectories(temp.resolve("two")), false).location();
    KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry.json"));
    registry.observe(first);
    registry.observe(second);
    FakeStarter starter = new FakeStarter();
    DaemonServer server = DaemonServer.start(temp.resolve("daemon"), registry, starter,
        new LocalProjectSelectionStore());
    Thread serving = serve(server);
    try {
      DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("daemon/endpoint.json"));
      String routed = v2Answer(first.projectId());
      Map<String, Object> result = object(exchange(endpoint, Map.of("token", endpoint.token(),
          "operation", "OPEN_URI", "uri", routed)));
      assertTrue(Boolean.TRUE.equals(result.get("ok")));
      assertEquals(List.of(routed), starter.runtime(first.root()).uris);
      assertTrue(starter.runtime(second.root()).uris.isEmpty());

      String unknown = v2Answer(UUID.randomUUID());
      Map<String, Object> unresolved = object(exchange(endpoint, Map.of("token", endpoint.token(),
          "operation", "OPEN_URI", "uri", unknown)));
      assertEquals("URI_UNRESOLVED", unresolved.get("state"));
      assertTrue(starter.runtime(second.root()).uris.isEmpty());
    } finally {
      server.close();
      serving.join(TimeUnit.SECONDS.toMillis(3));
    }
  }

  @Test
  void twoEligibleProjectsCreateSelectionAndDispatchOnlyAfterValidChoice() throws Exception {
    ProjectApplicationService service = new ProjectApplicationService();
    var first = service.init(Files.createDirectories(temp.resolve("one")), false).location();
    var second = service.init(Files.createDirectories(temp.resolve("two")), false).location();
    KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry.json"));
    registry.observe(first);
    registry.observe(second);
    FakeStarter starter = new FakeStarter();
    DaemonServer server = DaemonServer.start(temp.resolve("daemon"), registry, starter,
        new LocalProjectSelectionStore());
    Thread serving = serve(server);
    try {
      DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("daemon/endpoint.json"));
      String invitation = validSlo1();
      Map<String, Object> required = object(exchange(endpoint, Map.of("token", endpoint.token(),
          "operation", "OPEN_URI", "uri", invitation)));
      assertEquals("PROJECT_SELECTION_REQUIRED", required.get("state"));
      assertTrue(starter.allUris().isEmpty());

      UUID selectionId = UUID.fromString((String) required.get("selectionId"));
      Map<String, Object> description = server.describeSelection(selectionId);
      assertEquals("PROJECT_SELECTION_REQUIRED", description.get("state"));
      Map<String, Object> selected = server.selectProject(selectionId, second.projectId());
      assertTrue(Boolean.TRUE.equals(selected.get("ok")));
      assertEquals(List.of(invitation), starter.runtime(second.root()).uris);
      assertTrue(starter.runtime(first.root()).uris.isEmpty());
    } finally {
      server.close();
      serving.join(TimeUnit.SECONDS.toMillis(3));
    }
  }

  @Test
  @Timeout(15)
  void oneEligibleProjectStartsAndReceivesSlo1Directly() throws Exception {
    ProjectApplicationService service = new ProjectApplicationService();
    var project = service.init(Files.createDirectories(temp.resolve("one")), false).location();
    KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry.json"));
    registry.observe(project);
    FakeStarter starter = new FakeStarter();
    DaemonServer server = DaemonServer.start(temp.resolve("daemon"), registry, starter,
        new LocalProjectSelectionStore());
    Thread serving = serve(server);
    try {
      DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("daemon/endpoint.json"));
      String invitation = validSlo1();
      Map<String, Object> result = object(exchange(endpoint, Map.of("token", endpoint.token(),
          "operation", "OPEN_URI", "uri", invitation)));
      assertTrue(Boolean.TRUE.equals(result.get("ok")));
      assertEquals(List.of(invitation), starter.runtime(project.root()).uris);
    } finally {
      server.close();
      serving.join(TimeUnit.SECONDS.toMillis(3));
    }
  }

  private static String v2Answer(UUID projectId) throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    NodeIdentity joiner = NodeIdentity.generate();
    Instant issued = Instant.now().minusSeconds(2);
    Instant expires = issued.plusSeconds(120);
    UUID session = UUID.randomUUID();
    CandidateDescriptor hostDescriptor = descriptor(host, issued, expires, 4401);
    CandidateDescriptor joinerDescriptor = descriptor(joiner, issued, expires, 4402);
    SessionInvitation invitation = SessionInvitation.create(host, session, ProtocolVersion.V1,
        issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], hostDescriptor);
    TraversalOffer offer = TraversalOffer.createV2(host, null, session,
        digest(invitation.encoded()), issued, expires, new byte[TraversalOffer.NONCE_BYTES],
        hostDescriptor, ReturnTarget.of(projectId));
    return TraversalAnswer.create(joiner, offer, joinerDescriptor,
        new byte[TraversalAnswer.NONCE_BYTES]).shareLink();
  }

  private static String validSlo1() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    Instant issued = Instant.now().minusSeconds(2);
    Instant expires = issued.plusSeconds(120);
    UUID session = UUID.randomUUID();
    CandidateDescriptor descriptor = descriptor(host, issued, expires, 4401);
    SessionInvitation invitation = SessionInvitation.create(host, session, ProtocolVersion.V1,
        issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
    TraversalOffer offer = TraversalOffer.create(host, null, session,
        digest(invitation.encoded()), issued, expires, new byte[TraversalOffer.NONCE_BYTES],
        descriptor);
    return TraversalInvitation.create(invitation, offer).shareLink();
  }

  private static CandidateDescriptor descriptor(NodeIdentity identity, Instant issued,
      Instant expires, int port) throws Exception {
    return CandidateDescriptor.create(identity, issued, expires,
        List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"),
            port, 1)));
  }

  private static byte[] digest(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static Thread serve(DaemonServer server) {
    Thread serving = new Thread(() -> {
      try {
        server.serve();
      } catch (IOException ignored) {
        // Closing the daemon interrupts accept.
      }
    }, "daemon-routing-test");
    serving.start();
    return serving;
  }

  private static DaemonEndpoint.Record awaitEndpoint(Path endpointFile) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      try {
        return DaemonEndpoint.read(endpointFile);
      } catch (IOException notReady) {
        Thread.sleep(20L);
      }
    }
    throw new IOException("test daemon endpoint did not become ready");
  }

  private static String exchange(DaemonEndpoint.Record endpoint, Map<String, Object> request)
      throws Exception {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 1000);
      socket.setSoTimeout(3000);
      try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
          socket.getOutputStream(), StandardCharsets.UTF_8));
          BufferedReader reader = new BufferedReader(new InputStreamReader(
              socket.getInputStream(), StandardCharsets.UTF_8))) {
        writer.write(ProviderJson.write(request));
        writer.newLine();
        writer.flush();
        return reader.readLine();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(String json) {
    return (Map<String, Object>) ProviderJson.parse(json);
  }

  private static final class FakeStarter implements DaemonServer.RuntimeStarter {
    private final Map<Path, FakeRuntime> runtimes = new HashMap<>();

    @Override
    public DaemonServer.RuntimeHandle start(Path project) {
      return runtime(project);
    }

    private FakeRuntime runtime(Path project) {
      return runtimes.computeIfAbsent(project.toAbsolutePath().normalize(),
          path -> new FakeRuntime(path));
    }

    private List<String> allUris() {
      return runtimes.values().stream().flatMap(runtime -> runtime.uris.stream()).toList();
    }
  }

  private static final class FakeRuntime implements DaemonServer.RuntimeHandle {
    private final Path project;
    private final List<String> uris = new ArrayList<>();

    private FakeRuntime(Path project) {
      this.project = project;
    }

    @Override
    public boolean reachable() {
      return true;
    }

    @Override
    public URI uiUri() {
      return URI.create("http://127.0.0.1:1/");
    }

    @Override
    public Map<String, Object> dispatchUri(String uri) {
      uris.add(uri);
      return new HashMap<>(Map.of("ok", true, "project", project.toString()));
    }

    @Override
    public URI endpoint() {
      return URI.create("http://127.0.0.1:1/" + project.getFileName());
    }

    @Override
    public void close() {
    }
  }
}
