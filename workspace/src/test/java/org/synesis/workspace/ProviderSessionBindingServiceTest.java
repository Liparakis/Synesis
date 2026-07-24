package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.workspace.application.HookApplicationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;

/**
 * Verifies project-scoped provider session identity and trust bootstrap.
 */
final class ProviderSessionBindingServiceTest {

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    @Test
    void bindsAndResumesByExplicitProviderInstanceWithoutChangingProjectIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-binding-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root)
                .location();
        String projectId = location.projectId()
                .toString();
        String nodeId = new ProjectApplicationService().init(root)
                .identity()
                .nodeId();
        ProviderSessionBindingService service = new ProviderSessionBindingService();

        var first = service.ensure(location, "codex", "chat-a");
        var resumed = service.ensure(location, "codex", "chat-a");
        var second = service.ensure(location, "codex", "chat-b");
        var otherProvider = service.ensure(location, "antigravity", "chat-a");

        assertEquals(projectId,
                first.binding()
                        .projectId());
        assertEquals(nodeId,
                first.binding()
                        .nodeId());
        assertEquals(first.binding()
                        .sessionId(),
                resumed.binding()
                        .sessionId());
        assertEquals(first.binding()
                        .supervisorId(),
                resumed.binding()
                        .supervisorId());
        assertNotEquals(first.binding()
                        .sessionId(),
                second.binding()
                        .sessionId());
        assertNotEquals(first.binding()
                        .sessionId(),
                otherProvider.binding()
                        .sessionId());
        assertEquals("WORKSPACE_UNVERIFIED",
                first.binding()
                        .providerTrustState());
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertEquals(3, paths.count());
        }
    }

    @Test
    void fallbackEvidenceIsExplicitlyMarkedAndDoesNotClaimChatIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-fallback-");
        var location = new ProjectApplicationService().init(root)
                .location();
        var result = new ProviderSessionBindingService().ensure(location, "codex", null);

        assertTrue(result.fallbackEvidence());
        assertTrue(Files.exists(root.resolve(".synesis/local/providers/codex.bootstrap-key")));
        assertEquals("FALLBACK", result.fallbackEvidence() ? "FALLBACK" : "EXPLICIT");
    }

    @Test
    void allocatesDistinctWorktreeOnlyForACommittedGitProject() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-worktree-");
        var location = new ProjectApplicationService().init(root)
                .location();
        Files.writeString(root.resolve("README.md"), "proof\n");
        git(root, "init");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "initial");

        var service = new ProviderSessionBindingService();
        var binding = service.ensure(location, "codex", "chat-worktree")
                .binding();
        var second = service.ensure(location, "codex", "chat-worktree-2")
                .binding();
        assertNotEquals(root.toAbsolutePath()
                .normalize()
                .toString(), binding.worktreePath());
        assertTrue(binding.worktreePath() != null && Files.isDirectory(Path.of(binding.worktreePath())));
        assertNotEquals(binding.worktreePath(), second.worktreePath());
        assertNotEquals(binding.branch(), second.branch());
        assertTrue(binding.baseCommit()
                .matches("[0-9a-f]{40}"));
        var check = new ProviderSessionBindingService().verifyWorkspace(location, binding,
                Path.of(binding.worktreePath()));
        assertTrue(check.verified(), check::code);
        assertEquals("CONTROL_CHECKOUT_MUTATION_DENIED",
                service.verifyWorkspace(location, binding, root)
                        .code());
        git(root, "worktree", "remove", "--force", binding.worktreePath());
        assertEquals("WORKSPACE_TRANSITION_REQUIRED", service.verifyWorkspace(location, binding,
                        Path.of(binding.worktreePath()))
                .code());
    }

    @Test
    void malformedBindingFailsClosedWithoutReplacingProjectIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-malformed-");
        var location = new ProjectApplicationService().init(root)
                .location();
        Path binding = root.resolve(".synesis/local/sessions/codex-bad.json");
        Files.createDirectories(binding.getParent());
        Files.writeString(binding, "{broken");

        assertThrows(ProviderSessionBindingService.BindingException.class,
                () -> new ProviderSessionBindingService().list(location, "codex"));
        assertTrue(Files.exists(location.profile()
                .resolve("link/identity.bin")));
    }

    @Test
    void hookResolvesControlSessionFromAssignedWorktreeMarker() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-routing-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root)
                .location();
        String peer = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity()
                .nodeId();
        new ProjectConfig(location.projectId(), java.util.Set.of(peer)).save(location.profile()
                .resolve("project.conf"));
        var binding = new ProviderSessionBindingService().ensure(location, "codex", "routing-session")
                .binding();
        String event = "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"routing-session\","
                + "\"cwd\":\"" + binding.worktreePath()
                .replace("\\", "\\\\")
                + "\",\"tool_name\":\"apply_patch\",\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Add File: src/free.txt\\n*** End Patch\"}}";

        var result = new HookApplicationService().codex(
                new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("ALLOWED", result.outcome(), result.responseJson() + " " + result.humanReason());
        assertTrue(result.humanReason()
                .contains("SESSION_ID=" + binding.sessionId()));
        assertTrue(Files.notExists(root.resolve("src/free.txt")));
    }

    @Test
    void codexHookBootstrapsProjectSessionBeforePolicyEvaluation() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-codex-hook-");
        var location = new ProjectApplicationService().init(root)
                .location();
        String peer = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity()
                .nodeId();
        ProjectConfig config = new ProjectConfig(location.projectId(), java.util.Set.of(peer));
        config.save(location.profile()
                .resolve("project.conf"));
        String event = "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"codex-chat-a\","
                + "\"cwd\":\"" + root.toString()
                .replace("\\", "\\\\")
                + "\",\"tool_name\":\"apply_patch\",\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Update File: src/free.txt\\n*** End Patch\"}}";

        HookApplicationService.HookExecutionResult result = new HookApplicationService().codex(
                new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("INVALID_INPUT", result.outcome());
        assertTrue(result.responseJson()
                .contains("GIT_HEAD_UNAVAILABLE"));
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertTrue(paths.findAny()
                    .isPresent());
        }
    }

    @Test
    void antigravityHookBootstrapsProjectSessionBeforePolicyEvaluation() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-antigravity-hook-");
        var location = new ProjectApplicationService().init(root)
                .location();
        String peer = new IdentityBootstrap(location.profile()
                .resolve("link")).loadOrCreate()
                .identity()
                .nodeId();
        ProjectConfig config = new ProjectConfig(location.projectId(), java.util.Set.of(peer));
        config.save(location.profile()
                .resolve("project.conf"));
        String event = "{\"conversationId\":\"antigravity-chat-a\",\"workspacePaths\":[\""
                + root.toString()
                .replace("\\", "\\\\") + "\"],\"name\":\"write_to_file\",\"TargetFile\":\""
                + root.resolve("free.txt")
                .toString()
                .replace("\\", "\\\\") + "\"}";

        HookApplicationService.HookExecutionResult result = new HookApplicationService().antigravity(root,
                root.resolve(".synesis/local/profile"),
                new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("INVALID_INPUT", result.outcome());
        assertTrue(result.responseJson()
                .contains("GIT_HEAD_UNAVAILABLE"));
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertTrue(paths.findAny()
                    .isPresent());
        }
    }
}
