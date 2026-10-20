package org.synesis.mcp.transport.stdio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.mcp.application.McpProtocolHandler;
import org.synesis.workspace.application.agent.session.AgentSessionService;

/**
 * The opt-in trace records how long Synesis held each answered request, so the onboarding measurement (ADR-0103) can
 * separate it from the model's time, without recording what was asked or answered.
 */
class McpStdioServerTraceTest {

    private static final Pattern CALL = Pattern.compile("mcp_call method=(\\S+) tool=(\\S+) started_epoch_ms=\\d{13} duration_micros=\\d+");

    @TempDir
    Path tmp;

    @Test
    void everyAnsweredRequestIsTracedWithItsMethodAndToolAndNothingElse() throws Exception {
        Path trace = tmp.resolve("trace.log");
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\",\"arguments\":{\"secret\":\"do-not-trace\"}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"bad name\\nwith newline\"}}\n";

        run(input, trace);

        List<String> calls = Files.readAllLines(trace).stream().filter(line -> line.startsWith("mcp_call ")).toList();
        assertEquals(4, calls.size(), "the notification has no answer, so it has no line: " + calls);
        calls.forEach(line -> assertTrue(CALL.matcher(line).matches(), line));
        assertTrue(calls.get(0).contains("method=initialize tool=?"), calls.get(0));
        assertTrue(calls.get(1).contains("method=tools/list tool=?"), calls.get(1));
        assertTrue(calls.get(2).contains("method=tools/call tool=no_such_tool "), calls.get(2));
        assertTrue(calls.get(3).contains("method=tools/call tool=? "), "a tool name that is not a plain identifier is not copied: " + calls.get(3));
        assertFalse(Files.readString(trace).contains("do-not-trace"));
    }

    @Test
    void withoutATraceFileNothingIsWritten() throws Exception {
        run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n", null);

        assertFalse(Files.exists(tmp.resolve("trace.log")));
    }

    private void run(String input, Path trace) {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tmp, "codex", "conn-trace", false, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(handler, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()), trace);
        assertEquals(0, server.run());
        assertTrue(out.size() > 0);
    }
}
