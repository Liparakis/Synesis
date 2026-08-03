package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/** Proves a long WAIT does not prevent STEER or INTERRUPT control. */
class CodexLifecycleWaitControlTest {

    @TempDir
    Path temp;

    @Test
    void waitDoesNotStarveSteer() throws Exception {
        try (FakeServer server = new FakeServer(); CodexAppServerLifecycleService service = service(server)) {
            LifecycleControlRequestEnvelope start = request(LifecycleControlRequestEnvelope.Operation.START, null, null,
                    true, "initial");
            CodexLifecycleHttpClient.Response started = service.start(start);
            LifecycleControlRequestEnvelope wait = request(LifecycleControlRequestEnvelope.Operation.WAIT,
                    started.threadId(), started.turnId(), false, null);
            CompletableFuture<CodexLifecycleHttpClient.Response> waiting = CompletableFuture.supplyAsync(() -> callWait(service, wait));
            Thread.sleep(50L);
            LifecycleControlRequestEnvelope steer = request(LifecycleControlRequestEnvelope.Operation.STEER,
                    started.threadId(), started.turnId(), false, "steer");
            assertTrue(service.steer(steer).success());
            assertEquals(CodexLifecycleStateStore.State.COMPLETED.name(),
                    waiting.get(2, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void waitDoesNotStarveInterrupt() throws Exception {
        try (FakeServer server = new FakeServer(); CodexAppServerLifecycleService service = service(server)) {
            CodexLifecycleHttpClient.Response started = service.start(
                    request(LifecycleControlRequestEnvelope.Operation.START, null, null, true, "initial"));
            LifecycleControlRequestEnvelope wait = request(LifecycleControlRequestEnvelope.Operation.WAIT,
                    started.threadId(), started.turnId(), false, null);
            CompletableFuture<CodexLifecycleHttpClient.Response> waiting = CompletableFuture.supplyAsync(() -> callWait(service, wait));
            Thread.sleep(50L);
            LifecycleControlRequestEnvelope interrupt = request(LifecycleControlRequestEnvelope.Operation.INTERRUPT,
                    started.threadId(), started.turnId(), false, null);
            assertTrue(service.interrupt(interrupt).success());
            assertEquals(CodexLifecycleStateStore.State.INTERRUPTED.name(),
                    waiting.get(2, TimeUnit.SECONDS).state());
        }
    }

    private CodexAppServerLifecycleService service(FakeServer server) throws Exception {
        Path worktree = Files.createDirectories(temp.resolve(UUID.randomUUID().toString()));
        LifecycleControlRequestEnvelope.AuthorityContext authority = new LifecycleControlRequestEnvelope.AuthorityContext(
                "project", "codex", "connection", "session", "fingerprint", 1, "participant",
                UUID.randomUUID().toString(), 1L, worktree.toString(), worktree.toRealPath().toString(),
                "git", "branch", "a".repeat(40), "supervisor", "worker");
        CodexLifecycleStateStore store = new CodexLifecycleStateStore(temp.resolve("state"));
        LifecycleIdempotencyLedger ledger = new LifecycleIdempotencyLedger(temp.resolve("ledger.json"));
        return new CodexAppServerLifecycleService(authority, store, ledger,
                (ignored, generation) -> server.process(), new ProcessTreeTerminator(), temp.resolve("evidence"));
    }

    private LifecycleControlRequestEnvelope request(LifecycleControlRequestEnvelope.Operation operation,
            String threadId, String turnId, boolean continuation, String input) throws IOException {
        LifecycleControlRequestEnvelope.AuthorityContext authority = new LifecycleControlRequestEnvelope.AuthorityContext(
                "project", "codex", "connection", "session", "fingerprint", 1, "participant",
                "00000000-0000-0000-0000-000000000001", 1L, temp.toString(), temp.toRealPath().toString(),
                "git", "branch", "a".repeat(40), "supervisor", "worker");
        return new LifecycleControlRequestEnvelope(UUID.randomUUID(), "host", authority, operation, 0L, threadId,
                turnId, continuation, input, Instant.now().plusSeconds(10).toEpochMilli(), Map.of());
    }

    private static CodexLifecycleHttpClient.Response callWait(CodexAppServerLifecycleService service,
            LifecycleControlRequestEnvelope request) {
        try {
            return service.waitForTurn(request);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static final class FakeServer implements AutoCloseable {
        private final PipedInputStream serverInput;
        private final PipedOutputStream clientOutput;
        private final Thread thread;
        private FakeProcess process;

        private FakeServer() throws IOException {
            serverInput = new PipedInputStream();
            PipedOutputStream clientInput = new PipedOutputStream(serverInput);
            clientOutput = new PipedOutputStream();
            PipedInputStream clientStdout = new PipedInputStream(clientOutput);
            process = new FakeProcess(clientStdout, clientInput);
            thread = new Thread(() -> serve(serverInput, clientOutput), "fake-codex-app-server");
            thread.setDaemon(true);
            thread.start();
        }

        private CodexAppServerLifecycleService.AppServerProcess process() {
            return new CodexAppServerLifecycleService.AppServerProcess(process, "fake-codex", "fake-codex app-server",
                    System.currentTimeMillis());
        }

        private static void serve(InputStream input, OutputStream output) {
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Map<String, Object> request = map(ProviderJson.parse(line));
                    if ("initialized".equals(String.valueOf(request.get("method")))) {
                        continue;
                    }
                    String id = String.valueOf(request.get("id"));
                    String method = String.valueOf(request.get("method"));
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (method.equals("thread/start")) {
                        result.put("thread", Map.of("id", "thread-1"));
                    } else if (method.equals("turn/start")) {
                        result.put("turn", Map.of("id", "turn-1", "status", "inProgress"));
                    }
                    write(output, Map.of("id", id, "result", result));
                    if (method.equals("thread/start")) {
                        write(output, Map.of("method", "thread/started", "params",
                                Map.of("thread", Map.of("id", "thread-1"))));
                    } else if (method.equals("turn/start")) {
                        write(output, Map.of("method", "turn/started", "params",
                                Map.of("threadId", "thread-1", "turn", Map.of("id", "turn-1",
                                        "status", "inProgress"))));
                    } else if (method.equals("turn/steer")) {
                        write(output, Map.of("method", "turn/completed", "params", Map.of("threadId", "thread-1",
                                "turn", Map.of("id", "turn-1", "status", "completed"))));
                    } else if (method.equals("turn/interrupt")) {
                        write(output, Map.of("method", "turn/completed", "params", Map.of("threadId", "thread-1",
                                "turn", Map.of("id", "turn-1", "status", "interrupted"))));
                    }
                }
            } catch (IOException ignored) {
                // Fixture shutdown.
            }
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

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { alive = false; return true; }
        @Override public int exitValue() { return alive ? 0 : 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return 12345L; }
    }
}
