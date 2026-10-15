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
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.CompactAnswer;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Verifies deterministic target routing and the local selection handoff.
 */
final class DaemonServerRoutingTest {

    @TempDir
    Path temp;

    private static CompactBootstrapInvitation invitation() throws Exception {
        java.time.Instant issued = java.time.Instant.now().minusSeconds(2);
        return CompactBootstrapInvitation.createMinimized(NodeIdentity.generate(), ProtocolVersion.V1, issued, issued.plusSeconds(120),
                new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES], List.of(candidate(4401)));
    }

    private static String answer(CompactBootstrapInvitation invitation) throws Exception {
        return CompactAnswer.create(invitation, List.of(candidate(4402))).shareLink();
    }

    private static Candidate candidate(int port) throws Exception {
        return new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"), port, 1);
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
    @Timeout(15)
    void anAnswerGoesToTheOneRunningRuntimeAndIsUnresolvedWithoutOne() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        var project = service.init(Files.createDirectories(temp.resolve("one")), false).location();
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry.json"));
        registry.observe(project);
        FakeStarter starter = new FakeStarter();
        DaemonServer server = DaemonServer.start(temp.resolve("daemon"), registry, starter, new LocalProjectSelectionStore());
        Thread serving = serve(server);
        try {
            DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("daemon/endpoint.json"));
            CompactBootstrapInvitation invitation = invitation();
            String answer = answer(invitation);
            Map<String, Object> unresolved = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", answer, "openBrowser", false)));
            assertEquals("URI_UNRESOLVED", unresolved.get("state"));

            assertTrue(Boolean.TRUE.equals(object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", invitation.shareLink(), "openBrowser", false))).get("ok")));
            Map<String, Object> routed = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", answer, "openBrowser", false)));
            assertTrue(Boolean.TRUE.equals(routed.get("ok")));
            assertEquals(List.of(invitation.shareLink(), answer), starter.runtime(project.root()).uris);
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
        DaemonServer server = DaemonServer.start(temp.resolve("daemon"), registry, starter, new LocalProjectSelectionStore());
        Thread serving = serve(server);
        try {
            DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("daemon/endpoint.json"));
            String invitation = invitation().shareLink();
            Map<String, Object> required = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", invitation, "openBrowser", false)));
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
    void oneEligibleProjectStartsAndReceivesAnInvitationDirectly() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        var project = service.init(Files.createDirectories(temp.resolve("one")), false).location();
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry.json"));
        registry.observe(project);
        FakeStarter starter = new FakeStarter();
        DaemonServer server = DaemonServer.start(temp.resolve("daemon"), registry, starter, new LocalProjectSelectionStore());
        Thread serving = serve(server);
        try {
            DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("daemon/endpoint.json"));
            String invitation = invitation().shareLink();
            Map<String, Object> result = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", invitation, "openBrowser", false)));
            assertTrue(Boolean.TRUE.equals(result.get("ok")));
            assertEquals(List.of(invitation), starter.runtime(project.root()).uris);
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    @Test
    @Timeout(15)
    void statusClosesAndRemovesAnUnreachableRuntimeHandle() throws Exception {
        Path project = Files.createDirectories(temp.resolve("status-project"));
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("status-registry.json"));
        FakeStarter starter = new FakeStarter();
        DaemonServer server = DaemonServer.start(temp.resolve("status-daemon"), registry, starter, new LocalProjectSelectionStore());
        Thread serving = serve(server);
        try {
            DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("status-daemon/endpoint.json"));
            // This test is about runtime handles. Opening a real browser would make it depend on the machine having one.
            Map<String, Object> opened = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_PROJECT", "project", project.toString(), "openBrowser", false)));
            assertEquals("READY", opened.get("state"));

            FakeRuntime runtime = starter.runtime(project);
            runtime.reachable = false;
            Map<String, Object> status = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "STATUS")));

            assertEquals(0L, ((Number) status.get("runtimeCount")).longValue());
            assertEquals(1, runtime.closeCount);
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    /** {@code synesis ui} run outside a project opens on a project the installation knows. */
    @Test
    @Timeout(15)
    void openingTheUiWithoutAProjectUsesAKnownProjectAndTheHomeWorkspaceWhenThereIsNone() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("ui-registry.json"));
        FakeStarter starter = new FakeStarter();
        Path home = temp.resolve("ui-home-workspace");
        boolean[] homeAvailable = {false};
        DaemonServer server = DaemonServer.start(temp.resolve("ui-daemon"), registry, starter, new LocalProjectSelectionStore(), () -> {
            if (!homeAvailable[0]) {
                throw new IOException("home workspace fixture is unavailable");
            }
            return home;
        });
        Thread serving = serve(server);
        try {
            DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("ui-daemon/endpoint.json"));
            Map<String, Object> openUi = Map.of("token", endpoint.token(), "operation", "OPEN_UI", "openBrowser", false);

            Map<String, Object> none = object(exchange(endpoint, openUi));
            assertEquals(false, none.get("ok"));
            assertEquals("NO_KNOWN_PROJECT", none.get("error"));

            // With no project known, the page is served from the installation's own workspace.
            homeAvailable[0] = true;
            Map<String, Object> fromHome = object(exchange(endpoint, openUi));
            assertEquals("READY", fromHome.get("state"));
            assertEquals(java.util.Set.of(home.toAbsolutePath().normalize()), starter.runtimes.keySet());
            assertEquals(List.of(), registry.projects(), "the home workspace was recorded as a known project");

            var first = service.init(Files.createDirectories(temp.resolve("ui-one")), false).location();
            var second = service.init(Files.createDirectories(temp.resolve("ui-two")), false).location();
            registry.observe(first);
            registry.observe(second);
            // Once a project is known it serves the page, even while the home runtime is still up.
            Map<String, Object> opened = object(exchange(endpoint, openUi));
            assertEquals("READY", opened.get("state"));
            assertEquals(2, starter.runtimes.size(), "more than one project runtime was started to serve one UI");
            Object served = opened.get("endpoint");
            assertTrue(!served.equals(fromHome.get("endpoint")), "the home workspace kept serving after a project became known");

            // A runtime that is already up keeps serving; a named project still gets its own.
            Map<String, Object> other = object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_UI", "openBrowser", false, "project",
                    (starter.runtimes.containsKey(first.root()) ? second : first).root().toString())));
            assertEquals("READY", other.get("state"));
            assertEquals(3, starter.runtimes.size());
            assertEquals(served, object(exchange(endpoint, openUi)).get("endpoint"));
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    /** {@code synesis://ui} typed into a browser opens the UI; it is never treated as an invitation. */
    @Test
    @Timeout(15)
    void aUiAddressOpensTheUiOnAKnownProject() throws Exception {
        for (String address : List.of("synesis://ui", "synesis://ui/", "SYNESIS://UI", "synesis://open", "synesis://", "synesis:")) {
            assertTrue(DaemonServer.isUiUri(address), address);
        }
        for (String other : List.of("synesis://join/SLO1-x", "synesis://ui/extra", "synesis://ui?x=1", "https://ui", "synesis://uix")) {
            assertTrue(!DaemonServer.isUiUri(other), other);
        }
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("address-registry.json"));
        FakeStarter starter = new FakeStarter();
        DaemonServer server = DaemonServer.start(temp.resolve("address-daemon"), registry, starter, new LocalProjectSelectionStore(), () -> {
            throw new IOException("home workspace fixture is unavailable");
        });
        Thread serving = serve(server);
        try {
            DaemonEndpoint.Record endpoint = awaitEndpoint(temp.resolve("address-daemon/endpoint.json"));
            Map<String, Object> open = Map.of("token", endpoint.token(), "operation", "OPEN_URI", "uri", "synesis://ui", "openBrowser", false);

            Map<String, Object> none = object(exchange(endpoint, open));
            assertEquals("URI_UNRESOLVED", none.get("state"));
            assertEquals("NO_KNOWN_PROJECT", none.get("reason"));

            registry.observe(new ProjectApplicationService().init(Files.createDirectories(temp.resolve("address-project")), false).location());
            assertEquals("READY", object(exchange(endpoint, open)).get("state"));
            assertEquals(List.of(), starter.allUris(), "a UI address was handed to a runtime as a deep link");
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    /** The installer stops the daemon before it replaces or removes the files it runs from. */
    @Test
    @Timeout(15)
    void shutdownAnswersThenStopsTheDaemonAndItsRuntimes() throws Exception {
        Path project = Files.createDirectories(temp.resolve("stop-project"));
        FakeStarter starter = new FakeStarter();
        DaemonServer server = DaemonServer.start(temp.resolve("stop-daemon"), new KnownProjectRegistry(temp.resolve("stop-registry.json")), starter, new LocalProjectSelectionStore());
        Thread serving = serve(server);
        try {
            Path endpointFile = temp.resolve("stop-daemon/endpoint.json");
            DaemonEndpoint.Record endpoint = awaitEndpoint(endpointFile);
            exchange(endpoint, Map.of("token", endpoint.token(), "operation", "OPEN_PROJECT", "project", project.toString(), "openBrowser", false));

            Map<String, Object> refused = object(exchange(endpoint, Map.of("token", "wrong", "operation", "SHUTDOWN")));
            assertEquals("AUTH_REQUIRED", refused.get("error"));
            assertEquals(0, starter.runtime(project).closeCount);

            assertEquals("STOPPING", object(exchange(endpoint, Map.of("token", endpoint.token(), "operation", "SHUTDOWN"))).get("state"));
            serving.join(TimeUnit.SECONDS.toMillis(5));
            assertTrue(!serving.isAlive(), "the daemon kept serving after shutdown");
            // Serving ends when the listener closes; the rest of the shutdown follows on the worker.
            for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); Files.exists(endpointFile) && System.nanoTime() < deadline; ) {
                Thread.sleep(20L);
            }
            assertTrue(!Files.exists(endpointFile), "a stopped daemon left its endpoint file behind");
            assertEquals(1, starter.runtime(project).closeCount);
        } finally {
            server.close();
            serving.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    private static final class FakeStarter implements DaemonServer.RuntimeStarter {

        private final Map<Path, FakeRuntime> runtimes = new HashMap<>();

        @Override
        public DaemonServer.RuntimeHandle start(Path project) {
            return runtime(project);
        }

        private FakeRuntime runtime(Path project) {
            return runtimes.computeIfAbsent(project.toAbsolutePath().normalize(), path -> new FakeRuntime(path));
        }

        private List<String> allUris() {
            return runtimes.values().stream().flatMap(runtime -> runtime.uris.stream()).toList();
        }
    }

    private static final class FakeRuntime implements DaemonServer.RuntimeHandle {

        private final Path project;
        private final List<String> uris = new ArrayList<>();
        private boolean reachable = true;
        private int closeCount;

        private FakeRuntime(Path project) {
            this.project = project;
        }

        @Override
        public boolean reachable() {
            return reachable;
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
            closeCount++;
        }
    }
}
