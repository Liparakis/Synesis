package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderRegistry;
import org.synesis.workspace.provider.ProviderIntegration;

/**
 * Verifies provider registry and isolated lifecycle behavior.
 */
final class ProviderApplicationServiceTest {

    private String previousUserHome;

    private static void git(Path root, String... arguments) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, arguments);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @BeforeEach
    void isolateProviderHome() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home",
                Files.createTempDirectory("provider-test-home-")
                        .toString());
    }

    @AfterEach
    void restoreProviderHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void registryIsDeterministicAndListsCodexAsExperimental() {
        assertEquals(java.util.List.of("claude", "codex"),
                ProviderRegistry.providers()
                        .stream()
                        .map(ProviderIntegration::id)
                        .toList());
        assertNull(ProviderRegistry.find("claude-code"));
        assertEquals(org.synesis.workspace.provider.ProviderSupportLevel.EXPERIMENTAL,
                ProviderRegistry.find("codex")
                        .supportLevel());
        assertEquals(org.synesis.workspace.provider.ProviderMcpEvidenceTier.MCP_CONFIRMED_WORKING,
                ProviderRegistry.find("codex")
                        .mcpEvidenceTier());
        assertEquals(org.synesis.workspace.provider.ProviderMcpEvidenceTier.MCP_CONFIRMED_WORKING,
                ProviderRegistry.find("claude")
                        .mcpEvidenceTier());
        assertEquals("REVIEW_REQUIRED",
                ProviderRegistry.find("codex")
                        .trustStatus());
        assertTrue(ProviderRegistry.find("codex")
                .requiresRealValidation());
    }

    @Test
    void malformedConfigurationIsNotOverwritten() throws Exception {
        Path root = Files.createTempDirectory("provider-malformed-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            var location = new ProjectApplicationService().init(root)
                    .location();
            Path config = root.resolve(".claude/settings.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{broken");
            var result = new ProviderApplicationService().install(location, "claude");
            assertEquals("INVALID_CONFIG",
                    result.values()
                            .get("PROVIDER_INSTALL_RESULT"));
            assertEquals("{broken", Files.readString(config));
        } finally {
            if (previous == null) {
                System.clearProperty("synesis.launcher");
            } else {
                System.setProperty("synesis.launcher", previous);
            }
        }
    }

    @Test
    void codexLifecycleUsesProjectHookShapeAndPreservesUnrelatedConfiguration() throws Exception {
        Path root = Files.createTempDirectory("codex-provider-lifecycle-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            var location = new ProjectApplicationService().init(root)
                    .location();
            Path config = root.resolve(".codex/hooks.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\"unrelated\":{\"value\":true},\"hooks\":{\"Stop\":[]}}\n");
            ProviderApplicationService service = new ProviderApplicationService();

            var installed = service.install(location, "codex");
            assertEquals("DEGRADED",
                    installed.values()
                            .get("PROVIDER_INSTALL_RESULT"));
            assertEquals("REVIEW_REQUIRED",
                    installed.values()
                            .get("TRUST_STATUS"));
            Map<?, ?> merged = (Map<?, ?>) ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) merged.get("unrelated")).get("value"));
            assertFalse(Files.readString(config)
                    .contains("versions"));
            Map<?, ?> hooks = (Map<?, ?>) merged.get("hooks");
            assertTrue(hooks.containsKey("Stop"));
            Map<?, ?> preToolUse = (Map<?, ?>) ((java.util.List<?>) hooks.get("PreToolUse")).getFirst();
            Map<?, ?> handler = (Map<?, ?>) ((java.util.List<?>) preToolUse.get("hooks")).getFirst();
            assertTrue(String.valueOf(handler.get("commandWindows"))
                    .startsWith("cmd.exe /d /s /c"));
            assertEquals("DEGRADED",
                    service.status(location, "codex")
                            .values()
                            .get("PROVIDER_STATUS"));
            assertEquals("REVIEW_REQUIRED",
                    service.status(location, "codex")
                            .values()
                            .get("TRUST_STATUS"));
            assertEquals("UP_TO_DATE",
                    service.status(location, "codex")
                            .values()
                            .get("MCP_CONFIG_STATUS"));
            assertEquals("SUCCESS",
                    service.uninstall(location, "codex")
                            .values()
                            .get("PROVIDER_UNINSTALL_RESULT"));
            Map<?, ?> after = (Map<?, ?>) ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) after.get("unrelated")).get("value"));
            assertTrue(((Map<?, ?>) after.get("hooks")).containsKey("Stop"));
        } finally {
            if (previous == null) {
                System.clearProperty("synesis.launcher");
            } else {
                System.setProperty("synesis.launcher", previous);
            }
        }
    }

    @Test
    void mcpConfigurationInstalledAndPreservesUnrelatedEntries() throws Exception {
        Path root = Files.createTempDirectory("mcp-config-test-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        Path mcpLauncher = Files.createTempFile("synesis-mcp-", ".exe");
        String previous = System.getProperty("synesis.launcher");
        String previousMcp = System.getProperty("synesis.mcp.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        System.setProperty("synesis.mcp.launcher", mcpLauncher.toString());
        try {
            var location = new ProjectApplicationService().init(root)
                    .location();
            var provider = org.synesis.workspace.provider.ProviderRegistry.find("codex");
            Path userMcp = provider.mcpConfigurationPath(root);
            if (userMcp != null) {
                Files.createDirectories(userMcp.getParent());
                Files.writeString(userMcp,
                        "notify = [\"keep\"]\n\n[mcp_servers.other-server]\ncommand = \"other.cmd\"\n");
            }
            Path legacyProjectMcp = root.resolve(".codex/mcp.json");
            Files.createDirectories(legacyProjectMcp.getParent());
            Files.writeString(legacyProjectMcp,
                    "{\"mcpServers\":{\"synesis\":{\"command\":\"old.cmd\"},\"other\":{\"command\":\"other.cmd\"}}}\n");

            ProviderApplicationService service = new ProviderApplicationService();
            service.install(location, "codex");

            Assertions.assertNotNull(userMcp);
            assertTrue(Files.exists(userMcp));
            String parsed = Files.readString(userMcp);
            assertTrue(parsed.contains("notify = [\"keep\"]"));
            assertTrue(parsed.contains("[mcp_servers.other-server]"));
            assertTrue(parsed.contains("[mcp_servers.synesis]"));
            assertTrue(parsed.contains("command = '" + mcpLauncher.toAbsolutePath()
                    .normalize() + "'"));
            assertTrue(parsed.contains("\"mcp\", \"--provider\", \"codex\", \"--project\""));
            assertTrue(parsed.contains(root.toAbsolutePath()
                    .normalize()
                    .toString()
                    .replace("\\", "\\\\")));
            Map<?, ?> legacy = (Map<?, ?>) ProviderJson.parse(Files.readString(legacyProjectMcp));
            assertTrue(((Map<?, ?>) legacy.get("mcpServers")).containsKey("other"));
            assertFalse(((Map<?, ?>) legacy.get("mcpServers")).containsKey("synesis"));
        } finally {
            if (previous == null) {
                System.clearProperty("synesis.launcher");
            } else {
                System.setProperty("synesis.launcher", previous);
            }
            if (previousMcp == null) {
                System.clearProperty("synesis.mcp.launcher");
            } else {
                System.setProperty("synesis.mcp.launcher", previousMcp);
            }
        }
    }

    @Test
    void freshCodexInstallPinsProjectAndIndependentSessionsConverge() throws Exception {
        Path root = Files.createTempDirectory("provider-readiness-");
        git(root, "init");
        git(root, "config", "user.name", "Test User");
        git(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Readiness\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "Initial commit");

        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        Path mcpLauncher = Files.createTempFile("synesis-mcp-", ".exe");
        String previousLauncher = System.getProperty("synesis.launcher");
        String previousMcp = System.getProperty("synesis.mcp.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        System.setProperty("synesis.mcp.launcher", mcpLauncher.toString());
        try {
            ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root)
                    .location();
            ProviderApplicationService service = new ProviderApplicationService();
            service.install(location, "codex");

            Path config = ProviderRegistry.find("codex")
                    .mcpConfigurationPath(root);
            String configured = Files.readString(config);
            assertTrue(configured.contains("\"--project\""));
            assertTrue(configured.contains(root.toAbsolutePath()
                    .normalize()
                    .toString()
                    .replace("\\", "\\\\")));

            AgentSessionService sessions = new AgentSessionService();
            AgentSessionService.SessionResolutionRequest first =
                    new AgentSessionService.SessionResolutionRequest(root, "codex", "fresh-agent-a", null, false);
            AgentSessionService.SessionResolutionRequest second =
                    new AgentSessionService.SessionResolutionRequest(root, "codex", "fresh-agent-b", null, false);
            assertEquals(AgentStatus.READY,
                    sessions.ensureSession(first)
                            .status());
            assertEquals(AgentStatus.READY,
                    sessions.ensureSession(first)
                            .status());
            assertEquals(AgentStatus.READY,
                    sessions.ensureSession(second)
                            .status());
            assertTrue(sessions.resolveSessionContext(first)
                    .isIsolatedWorkspace());
            assertTrue(sessions.resolveSessionContext(second)
                    .isIsolatedWorkspace());
            assertNotEquals(sessions.resolveSessionContext(first)
                            .sessionId(),
                    sessions.resolveSessionContext(second)
                            .sessionId());
        } finally {
            restoreProperty("synesis.launcher", previousLauncher);
            restoreProperty("synesis.mcp.launcher", previousMcp);
        }
    }

    @Test
    void claudeCodeMcpInstallUsesProjectConfigAndAliasPreservesUnrelatedEntries() throws Exception {
        Path root = Files.createTempDirectory("claude-mcp-config-test-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            var location = new ProjectApplicationService().init(root)
                    .location();
            Path mcp = root.resolve(".mcp.json");
            Files.writeString(mcp, "{\"mcpServers\":{\"other\":{\"command\":\"other.cmd\"}},\"custom\":true}\n");
            ProviderApplicationService service = new ProviderApplicationService();

            var installed = service.install(location, "claude");
            assertEquals("INSTALLED",
                    installed.values()
                            .get("MCP_CONFIG_STATUS"));
            Map<?, ?> parsed = (Map<?, ?>) ProviderJson.parse(Files.readString(mcp));
            assertEquals(Boolean.TRUE, parsed.get("custom"));
            Map<?, ?> servers = (Map<?, ?>) parsed.get("mcpServers");
            assertTrue(servers.containsKey("other"));
            Map<?, ?> synesis = (Map<?, ?>) servers.get("synesis");
            assertEquals(service.mcpLauncherPath()
                    .toAbsolutePath()
                    .normalize()
                    .toString(), synesis.get("command"));
            assertEquals(java.util.List.of("mcp", "--provider", "claude", "--project",
                    root.toAbsolutePath()
                            .normalize()
                            .toString()), synesis.get("args"));

            var reinstalled = service.install(location, "claude-code");
            assertEquals("UNKNOWN_PROVIDER",
                    reinstalled.values()
                            .get("PROVIDER_INSTALL_RESULT"));

            var uninstalled = service.uninstall(location, "claude");
            assertEquals("SUCCESS",
                    uninstalled.values()
                            .get("PROVIDER_UNINSTALL_RESULT"));
            Map<?, ?> after = (Map<?, ?>) ProviderJson.parse(Files.readString(mcp));
            assertEquals(Boolean.TRUE, after.get("custom"));
            assertTrue(((Map<?, ?>) after.get("mcpServers")).containsKey("other"));
        assertFalse(((Map<?, ?>) after.get("mcpServers")).containsKey("synesis"));
        } finally {
            if (previous == null) {
                System.clearProperty("synesis.launcher");
            } else {
                System.setProperty("synesis.launcher", previous);
            }
        }
    }

    @Test
    void claudeCodeHookUsesCanonicalCommandName() {
        Path launcher = Path.of("C:/tools/synesis.cmd");
        Path profile = Path.of("C:/project/.synesis/local/profile");

        String command = ProviderRegistry.find("claude")
                .hookCommand(launcher, profile);

        assertTrue(command.contains(" hook claude --profile "));
        assertFalse(command.contains(" hook claude-code --profile "));
    }

    @Test
    void codexInstallationMaterializesHookIntoAssignedWorktree() throws Exception {
        Path root = Files.createTempDirectory("codex-worktree-hook-");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "init");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "baseline");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            var location = new ProjectApplicationService().init(root)
                    .location();
            var result = new ProviderApplicationService().install(location, "codex");
            Path worktree = Path.of(result.values()
                    .get("ASSIGNED_WORKTREE"));
            assertTrue(Files.isRegularFile(worktree.resolve(".codex/hooks.json")));
            assertTrue(Files.readString(worktree.resolve(".codex/hooks.json"))
                    .contains("hook codex"));
        } finally {
            if (previous == null) {
                System.clearProperty("synesis.launcher");
            } else {
                System.setProperty("synesis.launcher", previous);
            }
        }
    }

}
