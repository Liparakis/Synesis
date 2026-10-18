package org.synesis.coordination.transport.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.coordination.persistence.ReplicationMessage;

/**
 * Loopback HTTP command and server-sent-event adapter for one coordinator.
 */
public final class CoordinationHttpServer implements AutoCloseable {

    private static final int MAX_CODE_REQUEST_BYTES = 1024;

    private final CoordinationService service;
    private final HttpServer server;
    private final ExecutorService executor;
    /**
     * Where this computer's processes send proposals and pulls for a leader, or null when this computer has none.
     */
    private volatile ReplicationExchange relay;
    /**
     * Where this computer's processes ask for code of a shared project, or null when this computer has no peers.
     */
    private volatile ReplicationExchange code;

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
     * Creates the existing coordination listener with an optional Codex-only lifecycle route. The handler is injected
     * by the production owner
     * and no provider abstraction is introduced in this coordination module.
     *
     * @param service               coordination service
     * @param address               local loopback address
     * @param codexLifecycleHandler retained Codex handler, or {@code null}
     * @throws IOException when the listener cannot be created
     */
    public CoordinationHttpServer(CoordinationService service,
            InetSocketAddress address,
            HttpHandler codexLifecycleHandler) throws IOException {
        this(service, address, codexLifecycleHandler, null);
    }

    /**
     * Creates the loopback coordination listener with optional lifecycle and versioned local-control handlers.
     *
     * @param service               coordination service
     * @param address               local loopback address
     * @param codexLifecycleHandler retained Codex handler, or {@code null}
     * @param controlPlaneHandler   local control-plane handler, or {@code null}
     * @throws IOException when the listener cannot be created
     */
    public CoordinationHttpServer(CoordinationService service,
            InetSocketAddress address,
            HttpHandler codexLifecycleHandler,
            HttpHandler controlPlaneHandler) throws IOException {
        this(service, address, codexLifecycleHandler, controlPlaneHandler, null);
    }

