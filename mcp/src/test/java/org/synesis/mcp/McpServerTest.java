package org.synesis.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentSessionService;

class McpServerTest {

    private Path tempRoot;

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed: " + output);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("synesis-mcp-test-");
        git(tempRoot, "init");
        git(tempRoot, "config", "user.name", "Test User");
        git(tempRoot, "config", "user.email", "test@example.com");
        Files.writeString(tempRoot.resolve("README.md"), "# Test Repo\n");
        git(tempRoot, "add", ".");
        git(tempRoot, "commit", "-m", "Initial commit");

        new org.synesis.workspace.application.ProjectApplicationService().init(tempRoot);
    }

    @Test
    void testInitializeHandshakeSucceeds() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2024-11-05\""));
        assertTrue(responseJson.contains("\"name\":\"synesis-mcp\""));
    }

    @Test
    void testInitializedNotificationReturnsNoResponse() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        String responseJson = handler.handleMessage(notif);

        assertNull(responseJson);
    }

    @Test
    void testToolsListReturnsExactlyEnsureSession() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String listReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        String responseJson = handler.handleMessage(listReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"name\":\"synesis.ensure_session\""));
        assertFalse(responseJson.contains("synesis.read_file"));
        assertFalse(responseJson.contains("synesis.apply_patch"));
        assertFalse(responseJson.contains("synesis.run_command"));
        assertFalse(responseJson.contains("synesis.get_next_action"));
    }

    @Test
    void testToolsCallEnsureSessionReturnsReadyStatus() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        String responseJson = handler.handleMessage(callReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("ready"));
        assertTrue(responseJson.contains("isolated"));

        // Verify no internal diagnostic leak
        assertFalse(responseJson.contains("sessionId"));
        assertFalse(responseJson.contains("worktreePath"));
        assertFalse(responseJson.contains(tempRoot.toString()));
    }

    @Test
    void testUnknownMethodReturnsMethodNotFound() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String req = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"unknown/method\"}";
        String responseJson = handler.handleMessage(req);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"code\":-32601"));
        assertTrue(responseJson.contains("Method not found"));
    }

    @Test
    void testUnknownToolReturnsToolError() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String req = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.unknown_tool\"}}";
        String responseJson = handler.handleMessage(req);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("Unknown tool"));
    }

    @Test
    void testInvalidJsonReturnsParseError() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String req = "INVALID_JSON_{{";
        String responseJson = handler.handleMessage(req);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"code\":-32700"));
    }

    @Test
    void testStdioServerEventLoopAndCleanEofShutdown() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\"}}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        McpStdioServer server = new McpStdioServer(handler, in, new PrintStream(out), new PrintStream(err));
        int exitCode = server.run();

        assertEquals(0, exitCode);
        assertEquals(0, err.size()); // Stderr clean

        String stdoutString = out.toString(StandardCharsets.UTF_8);
        List<String> lines = stdoutString.lines().toList();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"id\":1"));
        assertTrue(lines.get(1).contains("\"id\":2"));
        assertTrue(lines.get(2).contains("\"id\":3"));
    }
}
