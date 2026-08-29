package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic request-correlation and late-response fixtures.
 */
class CodexAppServerProtocolClientTest {

    @TempDir
    Path temp;

    private static void assertThrowsTimeout(CompletableFuture<Void> call) throws Exception {
        try {
            call.get(2, TimeUnit.SECONDS);
            throw new AssertionError("request did not time out");
        } catch (ExecutionException expected) {
            assertInstanceOf(CompletionTimeout.class, expected.getCause());
        }
    }

    @Test
    void lateResponseAfterTimeoutIsNotGenerationFailure() throws Exception {
        try (Fixture fixture = new Fixture(temp.resolve("timeout"))) {
            CompletableFuture<Void> call = fixture.request(Duration.ofMillis(25));
            String id = fixture.awaitRequestId();
            assertThrowsTimeout(call);
            fixture.respond(id);
            Thread.sleep(50L);
            assertFalse(fixture.client.failed());
            assertTrue(fixture.client.tombstones()
                    .stream()
                    .anyMatch(item -> item.requestId()
                            .equals(id)
                            && item.responseArrived()));
        }
    }

    @Test
    void neverIssuedResponseFailsTheGeneration() throws Exception {
        try (Fixture fixture = new Fixture(temp.resolve("unknown"))) {
            fixture.stdout.write("{\"id\":\"never-issued\",\"result\":{}}\n"
                    .getBytes(StandardCharsets.UTF_8));
            fixture.stdout.flush();
            for (int i = 0; i < 20 && !fixture.client.failed(); i++) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
            }
            assertTrue(fixture.client.failed());
            assertTrue(fixture.failure.get()
                    .getMessage()
                    .contains("codex_request_correlation_failed"));
        }
    }

    @Test
    void duplicateTerminalResponseFailsTheGeneration() throws Exception {
        try (Fixture fixture = new Fixture(temp.resolve("duplicate"))) {
            CompletableFuture<Void> call = fixture.request(Duration.ofSeconds(2));
            String id = fixture.awaitRequestId();
            fixture.respond(id);
            call.get(2, TimeUnit.SECONDS);
            fixture.respond(id);
            for (int i = 0; i < 20 && !fixture.client.failed(); i++) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
            }
            assertTrue(fixture.client.failed());
            assertTrue(fixture.failure.get()
                    .getMessage()
                    .contains("duplicate_response_id"));
        }
    }

    @Test
    void callerCancellationLeavesAValidLateResponseTombstone() throws Exception {
        try (Fixture fixture = new Fixture(temp.resolve("cancel"))) {
            Thread caller = new Thread(() -> {
                try {
                    fixture.client.request("thread/read", Map.of(),
                            LifecycleControlRequestEnvelope.Classification.READ_ONLY, "digest", null, null,
                            Duration.ofSeconds(5));
                } catch (Exception ignored) {
                    // Cancellation is the expected caller outcome.
                }
            });
            caller.start();
            String id = fixture.awaitRequestId();
            caller.interrupt();
            caller.join(2_000L);
            fixture.respond(id);
            Thread.sleep(50L);
            assertFalse(fixture.client.failed());
            assertTrue(fixture.client.tombstones()
                    .stream()
                    .anyMatch(item -> item.requestId()
                            .equals(id)
                            && item.responseArrived()));
        }
    }

    @Test
    void echoesNumericServerRequestIdWithoutChangingItsJsonType() throws Exception {
        try (Fixture fixture = new Fixture(temp.resolve("numeric-server-request"))) {
            fixture.stdout.write("{\"id\":0,\"method\":\"currentTime/read\",\"params\":{}}\n"
                    .getBytes(StandardCharsets.UTF_8));
            fixture.stdout.flush();
            for (int i = 0; i < 100; i++) {
                String output = fixture.stdin.toString(StandardCharsets.UTF_8);
                if (output.contains("\"id\":0")) {
                    assertFalse(output.contains("\"id\":\"0\""));
                    return;
                }
                Thread.sleep(5L);
            }
            throw new AssertionError("numeric server request response not written");
        }
    }

    private static final class CompletionTimeout extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private CompletionTimeout(TimeoutException cause) {
            super(cause);
        }
    }

    private static final class CompletionFailure extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private CompletionFailure(Exception cause) {
            super(cause);
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final PipedOutputStream stdout = new PipedOutputStream();
        private final PipedInputStream clientStdout;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final CodexEvidenceJournal journal;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CodexAppServerProtocolClient client;

        private Fixture(Path path) throws IOException {
            clientStdout = new PipedInputStream(stdout);
            journal = new CodexEvidenceJournal(path.resolve("generation-1.jsonl"));
            client = new CodexAppServerProtocolClient(1L, clientStdout, stdin,
                    new CodexAppServerProtocolClient.Listener() {
                        @Override
                        public void onEvent(String method, Map<String, Object> params) {
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            failure.set(error);
                        }
                    }, journal);
        }

        private CompletableFuture<Void> request(Duration timeout) {
            return CompletableFuture.runAsync(() -> {
                try {
                    client.request("thread/read", Map.of(),
                            LifecycleControlRequestEnvelope.Classification.READ_ONLY, "digest", null, null,
                            timeout);
                } catch (TimeoutException expected) {
                    throw new CompletionTimeout(expected);
                } catch (Exception failure) {
                    throw new CompletionFailure(failure);
                }
            });
        }

        private String awaitRequestId() throws Exception {
            for (int i = 0; i < 100; i++) {
                String text = stdin.toString(StandardCharsets.UTF_8);
                int start = text.indexOf("\"id\":\"");
                if (start >= 0) {
                    int from = start + 6;
                    int end = text.indexOf('"', from);
                    return text.substring(from, end);
                }
                Thread.sleep(5L);
            }
            throw new AssertionError("request frame not written");
        }

        private void respond(String id) throws IOException {
            stdout.write(("{\"id\":\"" + id + "\",\"result\":{\"ok\":true}}\n")
                    .getBytes(StandardCharsets.UTF_8));
            stdout.flush();
        }

        @Override
        public void close() throws IOException {
            client.close();
            journal.close();
            stdout.close();
            clientStdout.close();
        }
    }
}
