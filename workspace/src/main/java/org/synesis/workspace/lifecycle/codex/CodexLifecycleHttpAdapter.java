package org.synesis.workspace.lifecycle.codex;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded signed HTTP adapter for the Codex-only loopback lifecycle route.
 *
 * <p>Request bodies are read incrementally and rejected above 64 KiB before
 * JSON parsing or signature verification. WAIT is delegated to the host, which
 * registers its waiter under the lifecycle lock and waits outside it with
 * bounded per-binding and per-host capacity. A disconnected client cannot
 * block protocol reading or lifecycle transitions. The adapter is thread-safe.
 *
 * @since 1.0
 */
public final class CodexLifecycleHttpAdapter implements HttpHandler {

    /** Maximum raw lifecycle-control body bytes. */
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    private final ProjectRuntimeHost host;
    private final AtomicLong oversizedRequests = new AtomicLong();

    /**
     * Creates an adapter bound to the retained production lifecycle owner.
     *
     * @param host retained production lifecycle owner
     */
    public CodexLifecycleHttpAdapter(ProjectRuntimeHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Handles one loopback POST request.
     *
     * @param exchange HTTP exchange
     * @throws IOException when response writing fails
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, new byte[0]);
                return;
            }
            byte[] body;
            try {
                body = readBounded(exchange.getRequestBody());
            } catch (IOException failure) {
                if (String.valueOf(failure.getMessage()).contains("oversized")) {
                    oversizedRequests.incrementAndGet();
                    send(exchange, 413, new CodexLifecycleHttpClient.Response(false,
                            "lifecycle_control_request_oversized", "FAILED", 0L, null, null,
                            Map.of("maxBytes", MAX_REQUEST_BYTES)).encoded());
                    return;
                }
                throw failure;
            }
            CodexLifecycleHttpClient.Response response;
            int status = 200;
            try {
                LifecycleControlRequestEnvelope.SignedEnvelope signed =
                        LifecycleControlRequestEnvelope.SignedEnvelope.decode(body);
                if (signed.request().operation() == LifecycleControlRequestEnvelope.Operation.WAIT) {
                    handleWait(exchange, signed);
                    return;
                }
                response = host.handle(signed);
                if (!response.success()) status = 409;
            } catch (Exception failure) {
                status = diagnosticStatus(failure);
                response = new CodexLifecycleHttpClient.Response(false, diagnostic(failure), "FAILED", 0L,
                        null, null, java.util.Map.of());
            }
            byte[] encoded = response.encoded();
            if (encoded.length > CodexLifecycleHttpClient.MAX_RESPONSE_BYTES) {
                status = 500;
                encoded = new CodexLifecycleHttpClient.Response(false, "lifecycle_response_overflow", "FAILED", 0L,
                        null, null, java.util.Map.of()).encoded();
            }
            send(exchange, status, encoded);
        }
    }

    /**
     * Returns the number of lifecycle-control bodies rejected before parsing.
     *
     * @return bounded oversized-body count
     */
    public long oversizedRequestCount() {
        return oversizedRequests.get();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (input) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_REQUEST_BYTES) {
                    throw new IOException("lifecycle_control_request_oversized");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void handleWait(HttpExchange exchange,
            LifecycleControlRequestEnvelope.SignedEnvelope signed) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            FutureTask<CodexLifecycleHttpClient.Response> result = new FutureTask<>(() -> {
                try {
                    return host.handle(signed);
                } catch (Exception failure) {
                    throw failure;
                }
            });
            Thread waitThread = new Thread(result, "synesis-codex-http-wait-bridge");
            waitThread.setDaemon(true);
            waitThread.start();
            byte[] heartbeat = new byte[] {' '};
            long nextHeartbeat = System.nanoTime();
            try {
                while (!result.isDone()) {
                    long remaining = signed.request().callerDeadlineEpochMillis() - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    long waitMillis = Math.min(250L, remaining);
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException interrupted) {
                        result.cancel(true);
                        waitThread.interrupt();
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!result.isDone() && System.nanoTime() >= nextHeartbeat) {
                        output.write(heartbeat);
                        output.flush();
                        nextHeartbeat = System.nanoTime() + 250_000_000L;
                    }
                }
                if (!result.isDone()) {
                    result.cancel(true);
                    waitThread.interrupt();
                    writeResponse(output, new CodexLifecycleHttpClient.Response(false,
                            "lifecycle_wait_deadline_expired", "FAILED", 0L, null, null, Map.of()));
                    return;
                }
                CodexLifecycleHttpClient.Response response;
                try {
                    response = result.get();
                } catch (InterruptedException interrupted) {
                    result.cancel(true);
                    waitThread.interrupt();
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException failure) {
                    response = new CodexLifecycleHttpClient.Response(false, diagnostic(failure),
                            "FAILED", 0L, null, null, Map.of());
                } catch (CancellationException cancelled) {
                    response = new CodexLifecycleHttpClient.Response(false, "lifecycle_wait_cancelled",
                            "FAILED", 0L, null, null, Map.of());
                }
                writeResponse(output, response);
            } catch (IOException disconnected) {
                result.cancel(true);
                waitThread.interrupt();
                throw disconnected;
            }
        }
    }

    private static void writeResponse(java.io.OutputStream output,
            CodexLifecycleHttpClient.Response response) throws IOException {
        byte[] encoded = response.encoded();
        if (encoded.length > CodexLifecycleHttpClient.MAX_RESPONSE_BYTES) {
            encoded = new CodexLifecycleHttpClient.Response(false, "lifecycle_response_overflow", "FAILED", 0L,
                    null, null, Map.of()).encoded();
        }
        output.write(encoded);
        output.flush();
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static int diagnosticStatus(Exception failure) {
        String message = String.valueOf(failure.getMessage());
        return message.contains("oversized") ? 413 : message.contains("signature") || message.contains("owner")
                ? 401 : message.contains("conflict") ? 409 : 400;
    }

    private static String diagnostic(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() > 1_024 ? message.substring(0, 1_024) : message;
    }
}
