package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.link.protocol.TraversalOffer;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/** Verifies the daemon server can run against an injected endpoint directory. */
final class DaemonServerTest {

  @TempDir
  Path temp;

  @Test
  void normalizesRuntimeReadyEndpointBeforeAppendingRoutes() {
    assertEquals(URI.create("http://127.0.0.1:12345"),
        DaemonServer.normalizeRuntimeEndpoint(URI.create("http://127.0.0.1:12345/")));
  }

  @Test
  @Timeout(10)
  void isolatedEndpointSupportsAuthenticatedBoundedRequestsAndCleansUp() throws Exception {
    Path endpointDirectory = temp.resolve("daemon-state");
    DaemonServer server = DaemonServer.start(endpointDirectory,
        new KnownProjectRegistry(temp.resolve("registry.json")));
    Thread serving = new Thread(() -> {
      try {
        server.serve();
      } catch (IOException ignored) {
        // Closing the test server interrupts accept normally.
      }
    }, "daemon-server-test");
    serving.start();
    try {
      DaemonEndpoint.Record endpoint = awaitEndpoint(endpointDirectory.resolve("endpoint.json"));
      assertEquals("RUNNING", object(exchange(endpoint,
          Map.of("token", endpoint.token(), "operation", "STATUS"))).get("state"));
      assertEquals("AUTH_REQUIRED", object(exchange(endpoint,
          Map.of("token", "wrong", "operation", "STATUS"))).get("error"));

      Map<String, Object> unresolved = object(exchange(endpoint, Map.of(
          "token", endpoint.token(), "operation", "OPEN_URI",
          "uri", validSlo1())));
      assertTrue(Boolean.TRUE.equals(unresolved.get("ok")));
      assertEquals("URI_UNRESOLVED", unresolved.get("state"));

      Map<String, Object> rejected = object(exchange(endpoint, Map.of(
          "token", endpoint.token(), "operation", "OPEN_URI",
          "uri", "https://example.test/SLO1-test")));
      assertFalse(Boolean.TRUE.equals(rejected.get("ok")));
      assertEquals("REQUEST_REJECTED", rejected.get("error"));
    } finally {
      server.close();
      serving.join(TimeUnit.SECONDS.toMillis(3));
    }
    assertFalse(Files.exists(endpointDirectory.resolve("endpoint.json")));
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

  private static String validSlo1() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    java.time.Instant issued = java.time.Instant.parse("2026-09-08T08:00:00Z");
    java.time.Instant expires = issued.plusSeconds(60);
    java.util.UUID session = java.util.UUID.randomUUID();
    CandidateDescriptor descriptor = CandidateDescriptor.create(host, issued, expires,
        java.util.List.of(new Candidate(CandidateType.MANUAL,
            InetAddress.getByName("198.51.100.1"), 4401, 1)));
    SessionInvitation invitation = SessionInvitation.create(host, session, ProtocolVersion.V1,
        issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(invitation.encoded());
    TraversalOffer offer = TraversalOffer.create(host, null, session, digest, issued, expires,
        new byte[TraversalOffer.NONCE_BYTES], descriptor);
    return TraversalInvitation.create(invitation, offer).shareLink();
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
}
