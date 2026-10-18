package org.synesis.workspace.application.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

/**
 * Exercises agent-session binding, renewal, and authority checks.
 */
class AgentSessionServiceTest {

    private AgentSessionService sessionService;
    private Path tempRoot;
    private String previousLauncher;
    private String previousMcpLauncher;

    private static void git(Path root, String... arguments) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, arguments);
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

        ProjectApplicationService projectService = new ProjectApplicationService();
        projectService.init(tempRoot);
        previousLauncher = System.getProperty("synesis.launcher");
        previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
        System.setProperty("synesis.launcher", Files.createTempFile("synesis-test-launcher-", ".bat").toString());
        System.setProperty("synesis.mcp.launcher", Files.createTempFile("synesis-test-mcp-", ".exe").toString());
        ProviderApplicationService providerService = new ProviderApplicationService();
        var installed = providerService.install(projectService.locate(tempRoot), "codex");
        assertTrue(installed.values().containsKey("PROVIDER_INSTALL_RESULT"));
        sessionService = new AgentSessionService();
    }

    @org.junit.jupiter.api.AfterEach
    void restoreProviderLauncherProperties() {
        if (previousLauncher == null) {
            System.clearProperty("synesis.launcher");
        } else {
            System.setProperty("synesis.launcher", previousLauncher);
        }
        if (previousMcpLauncher == null) {
            System.clearProperty("synesis.mcp.launcher");
        } else {
            System.setProperty("synesis.mcp.launcher", previousMcpLauncher);
        }
    }

    @Test
    void testValidInitializedProjectResolvesSuccessfully() throws Exception {
        SessionResolutionRequest request = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-1", null, false);

        AgentSessionContext context = sessionService.resolveSessionContext(request);

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
        SessionResolutionRequest req1 = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-same", null, false);
        SessionResolutionRequest req2 = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-same", null, false);

        AgentSessionContext ctx1 = sessionService.resolveSessionContext(req1);
        AgentSessionContext ctx2 = sessionService.resolveSessionContext(req2);

        assertEquals(ctx1.sessionId(), ctx2.sessionId());
        assertEquals(ctx1.workerId(), ctx2.workerId());
        assertEquals(ctx1.supervisorId(), ctx2.supervisorId());
        assertEquals(ctx1.worktreePath(), ctx2.worktreePath());
    }

    @Test
    void activeNoChangeSessionRecoversAfterTheControlBaseAdvances() throws Exception {
        String connection = "read-only-after-control-advance";
        var location = new ProjectApplicationService().locate(tempRoot);
        var bindings = new ProviderSessionBindingService();
        var original = bindings.ensure(location, "codex", connection).binding();
        String participant = WorkspaceCollaborationService.participantHandle(original.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntent intent = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                participant,
                "codex",
                UUID.randomUUID(),
                "Review an immutable candidate",
                "No repository mutation is required for this responsibility",
                original.baseCommit(),
                List.of(ResourceSelector.pathExact("review_report.md")),
                1,
                UUID.randomUUID(),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());

        Files.writeString(tempRoot.resolve("README.md"), "integrated sibling snapshot\n");
        git(tempRoot, "add", "README.md");
        git(tempRoot, "commit", "-m", "integrate sibling snapshot");

        SessionResolutionRequest request = new SessionResolutionRequest(tempRoot, "codex", connection, null, false);
        AgentResponse ensured = sessionService.ensureSession(request);
        var recovered = sessionService.resolveSessionContext(request);
        var ordinary = new WorkspaceReadinessService(bindings).assess(location, "codex", connection);

        assertEquals(AgentStatus.READY, ensured.status());
        assertEquals(original.sessionId(), recovered.sessionId());
        assertEquals(Path.of(original.worktreePath()), recovered.worktreePath());
        assertEquals(original.baseCommit(), recovered.baseCommit());
        assertFalse(ordinary.ready());
        assertEquals("CONTROL_BASE_ADVANCED", ordinary.internalReason());
    }

    @Test
    void testTwoConnectionInstancesCreateDifferentSessionsWorkersAndWorktrees() throws Exception {
        SessionResolutionRequest req1 = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-A", null, false);
        SessionResolutionRequest req2 = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-B", null, false);

        AgentSessionContext ctx1 = sessionService.resolveSessionContext(req1);
        AgentSessionContext ctx2 = sessionService.resolveSessionContext(req2);

        assertNotEquals(ctx1.sessionId(), ctx2.sessionId());
        assertNotEquals(ctx1.workerId(), ctx2.workerId());
        assertNotEquals(ctx1.worktreePath(), ctx2.worktreePath());
    }

    @Test
    void testCodexAndClaudeBindingsRemainDistinct() throws Exception {
        new ProviderApplicationService().install(new ProjectApplicationService().locate(tempRoot), "claude");
        SessionResolutionRequest codexReq = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-shared-id", null, false);
        SessionResolutionRequest agReq = new SessionResolutionRequest(tempRoot, "claude", "conn-instance-shared-id", null, false);

        AgentSessionContext codexCtx = sessionService.resolveSessionContext(codexReq);
        AgentSessionContext agCtx = sessionService.resolveSessionContext(agReq);

        assertNotEquals(codexCtx.sessionId(), agCtx.sessionId());
        assertEquals("codex", codexCtx.binding().provider());
        assertEquals("claude", agCtx.binding().provider());
    }

    @Test
    void testEnsureSessionOutputIsConciseAndContainsNoInternalIdsOrPaths() {
        AgentTaskIntent intent = new AgentTaskIntent("Implement feature", "Tests pass", List.of("catalog"), List.of());
        SessionResolutionRequest req = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-123", intent, false);

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
        SessionResolutionRequest req = new SessionResolutionRequest(uninit, "codex", "conn-instance-1", null, false);

        AgentResponse response = sessionService.ensureSession(req);

        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
        assertEquals(AgentReason.WORKSPACE_NOT_READY, response.reason());
        assertEquals(AgentNextAction.ENSURE_SESSION, response.nextAction());
    }

    @Test
    void initializedProjectWithoutProviderIntegrationIsBlockedBeforeBinding() throws Exception {
        Path uninstalled = Files.createTempDirectory("synesis-provider-required-");
        git(uninstalled, "init");
        git(uninstalled, "config", "user.name", "Test User");
        git(uninstalled, "config", "user.email", "test@example.com");
        Files.writeString(uninstalled.resolve("README.md"), "# Uninstalled provider\n");
        git(uninstalled, "add", ".");
        git(uninstalled, "commit", "-m", "Initial commit");
        new ProjectApplicationService().init(uninstalled);

        AgentResponse response = new AgentSessionService().ensureSession(new SessionResolutionRequest(uninstalled, "codex", "uninstalled-connection", null, false));

        assertEquals(AgentStatus.BLOCKED, response.status());
        assertEquals(AgentReason.PROVIDER_INTEGRATION_REQUIRED, response.reason());
        assertEquals(AgentNextAction.REQUEST_HUMAN_HELP, response.nextAction());
    }

    @Test
    void testWorktreeAsProjectRootFailsClosed() throws Exception {
        SessionResolutionRequest req = new SessionResolutionRequest(tempRoot, "codex", "conn-instance-1", null, false);
        AgentSessionContext ctx = sessionService.resolveSessionContext(req);

        Path worktreePath = ctx.worktreePath();
        SessionResolutionRequest invalidReq = new SessionResolutionRequest(worktreePath, "codex", "conn-instance-2", null, false);

        AgentResponse response = sessionService.ensureSession(invalidReq);

        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
    }

    @Test
    void aWorktreeThatCannotBeRefreshedWithoutRiskingItsWorkSendsTheAgentToItsLaneNotToAPerson() throws Exception {
        String connection = "stale-dirty";
        var location = new ProjectApplicationService().locate(tempRoot);
        var original = new ProviderSessionBindingService().ensure(location, "codex", connection).binding();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        assertTrue(new WorkIntentService(store, identity).announce(new WorkIntent(UUID.randomUUID(), location.projectId(), WorkspaceCollaborationService.participantHandle(original.sessionId()),
                "codex", UUID.randomUUID(), "Change the readme", "Readme changed", original.baseCommit(), List.of(ResourceSelector.pathExact("README.md")), 1,
                WorkIntent.Status.ANNOUNCED)).acquired());
        Path worktree = Path.of(original.worktreePath());
        Files.writeString(worktree.resolve("README.md"), "unpublished work\n");
        // main moves under the claimed file, so carrying the work across could lose it.
        Files.writeString(tempRoot.resolve("README.md"), "integrated elsewhere\n");
        git(tempRoot, "commit", "-am", "integrated elsewhere");

        AgentResponse answer = sessionService.ensureSession(new SessionResolutionRequest(tempRoot, "codex", connection, null, false));

        assertEquals(AgentStatus.BLOCKED, answer.status(), "s5-run8 answered internal_failure with request_human_help: " + answer.toJson());
        assertEquals(AgentReason.WORKSPACE_STALE, answer.reason(), answer.toJson());
        assertEquals(AgentNextAction.RETRY, answer.nextAction(), answer.toJson());
        java.util.Map<?, ?> result = (java.util.Map<?, ?>) answer.result();
        assertEquals("WORKSPACE_STALE_DIRTY", result.get("reason"));
        assertTrue(String.valueOf(result.get("instruction")).contains("Call get_next_action"), answer.toJson());
        assertEquals("unpublished work\n", Files.readString(worktree.resolve("README.md")), "the work is kept");
        assertEquals(original.worktreePath(), new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow().worktreePath());
    }

    @Test
    void testTaskIntentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskIntent("a".repeat(4097), "ok", List.of(), List.of()));
    }
}
