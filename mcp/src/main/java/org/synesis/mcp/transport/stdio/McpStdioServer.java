package org.synesis.mcp.transport.stdio;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.mcp.application.McpProtocolHandler;

/**
 * Runs the stdio message loop for the Synesis Model Context Protocol (MCP) server.
 *
 * <p>Reads JSON-RPC request frames from stdin and writes JSON-RPC response frames exclusively
 * to stdout. Diagnostic logging is isolated to stderr so stdout remains strictly unpolluted.
 *
 * @since 1.0
 */
public final class McpStdioServer {

    private final McpProtocolHandler handler;
    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final Path traceFile;
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
        this.handler = Objects.requireNonNull(handler, "handler");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        String tracePath = System.getenv("SYNESIS_MCP_TRACE_FILE");
        this.traceFile = tracePath == null || tracePath.isBlank() ? null : Path.of(tracePath);
    }

    /**
     * Starts the stdio event loop, processing requests until EOF or stream closure.
     *
     * @return process exit code (0 for clean EOF shutdown)
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
                String method = messageMethod(line);
                if (!firstInputObserved) {
                    firstInputObserved = true;
                    trace("stdin_first_byte_received");
                }
                if ("initialize".equals(method)) {
                    trace("initialize_parsed");
                } else if ("tools/list".equals(method)) {
                    trace("tools_list_received");
                }
                String responseJson = handler.handleMessage(line);
                if (responseJson != null) {
                    if ("initialize".equals(method)) {
                        trace("initialize_response_written");
                    }
                    out.println(responseJson);
                    out.flush();
                }
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

    private String messageMethod(String line) {
        try {
            Object value = ProviderJson.parse(line);
            if (value instanceof Map<?, ?> map && map.get("method") instanceof String method) {
                return method;
            }
        } catch (RuntimeException ignored) {
            // Malformed input is handled by the protocol handler.
        }
        return null;
    }

    private void trace(String event) {
        if (traceFile == null) {
            return;
        }
        try {
            Files.writeString(traceFile, event + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Opt-in diagnostics must never affect the protocol stream.
        }
    }
}
