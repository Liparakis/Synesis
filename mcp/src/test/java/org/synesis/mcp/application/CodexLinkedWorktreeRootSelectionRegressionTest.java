package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.GitProcessRunner;

/**
 * Reproduces Codex MCP root selection when the provider launches from a
 * normal linked Git worktree but the launcher is pinned to the control root.
 */
class CodexLinkedWorktreeRootSelectionRegressionTest {

    private String previousUserHome;
    private String previousLauncher;
    private String previousMcpLauncher;
    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        previousLauncher = System.getProperty("synesis.launcher");
        previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
        System.setProperty("user.home", Files.createTempDirectory("synesis-linked-provider-home-").toString());
        System.setProperty("synesis.launcher", Files.createTempFile("synesis-linked-launcher-", ".cmd").toString());
        System.setProperty("synesis.mcp.launcher", Files.createTempFile("synesis-linked-mcp-", ".exe").toString());
        fixture = createFixture();
    }

    @AfterEach
    void restoreSystemProperties() {
        restoreProperty("user.home", previousUserHome);
        restoreProperty("synesis.launcher", previousLauncher);
        restoreProperty("synesis.mcp.launcher", previousMcpLauncher);
    }

    @Test
    void linkedWorktreeCannotReplacePinnedControlRootDuringMcpInitialize() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                fixture.controlRoot(),
                "codex",
                "codex-linked-root-regression",
                true);

        String initialize = initializeRequest(fixture.linkedWorktree());
        String ensure = ensureSessionRequest();

        handler.handleMessage(initialize);
        String response = toolText(handler.handleMessage(ensure));

        assertAll(
                () -> assertEquals(fixture.controlRoot(), handler.activeProjectRoot(),
                        "the launcher's pinned control root must remain authoritative"),
                () -> assertTrue(response.contains("\"status\":\"ready\""), response),
                () -> assertTrue(response.contains("\"workspace\":\"isolated\""), response));
    }

    @Test
    void unrelatedInitializedRepositoryCannotReplacePinnedControlRoot() throws Exception {
        Path unrelated = Files.createTempDirectory("synesis-unrelated-root-");
        initializeGitRepository(unrelated, "Unrelated repository");
        new ProjectApplicationService().init(unrelated);

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                fixture.controlRoot(),
                "codex",
                "codex-unrelated-root-regression",
                true);

        handler.handleMessage(initializeRequest(unrelated));
        String response = toolText(handler.handleMessage(ensureSessionRequest()));

        assertAll(
                () -> assertNotEquals(unrelated, handler.activeProjectRoot(),
                        "an unrelated MCP root must not override the pinned control root"),
                () -> assertTrue(response.contains("\"status\":\"retry_required\""), response),
                () -> assertTrue(response.contains("\"reason\":\"workspace_not_ready\""), response),
                () -> assertFalse(response.contains("\"status\":\"ready\""), response));
    }

    @Test
    void synesisAssignedWorktreeCannotReplacePinnedControlRoot() throws Exception {
        AgentSessionService sessions = new AgentSessionService();
        Path assigned = sessions.resolveSessionContext(new AgentSessionService.SessionResolutionRequest(
                        fixture.controlRoot(), "codex", "prepare-synesis-assigned-root", null, false))
                .worktreePath();
        assertTrue(Files.isRegularFile(assigned.resolve(".synesis/local/workspace-binding.json")));

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                fixture.controlRoot(),
                "codex",
                "codex-assigned-root-regression",
                true);

        handler.handleMessage(initializeRequest(assigned));
        String response = toolText(handler.handleMessage(ensureSessionRequest()));

        assertAll(
                () -> assertNotEquals(assigned, handler.activeProjectRoot(),
                        "a Synesis-assigned worktree must never become the control root"),
                () -> assertTrue(response.contains("\"status\":\"retry_required\""), response),
                () -> assertTrue(response.contains("\"reason\":\"workspace_not_ready\""), response));
    }

    @Test
    void sameProjectIdInDifferentRepositoryCannotConfirmPinnedControlRoot() throws Exception {
        Path unrelated = Files.createTempDirectory("synesis-copied-project-id-");
        initializeGitRepository(unrelated, "Copied project identity");
        new ProjectApplicationService().init(unrelated);
        Files.copy(fixture.controlRoot().resolve(".synesis/project.json"),
                unrelated.resolve(".synesis/project.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertRootMismatch(unrelated, "codex-different-repository-regression");
    }

    @Test
    void differentProjectIdInSameRepositoryCannotConfirmPinnedControlRoot() throws Exception {
        Path metadata = fixture.linkedWorktree().resolve(".synesis/project.json");
        String changed = Files.readString(metadata).replaceFirst(
                "(?<=\\\"projectId\\\": \\\")[0-9a-f-]+",
                UUID.randomUUID().toString());
        Files.writeString(metadata, changed);

        assertRootMismatch(fixture.linkedWorktree(), "codex-different-project-id-regression");
    }

    @Test
    void pinnedRootNotificationsCannotRedirectConnection() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(),
                fixture.controlRoot(),
                "codex",
                "codex-pinned-notification-regression",
                true);

        handler.handleMessage(rootsChangedNotification(fixture.linkedWorktree()));
        assertEquals(fixture.controlRoot(), handler.activeProjectRoot());
        String ready = toolText(handler.handleMessage(ensureSessionRequest()));
        assertTrue(ready.contains("\"status\":\"ready\""), ready);

        Path unrelated = Files.createTempDirectory("synesis-notification-conflict-");
        handler.handleMessage(rootsChangedNotification(unrelated));
        assertEquals(fixture.controlRoot(), handler.activeProjectRoot());
    }

    @Test
    void missingIntegrationIsStillEvaluatedAtPinnedControlRoot() throws Exception {
        Path control = Files.createTempDirectory("synesis-uninstalled-control-");
        initializeGitRepository(control, "Uninstalled control");
        new ProjectApplicationService().init(control);
        Path linked = Files.createTempDirectory("synesis-uninstalled-linked-parent-")
                .resolve("linked");
        GitProcessRunner.run(control, "worktree", "add", linked.toString(), "HEAD");

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), control, "codex",
                "codex-uninstalled-linked-regression", true);
        handler.handleMessage(initializeRequest(linked));
        String response = toolText(handler.handleMessage(ensureSessionRequest()));

        assertAll(
                () -> assertEquals(control, handler.activeProjectRoot()),
                () -> assertTrue(response.contains("\"reason\":\"provider_integration_required\""), response));
    }

    @Test
    void linkedRootInspectionDoesNotRepairGitExclusions() throws Exception {
        Path common = Path.of(GitProcessRunner.run(fixture.controlRoot(),
                "rev-parse", "--path-format=absolute", "--git-common-dir").trim());
        Path exclude = common.resolve("info/exclude");
        byte[] before = Files.readAllBytes(exclude);

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), fixture.controlRoot(),
                "codex", "codex-read-only-root-inspection", true);
        handler.handleMessage(initializeRequest(fixture.linkedWorktree()));

        assertArrayEquals(before, Files.readAllBytes(exclude));
    }

    @Test
    void invalidExplicitPinCannotDowngradeToCandidateDiscovery() throws Exception {
        Path invalidPin = Files.createTempDirectory("synesis-invalid-explicit-pin-");
        Path unrelated = Files.createTempDirectory("synesis-valid-candidate-");
        initializeGitRepository(unrelated, "Valid unrelated candidate");
        new ProjectApplicationService().init(unrelated);

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), invalidPin, "codex",
                "codex-invalid-pin-regression", true);
        handler.handleMessage(initializeRequest(unrelated));
        String response = toolText(handler.handleMessage(ensureSessionRequest()));

        assertAll(
                () -> assertEquals(invalidPin, handler.activeProjectRoot()),
                () -> assertTrue(response.contains("\"reason\":\"workspace_not_ready\""), response),
                () -> assertTrue(response.contains("PROJECT_ROOT_PIN_INVALID"), response));
    }

    @Test
    void absentExplicitPinRetainsLegacyUnambiguousDiscovery() throws Exception {
        Path defaultRoot = Files.createTempDirectory("synesis-default-unpinned-root-");
        Path discovered = Files.createTempDirectory("synesis-unpinned-candidate-");
        initializeGitRepository(discovered, "Discovered candidate");
        new ProjectApplicationService().init(discovered);

        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), defaultRoot, "codex",
                "codex-unpinned-discovery-regression", false);
        handler.handleMessage(initializeRequest(discovered));

        assertEquals(discovered, handler.activeProjectRoot());
    }

    @Test
    void reservedSynesisWorkspacePathIsRejectedWithoutMarker() {
        Path markerlessAssignedPath = AdministrativeStateLocator.applicationStateRoot()
                .resolve("workspaces/test-project/worktrees/session-marker-missing");

        assertTrue(McpProjectRootAuthority.isAssignedWorkspace(markerlessAssignedPath));
    }

    @Test
    void rootAuthorityFailureRemainsLatchedForConnection() throws Exception {
        Path unrelated = Files.createTempDirectory("synesis-latched-conflict-");
        initializeGitRepository(unrelated, "Latched conflict");
        new ProjectApplicationService().init(unrelated);
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), fixture.controlRoot(),
                "codex", "codex-latched-root-conflict", true);

        handler.handleMessage(initializeRequest(unrelated));
        handler.handleMessage(rootsChangedNotification(fixture.linkedWorktree()));
        String response = toolText(handler.handleMessage(ensureSessionRequest()));

        assertAll(
                () -> assertEquals(fixture.controlRoot(), handler.activeProjectRoot()),
                () -> assertTrue(response.contains("PROJECT_ROOT_PIN_MISMATCH"), response));
    }

    private static Fixture createFixture() throws Exception {
        Path controlRoot = Files.createTempDirectory("synesis-linked-control-");
        initializeGitRepository(controlRoot, "Control repository");
        ProjectApplicationService projectService = new ProjectApplicationService();
        ProjectApplicationService.ProjectLocation location = projectService.init(controlRoot).location();
        McpProviderTestSupport.install(location, "codex");

        Path linkedWorktree = Files.createTempDirectory("synesis-linked-worktree-parent-")
                .resolve("codex-linked-worktree");
        GitProcessRunner.run(controlRoot, "worktree", "add", linkedWorktree.toString(), "HEAD");

        String controlCommonDirectory = GitProcessRunner.run(controlRoot,
                "rev-parse", "--path-format=absolute", "--git-common-dir").trim();
        String linkedCommonDirectory = GitProcessRunner.run(linkedWorktree,
                "rev-parse", "--path-format=absolute", "--git-common-dir").trim();
        assertAll(
                () -> assertEquals(Path.of(controlCommonDirectory).toAbsolutePath().normalize(),
                        Path.of(linkedCommonDirectory).toAbsolutePath().normalize()),
                () -> assertTrue(Files.isRegularFile(linkedWorktree.resolve(".git"))),
                () -> assertFalse(Files.exists(linkedWorktree.resolve(".synesis/local"))),
                () -> assertFalse(Files.exists(linkedWorktree.resolve(".codex/hooks.json"))),
                () -> assertTrue(Files.exists(controlRoot.resolve(".synesis/local/providers/codex.json"))));
        return new Fixture(controlRoot, linkedWorktree);
    }

    private static void initializeGitRepository(Path root, String readmeTitle) throws Exception {
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "config", "user.name", "Synesis Test");
        GitProcessRunner.run(root, "config", "user.email", "synesis-test@example.com");
        Files.writeString(root.resolve("README.md"), "# " + readmeTitle + "\n");
        GitProcessRunner.run(root, "add", ".");
        GitProcessRunner.run(root, "commit", "-m", "Initial commit");
    }

    private static String initializeRequest(Path advertisedRoot) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\""
                + advertisedRoot.toUri() + "\"}}";
    }

    private void assertRootMismatch(Path advertisedRoot, String connection) {
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), fixture.controlRoot(),
                "codex", connection, true);
        handler.handleMessage(initializeRequest(advertisedRoot));
        String response = toolText(handler.handleMessage(ensureSessionRequest()));
        assertAll(
                () -> assertEquals(fixture.controlRoot(), handler.activeProjectRoot()),
                () -> assertTrue(response.contains("\"status\":\"retry_required\""), response),
                () -> assertTrue(response.contains("\"reason\":\"workspace_not_ready\""), response),
                () -> assertTrue(response.contains("PROJECT_ROOT_PIN_MISMATCH"), response));
    }

    private static String rootsChangedNotification(Path advertisedRoot) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\",\"params\":{"
                + "\"workspaceFolders\":[{\"uri\":\"" + advertisedRoot.toUri() + "\"}]}}";
    }

    private static String ensureSessionRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"ensure_session\",\"arguments\":{}}}";
    }

    @SuppressWarnings("unchecked")
    private static String toolText(String response) {
        Map<String, Object> envelope = (Map<String, Object>) ProviderJson.parse(response);
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        List<Object> content = (List<Object>) result.get("content");
        return (String) ((Map<String, Object>) content.getFirst()).get("text");
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private record Fixture(Path controlRoot, Path linkedWorktree) {
    }
}
