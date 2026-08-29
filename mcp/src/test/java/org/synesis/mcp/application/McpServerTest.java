package org.synesis.mcp.application;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.mcp.transport.stdio.McpStdioServer;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.lifecycle.GitProcessRunner;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

@SuppressWarnings("TextBlockMigration")
class McpServerTest {

    private Path tempRoot;

    private static void git(Path root, String... arguments) throws Exception {
        GitProcessRunner.run(root, arguments);
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
        new ProviderManualService().install("codex");
        new ProviderManualService().install("claude");
        new ProviderManualService().install("antigravity");
    }

    @Test
    void testInitializeHandshakeSucceeds() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertTrue(responseJson.contains("\"protocolVersion\":\"2024-11-05\""));
        assertTrue(responseJson.contains("\"name\":\"synesis\""));
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
        assertTrue(responseJson.contains("\"name\":\"ensure_session\""));
        assertTrue(responseJson.contains("\"name\":\"read_file\""));
        assertTrue(responseJson.contains("\"name\":\"apply_patch\""));
        assertTrue(responseJson.contains("\"name\":\"run_command\""));
        assertTrue(responseJson.contains("\"name\":\"get_next_action\""));
    }

    @Test
    void toolsListAdvertisesExactlyElevenRawNamesAndRejectsDecoratedCalls() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", "conn-raw");
        String response = handler.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}");
        assertEquals(10, response.split("\"name\":\"").length - 1);
        assertTrue(response.contains("\"name\":\"ensure_session\""));
        assertFalse(response.contains("synesis.ensure_session"));
        String decorated = handler.handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"synesis.ensure_session\",\"arguments\":{}}}");
        assertTrue(decorated.contains("raw MCP tool name required"));
    }

    @Test
    void testToolsCallEnsureSessionReturnsReadyStatus() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
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
    void runCommandRejectsTheRemovedIntentSchema() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-legacy-command");
        String request = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_command\",\"arguments\":{"
                + "\"type\":\"git_status\",\"target\":\".\",\"arguments\":[]}}}";
        String response = handler.handleMessage(request);
        assertTrue(response.contains("invalid_path"), response);
    }

    @Test
    void runCommandRejectsNonIntegralOrOutOfRangeTimeouts() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-invalid-timeout");
        String fractional = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_command\",\"arguments\":{"
                + "\"argv\":[\"git\",\"status\"],\"timeoutSeconds\":1.5}}}";
        String tooLarge = fractional.replace("1.5", "3601");
        assertTrue(handler.handleMessage(fractional)
                .contains("invalid_path"));
        assertTrue(handler.handleMessage(tooLarge)
                .contains("invalid_path"));
    }

    @Test
    void collaborationContractPublishReturnsJsonSafeContractProjection() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                tempRoot,
                "claude",
                "conn-contract-json");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"contract json\",\"acceptance\":\"publish\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/task_tracker_contract.md\"}]}}}}";
        assertTrue(handler.handleMessage(ensure)
                .contains("ready"));
        String publish = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_proposal\",\"payload\":{\"contractId\":\"2b9d4d95-f7b7-4d5d-b3c7-8e40b2b6db31\",\"body\":\"Task tracker API v1\",\"selectors\":[\"src/task_tracker.py\"]}}}}";
        String response = handler.handleMessage(publish);
        assertFalse(response.contains("-32603"));
        assertTrue(response.contains("contentHash"));
        assertTrue(response.contains("2b9d4d95-f7b7-4d5d-b3c7-8e40b2b6db31"));
    }

    @Test
    void collaborationContractStatusReturnsJsonSafeHistoryProjection() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                tempRoot,
                "claude",
                "conn-contract-status");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"contract status json\",\"acceptance\":\"publish\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/status_contract.md\"}]}}}}";
        assertTrue(handler.handleMessage(ensure)
                .contains("ready"));
        String publish = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_proposal\",\"payload\":{\"contractId\":\"9b3fef3a-6f6e-4b4b-bd14-ae4f36ea4f18\",\"body\":\"Status contract v1\",\"selectors\":[\"src/task_tracker.py\"]}}}}";
        assertTrue(handler.handleMessage(publish)
                .contains("contentHash"));
        String status = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"collaboration_status\",\"payload\":{}}}}";
        String response = handler.handleMessage(status);
        assertFalse(response.contains("-32603"));
        assertTrue(response.contains("contracts"));
        assertTrue(response.contains("9b3fef3a-6f6e-4b4b-bd14-ae4f36ea4f18"));
    }

    @Test
    void collaborationDiscoveryReturnsJsonSafeParticipantsIntentsAndClaims() {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                tempRoot,
                "codex",
                "conn-discovery-json");
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"discoverable goal\",\"acceptance\":\"status is readable\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/discovery.json\"}]}}}}";
        assertTrue(handler.handleMessage(ensure)
                .contains("ready"));
        String status = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"collaboration_status\",\"payload\":{}}}}";
        String response = handler.handleMessage(status);
        assertFalse(response.contains("-32603"));
        assertTrue(response.contains("participants"));
        assertTrue(response.contains("intents"));
        assertTrue(response.contains("tests/discovery.json"));
        assertTrue(response.contains("discoverable goal"));
        String next = handler.handleMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}");
        assertFalse(next.contains("-32603"));
        assertTrue(next.contains("participants"));
        assertTrue(next.contains("tests/discovery.json"));
    }

    @Test
    @SuppressWarnings("ExtractMethodRecommender")
    void collaborationRequestAndHandoffOperationsAreAvailableThroughMcp() {
        McpProtocolHandler owner = new McpProtocolHandler(new AgentSessionService(),
                tempRoot,
                "codex",
                "conn-mcp-owner");
        McpProtocolHandler requester = new McpProtocolHandler(new AgentSessionService(),
                tempRoot,
                "claude",
                "conn-mcp-requester");
        String ownerEnsure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"owner\",\"acceptance\":\"contract\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/mcp-request-owner.txt\"}]}}}}";
        String requesterEnsure = ownerEnsure.replace("conn-mcp-owner", "conn-mcp-requester")
                .replace("\\\"id\\\":1", "\\\"id\\\":2")
                .replace("owner", "requester")
                .replace("mcp-request-owner", "mcp-requester");
        assertTrue(owner.handleMessage(ownerEnsure)
                .contains("ready"));
        assertTrue(requester.handleMessage(requesterEnsure)
                .contains("ready"));
        String status = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"collaboration_status\",\"payload\":{}}}}";
        String statusResponse = owner.handleMessage(status);
        String marker = "\\\"intentId\\\":\\\"";
        int markerStart = statusResponse.indexOf(marker);
        String intentId = markerStart >= 0
                ? statusResponse.substring(markerStart + marker.length(), markerStart + marker.length() + 36)
                : "";
        assertFalse(intentId.isBlank(), statusResponse);
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"contract_request\",\"payload\":{\"conflictingIntentId\":\""
                        + intentId + "\",\"proposal\":\"agree on API v1\"}}}}";
        String requestResponse = requester.handleMessage(request);
        assertFalse(requestResponse.contains("-32603"));
        assertTrue(requestResponse.contains("CONTRACT"));
        assertTrue(requestResponse.contains("PENDING"));
        String participantMarker = "\\\"id\\\":\\\"agt_";
        int firstParticipant = statusResponse.indexOf(participantMarker);
        int secondParticipant = statusResponse.indexOf(participantMarker,
                firstParticipant + participantMarker.length());
        String targetParticipant = secondParticipant >= 0
                ? "agt_" + statusResponse.substring(secondParticipant + participantMarker.length(),
                secondParticipant + participantMarker.length() + 36)
                : "";
        String handoff =
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"request_coordination\",\"arguments\":{\"kind\":\"handoff\",\"payload\":{\"intentId\":\""
                        + intentId + "\",\"targetParticipant\":\"" + targetParticipant
                        + "\",\"proposal\":\"clean-worktree-claim-only\"}}}}";
        String handoffResponse = owner.handleMessage(handoff);
        assertFalse(handoffResponse.contains("-32603"));
        assertTrue(handoffResponse.contains("HANDOFF"));
        assertTrue(handoffResponse.contains("PENDING"));
    }

    @Test
    void firstVerifiedEnsureSessionCreatesLeaseBeforeClaims() throws Exception {
        String connection = "conn-first-lease";
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"lease first\",\"acceptance\":\"recoverable\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/lease-first.txt\"}]}}}}";
        assertTrue(handler.handleMessage(ensure)
                .contains("ready"));
        Path lease = SessionLeaseStore.resolveLeasesDirectory(tempRoot)
                .resolve(connection + ".json");
        assertTrue(Files.exists(lease), "verified ensure_session must establish a lease");
        assertTrue(Files.readString(lease)
                .contains("ACTIVE"));
    }

    @Test
    void cleanlyClosedSessionCanEstablishANewLaneGeneration() {
        String connection = "conn-fenced-epoch";
        McpProtocolHandler first = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{\"task\":{\"goal\":\"fence epoch\",\"acceptance\":\"release\",\"claims\":[{\"kind\":\"path_exact\",\"path\":\"tests/fenced-epoch.txt\"}]}}}}";
        assertTrue(first.handleMessage(ensure)
                .contains("ready"));
        first.close();

        McpProtocolHandler returning = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String response = returning.handleMessage(ensure.replace("\"id\":1", "\"id\":2"));
        assertTrue(response.contains("ready"), response);
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

        String req = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"unknown_tool\"}}";
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
        McpProtocolHandler handler = new McpProtocolHandler(sessionService,
                dummyCwd,
                "antigravity",
                "conn-antigravity-1");

        String initReq =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"" + tempRoot.toUri()
                        + "\"}}";
        String responseJson = handler.handleMessage(initReq);

        assertNotNull(responseJson);
        assertEquals(tempRoot, handler.activeProjectRoot());

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);
        assertTrue(callResponse.contains("ready"));
    }

    @Test
    void testInitializeWithWorkspaceFoldersBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService,
                dummyCwd,
                "antigravity",
                "conn-antigravity-2");

        String initReq =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"workspaceFolders\":[{\"uri\":\""
                        + tempRoot.toUri() + "\",\"name\":\"test\"}]}}";
        handler.handleMessage(initReq);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testInitializeWithRootsBindsProjectRoot() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService,
                dummyCwd,
                "antigravity",
                "conn-antigravity-3");

        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"roots\":[{\"uri\":\""
                + tempRoot.toUri() + "\",\"name\":\"test\"}]}}";
        handler.handleMessage(initReq);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    @SuppressWarnings("ExtractMethodRecommender")
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
        McpProtocolHandler handler = new McpProtocolHandler(sessionService,
                tempRoot.getParent(),
                "antigravity",
                "conn-ambiguous");

        String initReq =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"workspaceFolders\":[{\"uri\":\""
                        + tempRoot.toUri() + "\"},{\"uri\":\"" + secondProject.toUri() + "\"}]}}";
        handler.handleMessage(initReq);

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);

        assertTrue(callResponse.contains("retry_required"));
        assertTrue(callResponse.contains("workspace_not_ready"));
    }

    @Test
    void testWorktreeRootPathIsNotAcceptedAsControlProject() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "codex", "conn-1");
        handler.setAntigravityProjectsDir(Files.createTempDirectory("synesis-test-empty-projects-"));
        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        handler.handleMessage(callReq);

        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(tempRoot,
                "codex",
                "conn-1",
                null,
                false);
        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();

        McpProtocolHandler worktreeHandler = new McpProtocolHandler(sessionService,
                tempRoot.getParent(),
                "antigravity",
                "conn-wt-test");
        worktreeHandler.setAntigravityProjectsDir(Files.createTempDirectory("synesis-test-empty-projects-2-"));
        String initReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"rootUri\":\""
                + worktreePath.toUri() + "\"}}";
        worktreeHandler.handleMessage(initReq);

        String wtCallResponse = worktreeHandler.handleMessage(callReq);
        assertTrue(wtCallResponse.contains("retry_required"));
    }

    @Test
    void testMissingProjectJsonReturnsWorkspaceNotReady() throws Exception {
        Path uninit = Files.createTempDirectory("synesis-uninit-root-");
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, uninit, "antigravity", "conn-uninit");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String callResponse = handler.handleMessage(callReq);

        assertTrue(callResponse.contains("retry_required"));
        assertTrue(callResponse.contains("workspace_not_ready"));
    }

    @Test
    void testEncodedWindowsFileUriAndUriWithSpacesParsedCleanly() {
        Path p1 = McpProtocolHandler.parseUriOrPath("file:///C:/My%20Test%20Folder/project");
        assertNotNull(p1);
        assertTrue(p1.toString()
                .contains("My Test Folder"));

        Path p2 = McpProtocolHandler.parseUriOrPath("file:///c%3A/Users/Liparakis/Desktop/SynesisTestProject");
        assertNotNull(p2);
        assertTrue(p2.toString()
                .replace('\\', '/')
                .toLowerCase()
                .contains("synesistestproject"));

        Path absoluteUnixUri = McpProtocolHandler.parseUriOrPath(tempRoot.toUri()
                .toString());
        assertEquals(tempRoot, absoluteUnixUri);
    }

    @Test
    void testRootsListChangedNotificationUpdatesUnboundContext() {
        AgentSessionService sessionService = new AgentSessionService();
        Path dummyCwd = tempRoot.getParent();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, dummyCwd, "antigravity", "conn-changed-1");

        String notif =
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\""
                        + tempRoot.toUri() + "\"}]}}";
        handler.handleMessage(notif);

        assertEquals(tempRoot, handler.activeProjectRoot());
    }

    @Test
    void testConflictingRootNotificationAfterSessionBindingIsRejected() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "antigravity", "conn-bound-1");

        String callReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String response = handler.handleMessage(callReq);
        assertTrue(response.contains("ready"));

        Path conflicting = tempRoot.getParent();
        String notif =
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{\"workspaceFolders\":[{\"uri\":\""
                        + conflicting.toUri() + "\"}]}}";
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
        assertTrue(responseJson.contains(tempRoot.getFileName()
                .toString()));
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
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\"}}\n";

        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        McpStdioServer server = new McpStdioServer(handler, in, new PrintStream(out), new PrintStream(err));
        int exitCode = server.run();

        assertEquals(0, exitCode);
        assertEquals(0, err.size()); // Stderr clean

        String stdoutString = out.toString(StandardCharsets.UTF_8);
        List<String> lines = stdoutString.lines()
                .toList();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0)
                .contains("\"id\":1"));
        assertTrue(lines.get(1)
                .contains("\"id\":2"));
        assertTrue(lines.get(2)
                .contains("\"id\":3"));
    }

    @Test
    void stdioTransportFailureDurablyDisconnectsTerminalLease() {
        String connection = "conn-stdio-abnormal";
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), tempRoot, "codex", connection);
        String ensure = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(handler.handleMessage(ensure)
                .contains("ready"));
        assertTrue(new org.synesis.workspace.lifecycle.lease.SessionLeaseService()
                .markTerminalAuthorityConfirmed(tempRoot, connection));

        java.io.InputStream failingInput = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("synthetic transport failure");
            }
        };
        McpStdioServer server = new McpStdioServer(handler, failingInput,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, server.run());
        assertEquals(org.synesis.workspace.lifecycle.lease.SessionLeaseState.TERMINAL_DISCONNECTED,
                new SessionLeaseStore().load(tempRoot, connection)
                        .orElseThrow()
                        .leaseState());
    }

    @Test
    void testMcpReadFileAndApplyPatchEndToEnd() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "antigravity", "conn-mcp-tools");

        // 1. Ensure Session
        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        String ensureResp = handler.handleMessage(ensureReq);
        assertTrue(ensureResp.contains("ready"));

        // 2. Apply Patch Create Mode
        String createReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"create\":true,\"content\":\"Hello MCP World\\n\"}}}";
        String createResp = handler.handleMessage(createReq);
        assertTrue(createResp.contains("completed"));
        assertTrue(createResp.contains("src/McpFile.txt"));

        // 3. Read File
        String readReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"src/McpFile.txt\"}}}";
        String readResp = handler.handleMessage(readReq);
        assertTrue(readResp.contains("completed"));
        assertTrue(readResp.contains("Hello MCP World"));

        // 4. Apply Patch Modify Mode
        byte[] bytes = "Hello MCP World\n".getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String modifyReq =
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"path\":\"src/McpFile.txt\",\"expectedHash\":\""
                        + hash
                        + "\",\"edits\":[{\"find\":\"World\",\"replace\":\"Antigravity\",\"expectedOccurrences\":1}]}}}";
        String modifyResp = handler.handleMessage(modifyReq);
        assertTrue(modifyResp.contains("completed"));

        // 5. Read Modified File
        String readModResp = handler.handleMessage(readReq);
        assertTrue(readModResp.contains("Hello MCP Antigravity"));

        // 6. Run Command (direct argv)
        String runCmdReq = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"run_command\",\"arguments\":{\"argv\":[\"git\",\"status\",\"--porcelain\"]}}}";
        String runCmdResp = handler.handleMessage(runCmdReq);
        assertTrue(runCmdResp.contains("completed"), runCmdResp);
        assertTrue(runCmdResp.contains("stdoutBytesRead"));

        // 7. Get Next Action
        String nextActionReq = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"get_next_action\",\"arguments\":{}}}";
        String nextActionResp = handler.handleMessage(nextActionReq);
        assertTrue(nextActionResp.contains("ready"));
        assertTrue(nextActionResp.contains("pending"));
        assertTrue(nextActionResp.contains("actionId"));
        assertTrue(nextActionResp.contains("AT_LEAST_ONCE"));
        String nextActionAgain = handler.handleMessage(nextActionReq);
        Matcher matcher = Pattern.compile("actionId[^0-9a-f]*([0-9a-f]{8}-[0-9a-f-]{27})")
                .matcher(nextActionResp);
        assertTrue(matcher.find());
        assertTrue(nextActionAgain.contains(matcher.group(1)));
    }

    @Test
    void runCommandWithoutClaimsRenewsLeaseAndPersistsTerminalResult() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "antigravity",
                "conn-mcp-no-claims");

        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(handler.handleMessage(ensureReq)
                .contains("ready"));

        String runReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_command\",\"arguments\":{\"argv\":[\"git\",\"status\",\"--porcelain\"]}}}";
        String response = handler.handleMessage(runReq);

        assertTrue(response.contains("completed"), response);
        assertFalse(response.contains("LEASE_RENEWAL_FAILED"), response);
    }

    @Test
    void providerShapedPathAliasesRemainRevisionChecked() {
        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, tempRoot, "antigravity", "conn-aliases");

        String ensureReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ensure_session\",\"arguments\":{}}}";
        assertTrue(handler.handleMessage(ensureReq)
                .contains("ready"));

        String createReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_patch\",\"arguments\":{\"relativePath\":\"src/alias.txt\",\"create\":true,\"newContent\":\"alias\"}}}";
        String createResp = handler.handleMessage(createReq);
        assertTrue(createResp.contains("completed"));

        String readReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"relativePath\":\"src/alias.txt\"}}}";
        String readResp = handler.handleMessage(readReq);
        assertTrue(readResp.contains("completed"));
        assertTrue(readResp.contains("alias"));
    }
}
