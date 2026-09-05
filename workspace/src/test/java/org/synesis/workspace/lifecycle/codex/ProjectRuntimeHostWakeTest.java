package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Verifies that the existing lifecycle owner performs exact-thread RESUME
 * continuation after durable authority has selected a dormant binding.
 */
class ProjectRuntimeHostWakeTest {

    @TempDir
    Path temp;

    @Test
    void ownerRoutesExactResumeReadAndContinuationTurn() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        TestGit.run(project, "init");
        TestGit.run(project, "config", "user.name", "Wake Owner Test");
        TestGit.run(project, "config", "user.email", "wake-owner@example.test");
        Files.writeString(project.resolve("README.md"), "owner wake fixture\n");
        TestGit.run(project, "add", ".");
        TestGit.run(project, "commit", "-m", "fixture");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(project).location();
        ProviderTestSupport.install(location, "codex");

        AgentSessionService.AgentSessionContext session = new AgentSessionService().resolveSessionContext(
                new AgentSessionService.SessionResolutionRequest(project, "codex", "owner-wake", null, false));
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        bindingService.verifyWorkspaceTrust(location, "codex", session.sessionId(), session.worktreePath());
        ProviderSessionBindingService.Binding binding = bindingService.list(location, "codex").stream()
                .filter(candidate -> candidate.sessionId().equals(session.sessionId()))
                .findFirst().orElseThrow();

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "owner-wake",
                "owner wake lane", "owner wake acceptance",
                List.of(ResourceSelector.pathExact("README.md")));

        CodexLifecycleStateStore stateStore = new CodexLifecycleStateStore(
                CodexWakeAdmissionService.runtimeRoot(location).resolve("bindings"));
        stateStore.write(new CodexLifecycleStateStore.Checkpoint(
                binding.sessionId(), location.projectId().toString(), "codex", 12L,
                CodexLifecycleStateStore.State.COMPLETED, "previous-host", 4L, 5L, -1L, 6L,
                "codex.exe", "codex.exe --app-server", "thread-dormant", "turn-old", null,
                true, System.currentTimeMillis()));

        NodeIdentity owner = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        try (FakeAppServer server = new FakeAppServer();
                ProjectRuntimeHost host = new ProjectRuntimeHost(location, owner,
                        (ignored, generation) -> server.process(), new ProcessTreeTerminator())) {
            CodexLifecycleStateStore.Checkpoint reconciled = stateStore.read(
                    binding.sessionId(), location.projectId().toString());
            Path assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            LifecycleControlRequestEnvelope.AuthorityContext authority =
                    new LifecycleControlRequestEnvelope.AuthorityContext(
                            location.projectId().toString(),
                            location.root().toAbsolutePath().normalize().toString(),
                            "codex",
                            CodexWakeAdmissionService.FINGERPRINT_CONNECTION_PREFIX
                                    + binding.providerInstanceFingerprint(),
                            binding.sessionId(),
                            binding.providerInstanceFingerprint(),
                            binding.bindingVersion(),
                            WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                            claim.intent().intentId().toString(),
                            claim.intent().version(),
                            assigned.toString(),
                            assigned.toRealPath().toString(),
                            binding.gitCommonDir(),
                            binding.branch(),
                            binding.baseCommit(),
                            binding.supervisorId(),
                            binding.workerId());
            LifecycleControlRequestEnvelope request = new LifecycleControlRequestEnvelope(
                    UUID.randomUUID(), host.hostInstanceId(), authority,
                    LifecycleControlRequestEnvelope.Operation.RESUME, reconciled.revision(),
                    "thread-dormant", "turn-old", true,
                    CodexWakeAdmissionService.GET_NEXT_ACTION_INPUT,
                    Instant.now().plusSeconds(20).toEpochMilli(), Map.of());

            CodexLifecycleHttpClient.Response response = host.dispatch(request);

            assertTrue(response.success());
            assertEquals("thread-dormant", response.threadId());
            assertEquals("turn-new", response.turnId());
            assertEquals(List.of("initialize", "thread/resume", "thread/read", "turn/start"),
                    server.methods);
            assertEquals("thread-dormant", server.readThreadId);
            assertEquals("thread-dormant", server.turnThreadId);
            assertTrue(server.turnInput.contains("get_next_action"));

            CodexLifecycleStateStore.Checkpoint afterResume = stateStore.read(
                    binding.sessionId(), location.projectId().toString());
            LifecycleControlRequestEnvelope notify = new LifecycleControlRequestEnvelope(
                    UUID.randomUUID(), host.hostInstanceId(), authority,
                    LifecycleControlRequestEnvelope.Operation.NOTIFY, afterResume.revision(),
                    response.threadId(), response.turnId(), true,
                    CodexWakeAdmissionService.GET_NEXT_ACTION_INPUT,
                    Instant.now().plusSeconds(20).toEpochMilli(), Map.of());

            CodexLifecycleHttpClient.Response notified = host.dispatch(notify);

            assertTrue(notified.success());
            assertEquals("thread-dormant", notified.threadId());
            assertEquals("turn-live", notified.turnId());
            assertEquals(List.of("initialize", "thread/resume", "thread/read", "turn/start", "turn/start"),
                    server.methods);
        }
    }

    private static final class FakeAppServer implements AutoCloseable {
        private final PipedInputStream serverInput;
        private final PipedOutputStream clientOutput;
        private final FakeProcess process;
        private final Thread thread;
        private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
        private volatile String readThreadId;
        private volatile String turnThreadId;
        private volatile String turnInput = "";
        private int turnNumber;

        private FakeAppServer() throws IOException {
            serverInput = new PipedInputStream();
            PipedOutputStream clientInput = new PipedOutputStream(serverInput);
            clientOutput = new PipedOutputStream();
            PipedInputStream clientStdout = new PipedInputStream(clientOutput);
            process = new FakeProcess(clientStdout, clientInput);
            thread = new Thread(() -> serve(serverInput, clientOutput), "fake-codex-owner-wake");
            thread.setDaemon(true);
            thread.start();
        }

        private CodexAppServerLifecycleService.AppServerProcess process() {
            return new CodexAppServerLifecycleService.AppServerProcess(process, "fake-codex",
                    "fake-codex app-server", System.currentTimeMillis());
        }

        private void serve(InputStream input, OutputStream output) {
            try (BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Map<String, Object> request = map(ProviderJson.parse(line));
                    String method = String.valueOf(request.get("method"));
                    if ("initialized".equals(method)) {
                        continue;
                    }
                    methods.add(method);
                    String id = String.valueOf(request.get("id"));
                    Map<String, Object> result = new LinkedHashMap<>();
                    if ("thread/resume".equals(method)) {
                        result.put("thread", Map.of("id", "thread-dormant"));
                    } else if ("thread/read".equals(method)) {
                        readThreadId = textParam(request, "threadId");
                        result.put("threadId", readThreadId);
                        result.put("status", "completed");
                    } else if ("turn/start".equals(method)) {
                        turnThreadId = textParam(request, "threadId");
                        turnInput = ProviderJson.write(request.get("params"));
                        turnNumber++;
                        result.put("turn", Map.of("id", turnNumber == 1 ? "turn-new" : "turn-live",
                                "status", "inProgress"));
                    } else if ("turn/interrupt".equals(method)) {
                        result.put("turn", Map.of("id", "turn-new", "status", "interrupted"));
                    }
                    write(output, Map.of("id", id, "result", result));
                    if ("turn/started".equals(method)) {
                        continue;
                    }
                    if ("turn/start".equals(method)) {
                        String turnId = turnNumber == 1 ? "turn-new" : "turn-live";
                        write(output, Map.of("method", "turn/started", "params",
                                Map.of("threadId", "thread-dormant",
                                        "turn", Map.of("id", turnId, "status", "inProgress"))));
                        write(output, Map.of("method", "turn/completed", "params",
                                Map.of("threadId", "thread-dormant",
                                        "turn", Map.of("id", turnId, "status", "completed"))));
                    } else if ("turn/interrupt".equals(method)) {
                        write(output, Map.of("method", "turn/completed", "params",
                                Map.of("threadId", "thread-dormant",
                                        "turn", Map.of("id", "turn-live", "status", "interrupted"))));
                    }
                }
            } catch (IOException ignored) {
                // Fixture shutdown.
            }
        }

        private static String textParam(Map<String, Object> request, String name) {
            Object params = request.get("params");
            if (!(params instanceof Map<?, ?> raw)) {
                return "";
            }
            Object value = raw.get(name);
            return value == null ? "" : String.valueOf(value);
        }

        private static void write(OutputStream output, Map<String, ?> value) throws IOException {
            output.write((ProviderJson.write(value) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object value) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }

        @Override
        public void close() throws IOException {
            process.destroy();
            serverInput.close();
            clientOutput.close();
            try {
                thread.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final OutputStream stdin;
        private volatile boolean alive = true;

        private FakeProcess(InputStream stdout, OutputStream stdin) {
            this.stdout = stdout;
            this.stdin = stdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return 12345L;
        }
    }
}
