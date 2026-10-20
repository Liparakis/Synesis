package org.synesis.mcp.transport.stdio;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import org.synesis.mcp.application.McpProtocolHandler;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Runs the stdio message loop for the Synesis Model Context Protocol (MCP) server.
 *
 * <p>Reads JSON-RPC request frames from stdin and writes JSON-RPC response frames exclusively to
 * stdout. Diagnostic logging is isolated to stderr so stdout remains strictly unpolluted.
 *
 * <p>Single-threaded: {@link #run()} must be called at most once, from one thread.
 *
 * @since 1.0
 */
public final class McpStdioServer {

    /** Environment variable naming the opt-in trace file. */
    private static final String TRACE_FILE_ENV = "SYNESIS_MCP_TRACE_FILE";

    /** Connection-scoped protocol handler that owns MCP session authority. */
    private final McpProtocolHandler handler;

    /** Input stream carrying newline-delimited JSON-RPC frames. */
    private final InputStream in;

    /** Output stream reserved exclusively for JSON-RPC response frames. */
    private final PrintStream out;

    /** Diagnostic stream used when the protocol loop fails. */
    private final PrintStream err;

    /** Optional append-only trace path; {@code null} when tracing is disabled. */
    private final Path traceFile;

    /** Whether the first non-empty input frame has been observed. */
    private boolean firstInputObserved;

    /**
     * Creates an MCP stdio server with standard system streams.
     *
     * @param handler protocol message handler
     */
    public McpStdioServer(McpProtocolHandler handler) {
        this(handler, System.in, System.out, System.err);
    }

    /**
     * Creates an MCP stdio server with custom streams for testing or isolation.
     *
     * @param handler protocol message handler
     * @param in      input stream for requests
     * @param out     output stream for protocol frames ONLY
     * @param err     error stream for diagnostic logging
     */
    public McpStdioServer(McpProtocolHandler handler, InputStream in, PrintStream out, PrintStream err) {
        this(handler, in, out, err, configuredTraceFile());
    }

    /**
     * Creates a server that traces to the given file, for tests that cannot set the environment.
     *
     * @param handler   protocol message handler
     * @param in        input stream for requests
     * @param out       output stream for protocol frames ONLY
     * @param err       error stream for diagnostic logging
     * @param traceFile append-only trace path, or {@code null} to trace nothing
     */
    McpStdioServer(McpProtocolHandler handler, InputStream in, PrintStream out, PrintStream err, Path traceFile) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.traceFile = traceFile;
    }

    private static Path configuredTraceFile() {
        String tracePath = System.getenv(TRACE_FILE_ENV);
        return tracePath == null || tracePath.isBlank() ? null : Path.of(tracePath);
    }

    /**
     * Starts the stdio event loop, processing requests until EOF or stream closure.
     *
     * <p>A clean EOF closes the handler normally; any failure closes it abnormally and is reported on
     * the error stream.
     *
     * @return process exit code ({@code 0} for clean EOF shutdown, {@code 1} after a transport or handler failure)
     */
    public int run() {
        boolean cleanClose = false;
        try {
            McpFrameReader reader = new McpFrameReader(in);
            String line;
            while ((line = reader.readFrame()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                process(line);
            }
            cleanClose = true;
            return 0;
        } catch (Throwable failure) {
            handler.closeAbnormally();
            err.println("[synesis-mcp] Stdio loop terminated with error: " + failure.getMessage());
            failure.printStackTrace(err);
            return 1;
        } finally {
            if (cleanClose) {
                handler.close();
            }
        }
    }

    /**
     * Handles one non-empty frame and writes its response, if any.
     *
     * @param line raw JSON-RPC input frame
     */
    private void process(String line) {
        // The method name only feeds tracing, so skip the extra parse when tracing is off.
        Map<?, ?> message = traceFile == null ? null : parsedMessage(line);
        String method = message != null && message.get("method") instanceof String name ? name : null;
        traceInbound(method);
        long startedMillis = System.currentTimeMillis();
        long startedNanos = System.nanoTime();
        String responseJson = handler.handleMessage(line);
        if (responseJson == null) {
            return;
        }
        if ("initialize".equals(method)) {
            trace("initialize_response_written");
        }
        out.println(responseJson);
        out.flush();
        traceCall(method, message, startedMillis, System.nanoTime() - startedNanos);
    }

    /**
     * Traces how long Synesis held one answered request, from the frame being read to the answer being flushed, so
     * the onboarding measurement can tell Synesis's own time from the model's (ADR-0103). Only the method and the tool
     * name are recorded, never arguments or results. The line is written after the answer, so tracing is not in the
     * time it reports.
     *
     * @param method        JSON-RPC method of the frame, or {@code null} when unknown
     * @param message       the parsed frame
     * @param startedMillis wall-clock time the frame was read, as epoch milliseconds
     * @param elapsedNanos  time from reading the frame to flushing its answer
     */
    private void traceCall(String method, Map<?, ?> message, long startedMillis, long elapsedNanos) {
        if (traceFile == null || method == null) {
            return;
        }
        String tool = "?";
        if ("tools/call".equals(method) && message.get("params") instanceof Map<?, ?> params && params.get("name") instanceof String name && name.matches("[A-Za-z0-9_.-]{1,64}")) {
            tool = name;
        }
        trace("mcp_call method=" + method + " tool=" + tool + " started_epoch_ms=" + startedMillis + " duration_micros=" + elapsedNanos / 1_000L);
    }

    /**
     * Traces the arrival of a frame and the handshake milestones it represents.
     *
     * @param method JSON-RPC method of the frame, or {@code null} when unknown
     */
    private void traceInbound(String method) {
        if (!firstInputObserved) {
            firstInputObserved = true;
            trace("stdin_first_byte_received");
        }
        if ("initialize".equals(method)) {
            trace("initialize_parsed");
        } else if ("tools/list".equals(method)) {
            trace("tools_list_received");
        }
    }

    /**
     * Parses a frame for transport tracing without taking over protocol validation.
     *
     * @param line raw JSON-RPC input frame
     * @return the frame as an object, or {@code null} when it is not one
     */
    private Map<?, ?> parsedMessage(String line) {
        try {
            if (ProviderJson.parse(line) instanceof Map<?, ?> map) {
                return map;
            }
        } catch (RuntimeException ignored) {
            // Malformed input is handled by the protocol handler.
        }
        return null;
    }

    /**
     * Appends one opt-in diagnostic event without allowing tracing failures to affect MCP I/O.
     *
     * @param event fixed event label to append
     */
    private void trace(String event) {
        if (traceFile == null) {
            return;
        }
        try {
            Files.writeString(traceFile, event + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Opt-in diagnostics must never affect the protocol stream.
        }
    }
}