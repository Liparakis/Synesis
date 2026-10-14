package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.link.protocol.TraversalOffer;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Verifies the daemon server can run against an injected endpoint directory.
 */
final class DaemonServerTest {

    @TempDir
    Path temp;

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
        CandidateDescriptor descriptor = CandidateDescriptor.create(host, issued, expires, java.util.List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"), 4401, 1)));
        SessionInvitation invitation = SessionInvitation.create(host, session, ProtocolVersion.V1, issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(invitation.encoded());
        TraversalOffer offer = TraversalOffer.create(host, null, session, digest, issued, expires, new byte[TraversalOffer.NONCE_BYTES], descriptor);
        return TraversalInvitation.create(invitation, offer).shareLink();
    }

    private static String validCompact() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        java.time.Instant issued = java.time.Instant.now().minusSeconds(1);
        return CompactBootstrapInvitation.createMinimized(host,
                ProtocolVersion.V1,
                issued,
                issued.plusSeconds(600),
                new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
                java.util.List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"), 4401, 1))).shareLink();
    }

    private static String exchange(DaemonEndpoint.Record endpoint, Map<String, Object> request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 1000);
            socket.setSoTimeout(3000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
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

    @Test
    void normalizesRuntimeReadyEndpointBeforeAppendingRoutes() {
        assertEquals(URI.create("http://127.0.0.1:12345"), ProcessRuntimeHandle.normalizeEndpoint(URI.create("http://127.0.0.1:12345/")));
    }

    @Test
    @Timeout(10)
    void recognizesHealthyLoopbackRuntimeThroughTheJdkCompatibleProbe() throws Exception {
        HttpServer runtime = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runtime.createContext("/api/v1/health", exchange -> {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
            exchange.close();
        });
        runtime.start();
        try {
            assertTrue(ProcessRuntimeHandle.runtimeReachable(URI.create("http://127.0.0.1:" + runtime.getAddress().getPort())));
        } finally {
            runtime.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void isolatedEndpointSupportsAuthenticatedBoundedRequestsAndCleansUp() throws Exception {
        Path endpointDirectory = temp.resolve("daemon-state");
        DaemonServer server = DaemonServer.start(endpointDirectory, new KnownProjectRegistry(temp.resolve("registry.json")));
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
            assertEquals("RUNNING", object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "STATUS"))).get("state"));
            assertEquals("AUTH_REQUIRED", object(exchange(endpoint, Map.of("token", "wrong", "operation", "STATUS"))).get("error"));

            Map<String, Object> unresolved = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", validSlo1(), "openBrowser", false)));
            assertTrue(Boolean.TRUE.equals(unresolved.get("ok")));
            assertEquals("URI_UNRESOLVED", unresolved.get("state"));

            Map<String, Object> compactUnresolved = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", validCompact(), "openBrowser", false)));
            assertTrue(Boolean.TRUE.equals(compactUnresolved.get("ok")));
            assertEquals("URI_UNRESOLVED", compactUnresolved.get("state"));

            Map<String, Object> retiredSlb1 = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", "synesis://join/SLB1-AAAA", "openBrowser", false)));
            assertFalse(Boolean.TRUE.equals(retiredSlb1.get("ok")));
            assertEquals("REQUEST_REJECTED", retiredSlb1.get("error"));

            Map<String, Object> rejected = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", "https://example.test/SLO1-test", "openBrowser", false)));
            assertFalse(Boolean.TRUE.equals(rejected.get("ok")));
            assertEquals("REQUEST_REJECTED", rejected.get("error"));
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
        assertFalse(Files.exists(endpointDirectory.resolve("endpoint.json")));
    }

    @Test
    @Timeout(5)
    void requestReadHasOneTotalDeadlineAgainstSlowDripClients() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                Socket accepted = listener.accept()) {
            Thread sender = new Thread(() -> {
                try (var output = client.getOutputStream()) {
                    while (!Thread.currentThread().isInterrupted()) {
                        output.write('x');
                        output.flush();
                        Thread.sleep(40L);
                    }
                } catch (IOException | InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "daemon-slow-drip-test");
            sender.setDaemon(true);
            sender.start();

            assertThrows(java.net.SocketTimeoutException.class, () -> DaemonServer.readBoundedLine(accepted, accepted.getInputStream(), Duration.ofMillis(250)));
            sender.interrupt();
            sender.join(500L);
        }
    }

    @Test
    @Timeout(10)
    void closingDaemonClosesAcceptedPartialRequestSockets() throws Exception {
        Path endpointDirectory = temp.resolve("daemon-shutdown");
        DaemonServer server = DaemonServer.start(endpointDirectory, new KnownProjectRegistry(temp.resolve("shutdown-registry.json")));
        Thread serving = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException ignored) {
                // Closing the test server interrupts accept normally.
            }
        }, "daemon-shutdown-test");
        serving.start();
        try (Socket client = new Socket()) {
            DaemonEndpoint.Record endpoint = awaitEndpoint(endpointDirectory.resolve("endpoint.json"));
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 1000);
            client.setSoTimeout(2000);
            client.getOutputStream().write("{\"token\":".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            server.close();
            // The daemon closes the socket with the partial request still unread. Linux then resets the
            // connection instead of ending the stream, so either one shows that the socket was closed.
            int read;
            try {
                read = client.getInputStream().read();
            } catch (java.net.SocketException reset) {
                read = -1;
            }
            assertEquals(-1, read);
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }
}
