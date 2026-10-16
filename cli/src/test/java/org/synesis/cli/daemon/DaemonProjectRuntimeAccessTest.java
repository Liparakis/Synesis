package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * A local client that holds the daemon's token can get a project runtime's endpoint and command token, so that
 * {@code synesis link} drives the runtime that owns the Link sessions, and can stop that runtime so it starts again
 * with the project's current replication role (ADR-0098).
 */
@Timeout(20)
final class DaemonProjectRuntimeAccessTest {

    @TempDir
    Path temp;

    private static Map<String, Object> exchange(DaemonEndpoint.Record endpoint, Map<String, Object> request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 1000);
            socket.setSoTimeout(3000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(ProviderJson.write(request));
                writer.newLine();
                writer.flush();
                @SuppressWarnings("unchecked") Map<String, Object> answer = (Map<String, Object>) ProviderJson.parse(reader.readLine());
                return answer;
            }
        }
    }

    @Test
    void theRuntimeIsStartedForItsEndpointAndTokenAndStoppedOnlyWhenOneRuns() throws Exception {
        Path project = new ProjectApplicationService().init(Files.createDirectories(temp.resolve("one")), false).location().root();
        List<Runtime> started = new ArrayList<>();
        DaemonServer server = DaemonServer.start(temp.resolve("daemon"), new KnownProjectRegistry(temp.resolve("registry.json")), path -> {
            Runtime runtime = new Runtime();
            started.add(runtime);
            return runtime;
        }, new LocalProjectSelectionStore());
        Thread serving = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException ignored) {
                // Closing the daemon interrupts accept.
            }
        });
        serving.start();
        try {
            DaemonEndpoint.Record endpoint = null;
            for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); endpoint == null && System.nanoTime() < deadline; ) {
                try {
                    endpoint = DaemonEndpoint.read(temp.resolve("daemon/endpoint.json"));
                } catch (IOException notReady) {
                    Thread.sleep(20);
                }
            }
            String token = endpoint.token();
            Map<String, Object> stopWhenNone = exchange(endpoint, Map.of("token", token, "operation", "STOP_PROJECT_RUNTIME", "project", project.toString()));
            assertEquals("NOT_RUNNING", stopWhenNone.get("state"));
            assertEquals(0, started.size());

            Map<String, Object> access = exchange(endpoint, Map.of("token", token, "operation", "PROJECT_RUNTIME", "project", project.toString()));
            assertEquals("READY", access.get("state"));
            assertEquals("http://127.0.0.1:1/runtime", access.get("endpoint"));
            assertEquals("runtime-command-token", access.get("commandToken"));
            assertEquals(1, started.size());
            exchange(endpoint, Map.of("token", token, "operation", "PROJECT_RUNTIME", "project", project.toString()));
            assertEquals(1, started.size(), "a runtime that runs is reused");

            assertEquals("STOPPED", exchange(endpoint, Map.of("token", token, "operation", "STOP_PROJECT_RUNTIME", "project", project.toString())).get("state"));
            assertEquals(1, started.getFirst().closed);
            exchange(endpoint, Map.of("token", token, "operation", "PROJECT_RUNTIME", "project", project.toString()));
            assertEquals(2, started.size(), "the next use starts it again");

            Map<String, Object> noProject = exchange(endpoint, Map.of("token", token, "operation", "PROJECT_RUNTIME"));
            assertFalse(Boolean.TRUE.equals(noProject.get("ok")));
            assertNull(noProject.get("commandToken"));
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    private static final class Runtime implements DaemonServer.RuntimeHandle {

        private int closed;

        @Override
        public boolean reachable() {
            return closed == 0;
        }

        @Override
        public URI uiUri() {
            return URI.create("http://127.0.0.1:1/");
        }

        @Override
        public Map<String, Object> dispatchUri(String uri) {
            return new HashMap<>(Map.of("ok", true));
        }

        @Override
        public URI endpoint() {
            return URI.create("http://127.0.0.1:1/runtime");
        }

        @Override
        public String commandToken() {
            return "runtime-command-token";
        }

        @Override
        public void close() {
            closed++;
        }
    }
}
