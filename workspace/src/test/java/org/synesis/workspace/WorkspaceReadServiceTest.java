package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.workspace.WorkspaceReadService;

class WorkspaceReadServiceTest {

    private Path controlRoot;

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
        controlRoot = Files.createTempDirectory("synesis-read-test-");
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");

        Files.createDirectories(controlRoot.resolve("src"));
        Files.writeString(controlRoot.resolve("src/Product.java"), "line1\nline2\nline3\nline4\nline5\n");
        byte[] binaryBytes = new byte[] { 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x00, 0x57, 0x6F, 0x72, 0x6C, 0x64 };
        Files.write(controlRoot.resolve("src/image.bin"), binaryBytes);

        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(controlRoot);
    }

    @Test
    void testReadsAssignedWorktreeOnlyAndNeverReadsControlCheckout() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-1", null, false);
        sessionService.ensureSession(req);

        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();
        assertNotNull(worktreePath);

        // Modify file in worktree
        Files.writeString(worktreePath.resolve("src/Product.java"), "worktree_line1\nworktree_line2\n");

        WorkspaceReadService readService = new WorkspaceReadService();
        WorkspaceReadService.ReadRequest readReq = new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-1", "src/Product.java", 1, 10, 65536);

        AgentResponse response = readService.readFile(readReq);
        assertEquals(AgentStatus.COMPLETED, response.status());

        String json = response.toJson();
        assertTrue(json.contains("worktree_line1"));
        assertTrue(json.contains("contentHash"));
        assertFalse(json.contains("line3")); // Original control content was not read

        // Verify control checkout is unchanged
        String controlContent = Files.readString(controlRoot.resolve("src/Product.java"));
        assertTrue(controlContent.contains("line1\nline2\nline3"));
    }

    @Test
    void reportsValidMissingClaimedFileAsCreateable() {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-missing", null, false);
        sessionService.ensureSession(req);

        AgentResponse response = new WorkspaceReadService().readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-missing", "src/NewTracker.py", 1, 10, 65536));

        assertEquals(AgentStatus.COMPLETED, response.status());
        assertTrue(response.toJson().contains("\"exists\":false"));
        assertTrue(response.toJson().contains("\"createAllowed\":true"));
        assertTrue(response.toJson().contains("\"contentHash\":\"\""));
    }

    @Test
    void testRejectsAbsolutePathsAndPathTraversal() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-2", null, false);
        sessionService.ensureSession(req);

        WorkspaceReadService readService = new WorkspaceReadService();

        // Absolute path
        String absPath = controlRoot.resolve("src/Product.java").toAbsolutePath().toString();
        AgentResponse absResp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-2", absPath, 1, 10, 65536));
        assertEquals(AgentStatus.BLOCKED, absResp.status());
        assertTrue(absResp.toJson().contains("invalid_path"));

        // Traversal
        AgentResponse travResp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-2", "../src/Product.java", 1, 10, 65536));
        assertEquals(AgentStatus.BLOCKED, travResp.status());
        assertTrue(travResp.toJson().contains("invalid_path"));
    }

    @Test
    void testRejectsProtectedInternalState() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-3", null, false);
        sessionService.ensureSession(req);

        WorkspaceReadService readService = new WorkspaceReadService();

        String[] protectedPaths = {
            ".synesis/project.json",
            ".synesis/local/profile",
            ".git/HEAD",
            ".codex/mcp.json",
            ".agents/mcp.json"
        };

        for (String p : protectedPaths) {
            AgentResponse resp = readService.readFile(new WorkspaceReadService.ReadRequest(
                    controlRoot, "codex", "conn-read-3", p, 1, 10, 65536));
            assertEquals(AgentStatus.BLOCKED, resp.status(), "Path should be blocked: " + p);
            assertTrue(resp.toJson().contains("invalid_path"));
        }
    }

    @Test
    void testRejectsBinaryFiles() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-4", null, false);
        sessionService.ensureSession(req);

        WorkspaceReadService readService = new WorkspaceReadService();
        AgentResponse resp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-4", "src/image.bin", 1, 10, 65536));

        assertEquals(AgentStatus.BLOCKED, resp.status());
        assertTrue(resp.toJson().contains("invalid_path"));
    }

    @Test
    void testSupportsLineRangesAndEnforcesMaxBytesAndTruncation() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-5", null, false);
        sessionService.ensureSession(req);

        WorkspaceReadService readService = new WorkspaceReadService();

        // Line range 2..3
        AgentResponse rangeResp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-5", "src/Product.java", 2, 3, 65536));
        assertEquals(AgentStatus.COMPLETED, rangeResp.status());
        String rangeJson = rangeResp.toJson();
        assertTrue(rangeJson.contains("line2\\nline3"));
        assertTrue(rangeJson.contains("\"truncated\":true"));

        // MaxBytes bounding
        AgentResponse byteResp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-5", "src/Product.java", 1, 10, 8));
        assertEquals(AgentStatus.COMPLETED, byteResp.status());
        String byteJson = byteResp.toJson();
        assertTrue(byteJson.contains("\"truncated\":true"));

        // Full read (not truncated)
        AgentResponse fullResp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-5", "src/Product.java", 1, 100, 65536));
        assertEquals(AgentStatus.COMPLETED, fullResp.status());
        assertFalse(fullResp.toJson().contains("\"truncated\":true"));
    }

    @Test
    void testLeaksNoInternalIdsOrAbsolutePaths() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-read-6", null, false);
        sessionService.ensureSession(req);

        WorkspaceReadService readService = new WorkspaceReadService();
        AgentResponse resp = readService.readFile(new WorkspaceReadService.ReadRequest(
                controlRoot, "codex", "conn-read-6", "src/Product.java", 1, 10, 65536));

        String json = resp.toJson();
        assertFalse(json.contains("sessionId"));
        assertFalse(json.contains("worktreePath"));
        assertFalse(json.contains(controlRoot.toString()));
    }
}
