package org.synesis.coordination.transport.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionEvent;

/**
 * Loopback HTTP command and server-sent-event adapter for one coordinator.
 */
public final class CoordinationHttpServer implements AutoCloseable {

    private final CoordinationService service;
    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * Creates a loopback-only server.
     *
     * @param service coordination service
     * @param address local bind address; only loopback addresses are accepted
     * @throws IOException when the listener cannot be created
     */
    public CoordinationHttpServer(CoordinationService service, InetSocketAddress address) throws IOException {
        this(service, address, null);
    }

    /**
     * Creates the existing coordination listener with an optional Codex-only
     * lifecycle route. The handler is injected by the production owner and no
     * provider abstraction is introduced in this coordination module.
     *
     * @param service               coordination service
     * @param address               local loopback address
     * @param codexLifecycleHandler retained Codex handler, or {@code null}
     * @throws IOException when the listener cannot be created
     */
    public CoordinationHttpServer(CoordinationService service, InetSocketAddress address,
            HttpHandler codexLifecycleHandler) throws IOException {
        this.service = java.util.Objects.requireNonNull(service, "service");
        java.util.Objects.requireNonNull(address, "address");
        InetAddress host = address.getAddress();
        if (host == null || !host.isLoopbackAddress()) {
            throw new IllegalArgumentException("loopback address required");
        }
        this.server = HttpServer.create(address, 16);
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "synesis-coordination-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/command", this::command);
        server.createContext("/events", this::events);
        if (codexLifecycleHandler != null) {
            server.createContext("/codex-lifecycle/v1", codexLifecycleHandler);
        }
    }

    private static boolean queryBoolean(URI uri) {
        String query = uri.getRawQuery();
        if (query == null) {
            return false;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                    .equals("once")) {
                return Boolean.parseBoolean(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private static long queryLong(URI uri) {
        String query = uri.getRawQuery();
        if (query == null) {
            return 0;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                    .equals("after")) {
                try {
                    return Long.parseLong(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException("invalid cursor");
                }
            }
        }
        return 0;
    }

    private static byte[] readBounded(HttpExchange exchange) throws IOException {
        long declared = exchange.getRequestHeaders()
                .getFirst("Content-Length") == null ? -1
                : Long.parseLong(exchange.getRequestHeaders()
                                 .getFirst("Content-Length"));
        if (declared > 64 * 1024) {
            throw new IOException("request exceeds bound");
        }
        try (var input = exchange.getRequestBody(); var bytes = new java.io.ByteArrayOutputStream()) {
            input.transferTo(new java.io.OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    if (bytes.size() >= 64 * 1024) {
                        throw new IOException("request exceeds bound");
                    }
                    bytes.write(value);
                }

                @Override
                @SuppressWarnings("NullableProblems")
                public void write(byte[] value, int offset, int length) throws IOException {
                    if (bytes.size() + length > 64 * 1024) {
                        throw new IOException("request exceeds bound");
                    }
                    bytes.write(value, offset, length);
                }
            });
            return bytes.toByteArray();
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders()
                .set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /**
     * Starts listening for commands and event streams.
     */
    public void start() {
        server.start();
    }

    /**
     * Returns the bound address, including an ephemeral port when requested.
     *
     * @return bound address
     */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    /**
     * Stops the server and releases its executor.
     */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void command(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        byte[] body = readBounded(exchange);
        try {
            PredictionEvent event = service.submit(CoordinationCommand.decode(body));
            send(exchange, 200, event.encoded());
        } catch (java.security.GeneralSecurityException | IllegalArgumentException failure) {
            send(exchange,
                    400,
                    failure.getMessage()
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private void events(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        long after = queryLong(exchange.getRequestURI());
        boolean once = queryBoolean(exchange.getRequestURI());
        exchange.getResponseHeaders()
                .set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders()
                .set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (exchange; var subscription = service.subscribe(after);
                var output = exchange.getResponseBody()) {
            while (!Thread.currentThread()
                    .isInterrupted()) {
                PredictionEvent event = once ? subscription.poll() : subscription.take();
                if (event == null) {
                    output.write(": empty\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    break;
                }
                String data = "id: " + event.sequence() + "\ndata: "
                        + Base64.getEncoder()
                        .encodeToString(event.encoded()) + " " + event.type()
                        .name() + "\n\n";
                output.write(data.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
        }
    }
}
