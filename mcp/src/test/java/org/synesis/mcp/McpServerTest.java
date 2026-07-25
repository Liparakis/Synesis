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
        assertTrue(responseJson.contains("\"name\":\"synesis.read_file\""));
        assertTrue(responseJson.contains("\"name\":\"synesis.apply_patch\""));
        assertTrue(responseJson.contains("\"name\":\"synesis.run_command\""));
        assertTrue(responseJson.contains("\"name\":\"synesis.get_next_action\""));
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
    void testInitializeWithRootUriBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent(); // Incorrect cwd (not project root)
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "antigravity", "conn-antigravity-1");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + tempRoot.toUri() + "\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertEquals(tempRoot, handler.activeProjectRoot());

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);
        assertTrue(callResponse.contains("ready"));
    }

    @Test
    void testInitializeWithWorkspaceFoldersBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "antigravity", "conn-antigravity-2");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + tempRoot.toUri() + "\",\"name\":\"test\"}]}}";
        handler.handleMessage(initReq);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testInitializeWithRootsBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "antigravity", "conn-antigravity-3");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"roots\":[{\"uri\":\"" + tempRoot.toUri() + "\",\"name\":\"test\"}]}}";
        handler.handleMessage(initReq);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testAmbiguousMultipleInitializedRootsFailClosed() throws Exception {
        Path secondProject = Files.createTempDirectory("synesis-second-proj-");
        git(secondProject, "init");
        git(secondProject, "config", "user.name", "Test User");
        git(secondProject, "config", "user.email", "test@example.com");
        Files.writeString(secondProject.resolve("README.md"), "# Second Repo\n");
        git(secondProject, "add", ".");
        git(secondProject, "commit", "-m", "Initial commit");
        new org.synesis.workspace.application.ProjectApplicationService().init(secondProject);

        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot.getParent(), "antigravity", "conn-ambiguous");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"workspaceFolders\":[{\"uri\":\""
                + tempRoot.toUri() + "\"},{\"uri\":\"" + secondProject.toUri() + "\"}]}}";
        handler.handleMessage(initReq);

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);

        assertTrue(callResponse.contains("retry_required"));
        assertTrue(callResponse.contains("workspace_not_ready"));
    }

    @Test
    void testWorktreeRootPathIsNotAcceptedAsControlProject() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");
        handler.setAntigravityProjectsDir(Files.createTempDirectory("synesis-test-empty-projects-"));
        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        handler.handleMessage(callReq);

        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(tempRoot, "codex", "conn-1", null, false);
        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();

        McpProtocolHandler worktreeHandler = new McpProtocolHandler(sessionService, tempRoot.getParent(), "antigravity", "conn-wt-test");
        worktreeHandler.setAntigravityProjectsDir(Files.createTempDirectory("synesis-test-empty-projects-2-"));
        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + worktreePath.toUri() + "\"}}";
        worktreeHandler.handleMessage(initReq);

        String wtCallResponse = worktreeHandler.handleMessage(callReq);
        assertTrue(wtCallResponse.contains("retry_required"));
    }

    @Test
    void testMissingProjectJsonReturnsWorkspaceNotReady() throws Exception {
        Path uninit = Files.createTempDirectory("synesis-uninit-root-");
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, uninit, "antigravity", "conn-uninit");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);

        assertTrue(callResponse.contains("retry_required"));
        assertTrue(callResponse.contains("workspace_not_ready"));
    }

    @Test
    void testEncodedWindowsFileUriAndUriWithSpacesParsedCleanly() {
        Path p1 = McpProtocolHandler.parseUriOrPath("file:///C:/My%20Test%20Folder/project");
        assertNotNull(p1);
        assertTrue(p1.toString().contains("My Test Folder"));

        Path p2 = McpProtocolHandler.parseUriOrPath("file:///c%3A/Users/Liparakis/Desktop/SynesisTestProject");
        assertNotNull(p2);
        assertTrue(p2.toString().replace('\\', '/').toLowerCase().contains("synesistestproject"));
    }

    @Test
    void testRootsListChangedNotificationUpdatesUnboundContext() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "antigravity", "conn-changed-1");

        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + tempRoot.toUri() + "\"}]}}";
        handler.handleMessage(notif);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testConflictingRootNotificationAfterSessionBindingIsRejected() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "antigravity", "conn-bound-1");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        String response = handler.handleMessage(callReq);
        assertTrue(response.contains("ready"));

        Path conflicting = tempRoot.getParent();
        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\"" + conflicting.toUri() + "\"}]}}";
        handler.handleMessage(notif);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testRootsListReturnsActiveProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-roots");

        String rootsReq = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"roots/list\"}";
        String responseJson = handler.handleMessage(rootsReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"roots\":"));
        assertTrue(responseJson.contains(tempRoot.getFileName().toString()));
    }

    @Test
    void testInitializeNegotiatesSupportedProtocolVersion() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-version");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2025-06-18\""));
    }

    @Test
    void testInitializeFallsBackForUnsupportedProtocolVersion() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-version-fallback");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2099-01-01\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2024-11-05\""));
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

    @Test
    void testMcpReadFileAndApplyPatchEndToEnd() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "antigravity", "conn-mcp-tools");

        // 1. Ensure Session
        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}";
        String ensureResp = handler.handleMessage(ensureReq);
        assertTrue(ensureResp.contains("ready"));

        // 2. Apply Patch Create Mode
        String createReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"create\":true,\"content\":\"Hello MCP World\\n\"}}}";
        String createResp = handler.handleMessage(createReq);
        assertTrue(createResp.contains("completed"));
        assertTrue(createResp.contains("src/McpFile.txt"));

        // 3. Read File
        String readReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.read_file\",\"arguments\":{\"path\":\"src/McpFile.txt\"}}}";
        String readResp = handler.handleMessage(readReq);
        assertTrue(readResp.contains("completed"));
        assertTrue(readResp.contains("Hello MCP World"));

        // 4. Apply Patch Modify Mode
        byte[] bytes = "Hello MCP World\n".getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String modifyReq = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"expectedHash\":\"" + hash + "\",\"edits\":[{\"find\":\"World\",\"replace\":\"Antigravity\",\"expectedOccurrences\":1}]}}}";
        String modifyResp = handler.handleMessage(modifyReq);
        assertTrue(modifyResp.contains("completed"));

        // 5. Read Modified File
        String readModResp = handler.handleMessage(readReq);
        assertTrue(readModResp.contains("Hello MCP Antigravity"));

        // 6. Run Command (git_status)
        String runCmdReq = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.run_command\",\"arguments\":{\"type\":\"git_status\"}}}";
        String runCmdResp = handler.handleMessage(runCmdReq);
        assertTrue(runCmdResp.contains("completed"));
        assertTrue(runCmdResp.contains("git_status"));

        // 7. Get Next Action
        String nextActionReq = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.get_next_action\",\"arguments\":{}}}";
        String nextActionResp = handler.handleMessage(nextActionReq);
        assertTrue(nextActionResp.contains("ready"));
        assertTrue(nextActionResp.contains("pending"));
    }
}
