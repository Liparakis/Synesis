package org.synesis.workspace.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

class AgentSessionServiceTest {

    private ProjectApplicationService projectService;
    private AgentSessionService sessionService;
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
        tempRoot = Files.createTempDirectory("synesis-session-test-");
        git(tempRoot, "init");
        git(tempRoot, "config", "user.name", "Test User");
        git(tempRoot, "config", "user.email", "test@example.com");
        Files.writeString(tempRoot.resolve("README.md"), "# Test Repo\n");
        git(tempRoot, "add", ".");
        git(tempRoot, "commit", "-m", "Initial commit");

        projectService = new ProjectApplicationService();
        projectService.init(tempRoot);
        sessionService = new AgentSessionService();
    }

    @Test
    void testValidInitializedProjectResolvesSuccessfully() throws Exception {
        AgentSessionService.SessionResolutionRequest request = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-1", null, false);

        AgentSessionService.AgentSessionContext context = sessionService.resolveSessionContext(request);

        assertNotNull(context);
        assertNotNull(context.sessionId());
        assertNotNull(context.workerId());
        assertNotNull(context.supervisorId());
        assertNotNull(context.worktreePath());
        assertEquals("WORKSPACE_UNVERIFIED", context.providerTrustState());
        assertEquals("VERIFIED", context.binding().verificationState());
        assertTrue(context.isIsolatedWorkspace());
    }

    @Test
    void testSameConnectionInstanceResumesSameSession() throws Exception {
        AgentSessionService.SessionResolutionRequest req1 = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-same", null, false);
        AgentSessionService.SessionResolutionRequest req2 = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-same", null, false);

        AgentSessionService.AgentSessionContext ctx1 = sessionService.resolveSessionContext(req1);
        AgentSessionService.AgentSessionContext ctx2 = sessionService.resolveSessionContext(req2);

        assertEquals(ctx1.sessionId(), ctx2.sessionId());
        assertEquals(ctx1.workerId(), ctx2.workerId());
        assertEquals(ctx1.supervisorId(), ctx2.supervisorId());
        assertEquals(ctx1.worktreePath(), ctx2.worktreePath());
    }

    @Test
    void testTwoConnectionInstancesCreateDifferentSessionsWorkersAndWorktrees() throws Exception {
        AgentSessionService.SessionResolutionRequest req1 = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-A", null, false);
        AgentSessionService.SessionResolutionRequest req2 = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-B", null, false);

        AgentSessionService.AgentSessionContext ctx1 = sessionService.resolveSessionContext(req1);
        AgentSessionService.AgentSessionContext ctx2 = sessionService.resolveSessionContext(req2);

        assertNotEquals(ctx1.sessionId(), ctx2.sessionId());
        assertNotEquals(ctx1.workerId(), ctx2.workerId());
        assertNotEquals(ctx1.worktreePath(), ctx2.worktreePath());
    }

    @Test
    void testCodexAndAntigravityBindingsRemainDistinct() throws Exception {
        AgentSessionService.SessionResolutionRequest codexReq = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-shared-id", null, false);
        AgentSessionService.SessionResolutionRequest agReq = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "antigravity", "conn-instance-shared-id", null, false);

        AgentSessionService.AgentSessionContext codexCtx = sessionService.resolveSessionContext(codexReq);
        AgentSessionService.AgentSessionContext agCtx = sessionService.resolveSessionContext(agReq);

        assertNotEquals(codexCtx.sessionId(), agCtx.sessionId());
        assertEquals("codex", codexCtx.binding().provider());
        assertEquals("antigravity", agCtx.binding().provider());
    }

    @Test
    void testEnsureSessionOutputIsConciseAndContainsNoInternalIdsOrPaths() throws Exception {
        AgentSessionService.AgentTaskIntent intent = new AgentSessionService.AgentTaskIntent(
                "Implement feature", "Tests pass", List.of("catalog"), List.of());
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-123", intent, false);

        AgentResponse response = sessionService.ensureSession(req);

        assertEquals(AgentStatus.READY, response.status());
        String json = response.toJson();

        assertTrue(json.contains("\"status\":\"ready\""));
        assertTrue(json.contains("\"workspace\":\"isolated\""));
        assertTrue(json.contains("\"pending\":0"));

        assertFalse(json.contains("sessionId"));
        assertFalse(json.contains("workerId"));
        assertFalse(json.contains("supervisorId"));
        assertFalse(json.contains("projectId"));
        assertFalse(json.contains("nodeId"));
        assertFalse(json.contains("worktreePath"));
        assertFalse(json.contains("conn-instance"));
        assertFalse(json.contains(tempRoot.toString()));
    }

    @Test
    void testUninitializedProjectReturnsRetryRequiredResponse() throws Exception {
        Path uninit = Files.createTempDirectory("synesis-uninit-");
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                uninit, "codex", "conn-instance-1", null, false);

        AgentResponse response = sessionService.ensureSession(req);

        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
        assertEquals(AgentReason.WORKSPACE_NOT_READY, response.reason());
        assertEquals(AgentNextAction.ENSURE_SESSION, response.nextAction());
    }

    @Test
    void testWorktreeAsProjectRootFailsClosed() throws Exception {
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                tempRoot, "codex", "conn-instance-1", null, false);
        AgentSessionService.AgentSessionContext ctx = sessionService.resolveSessionContext(req);

        Path worktreePath = ctx.worktreePath();
        AgentSessionService.SessionResolutionRequest invalidReq = new AgentSessionService.SessionResolutionRequest(
                worktreePath, "codex", "conn-instance-2", null, false);

        AgentResponse response = sessionService.ensureSession(invalidReq);

        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
    }

    @Test
    void testTaskIntentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new AgentSessionService.AgentTaskIntent(
                "a".repeat(4097), "ok", List.of(), List.of()));
    }
}