    /**
     * Creates the loopback coordination listener with optional lifecycle, versioned local-control, and packaged web UI
     * handlers.
     *
     * @param service               coordination service
     * @param address               local loopback address
     * @param codexLifecycleHandler retained Codex handler, or {@code null}
     * @param controlPlaneHandler   local control-plane handler, or {@code null}
     * @param webUiHandler          packaged static UI handler, or {@code null}
     * @throws IOException when the listener cannot be created
     */
    public CoordinationHttpServer(CoordinationService service,
            InetSocketAddress address,
            HttpHandler codexLifecycleHandler,
            HttpHandler controlPlaneHandler,
            HttpHandler webUiHandler) throws IOException {
        this.service = CoordinationNullException.requireNonNull(service, "service");
        CoordinationNullException.requireNonNull(address, "address");
        InetAddress host = address.getAddress();
        if (host == null || !host.isLoopbackAddress()) {
            throw new CoordinationArgumentException("loopback address required");
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
        server.createContext("/replication", exchange -> forward(exchange, relay, ReplicationMessage.MAX_BYTES));
        server.createContext("/code", exchange -> forward(exchange, code, MAX_CODE_REQUEST_BYTES));
        if (codexLifecycleHandler != null) {
            server.createContext("/codex-lifecycle/v1", codexLifecycleHandler);
        }
        if (controlPlaneHandler != null) {
            server.createContext("/api/v1", controlPlaneHandler);
        }
        if (webUiHandler != null) {
            server.createContext("/", webUiHandler);
        }
    }

    /**
     * Returns the decoded value of the first {@code key=value} pair of the query, or {@code null} when absent.
     */
    private static String queryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Parses the optional boolean query parameter.
     */
    private static boolean queryBoolean(URI uri) {
        return Boolean.parseBoolean(queryParam(uri, "once"));
    }

    /**
     * Parses the optional event-sequence cursor.
     */
    private static long queryLong(URI uri) {
        String after = queryParam(uri, "after");
        if (after == null) {
            return 0;
        }
        try {
            return Long.parseLong(after);
        } catch (NumberFormatException bad) {
            throw new CoordinationArgumentException("invalid cursor");
        }
    }

    /**
     * Reads the HTTP body while enforcing the request-size bound.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static byte[] readBounded(HttpExchange exchange) throws IOException {
        return readBounded(exchange, 64 * 1024);
    }

    /**
     * Reads the HTTP body while enforcing a given request-size bound.
     *
     * @throws IOException when the body exceeds the bound or reading fails
     */
    private static byte[] readBounded(HttpExchange exchange, int limit) throws IOException {
        long declared = exchange.getRequestHeaders().getFirst("Content-Length") == null ? -1
                : Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (declared > limit) {
            throw new CoordinationException("request exceeds bound");
        }
        try (var input = exchange.getRequestBody();
                var bytes = new ByteArrayOutputStream()) {
            input.transferTo(new OutputStream() {
                /**
                 * Writes the type field in the canonical payload format.
                 *
                 * @throws IOException when payload validation or durable I/O fails
                 */
                @Override
                public void write(int value) throws IOException {
                    if (bytes.size() >= limit) {
                        throw new CoordinationException("request exceeds bound");
                    }
                    bytes.write(value);
                }

                /**
                 * Writes the type field in the canonical payload format.
                 *
                 * @throws IOException when payload validation or durable I/O fails
                 */
                @Override
                @SuppressWarnings("NullableProblems")
                public void write(byte[] value, int offset, int length) throws IOException {
                    if (bytes.size() + length > limit) {
                        throw new CoordinationException("request exceeds bound");
                    }
                    bytes.write(value, offset, length);
                }
            });
            return bytes.toByteArray();
        }
    }

    /**
     * Writes the response status, content type, and bounded payload to the HTTP exchange.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /**
     * Sets, or with null removes, where this computer's processes relay proposals and pulls for the project's
     * leader. Only a member computer's coordination server has one.
     *
     * @param exchange the relay, or null
     */
    public void relay(ReplicationExchange exchange) {
        this.relay = exchange;
    }

    /**
     * Sets, or with null removes, what answers this computer's processes when they ask for code of a shared project
     * (ADR-0098 step 3): the coordination server moves it over Link and answers once it is here.
     *
     * @param exchange the answerer, or null
     */
    public void code(ReplicationExchange exchange) {
        this.code = exchange;
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

    /**
     * Decodes and submits one authenticated coordination command.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void command(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        byte[] body = readBounded(exchange);
        try {
            PredictionEvent event = service.submit(CoordinationCommand.decode(body));
            send(exchange, 200, event.encoded());
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            send(exchange, 400, failure.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Streams durable events after the requested cursor.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void events(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }
        long after = queryLong(exchange.getRequestURI());
        boolean once = queryBoolean(exchange.getRequestURI());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (exchange;
                var subscription = service.subscribe(after);
                var output = exchange.getResponseBody()) {
            while (!Thread.currentThread().isInterrupted()) {
                if (subscription.overflowed()) {
                    output.write(": refresh_required\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    break;
                }
                PredictionEvent event = once ? subscription.poll() : subscription.take();
                if (event == null) {
                    output.write(": empty\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    break;
                }
                output.write(EventFrames.encode(event));
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Hands one request of a local process to {@code current} and returns its answer: a replication request relayed
     * to the leader, or a code request. Answers 503 when there is no {@code current}, which tells the caller that
     * nothing was sent.
     *
     * @throws IOException when the request body cannot be read or the answer cannot be written
     */
    private static void forward(HttpExchange exchange, ReplicationExchange current, int maxBytes) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
        } else if (current == null) {
            send(exchange, 503, new byte[0]);
        } else {
            byte[] body;
            try {
                body = readBounded(exchange, maxBytes);
            } catch (CoordinationException tooLarge) {
                send(exchange, 413, new byte[0]);
                return;
            }
            try {
                send(exchange, 200, current.exchange(body));
            } catch (IOException lost) {
                send(exchange, 502, new byte[0]);
            }
        }
    }
}
