package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.HookApplicationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.link.identity.IdentityBootstrap;

/** Verifies project-scoped provider session identity and trust bootstrap. */
final class ProviderSessionBindingServiceTest {
    @Test
    void bindsAndResumesByExplicitProviderInstanceWithoutChangingProjectIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-binding-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        String projectId = location.projectId().toString();
        String nodeId = new ProjectApplicationService().init(root).identity().nodeId();
        ProviderSessionBindingService service = new ProviderSessionBindingService();

        var first = service.ensure(location, "codex", "chat-a");
        var resumed = service.ensure(location, "codex", "chat-a");
        var second = service.ensure(location, "codex", "chat-b");
        var otherProvider = service.ensure(location, "antigravity", "chat-a");

        assertEquals(projectId, first.binding().projectId());
        assertEquals(nodeId, first.binding().nodeId());
        assertEquals(first.binding().sessionId(), resumed.binding().sessionId());
        assertEquals(first.binding().supervisorId(), resumed.binding().supervisorId());
        assertNotEquals(first.binding().sessionId(), second.binding().sessionId());
        assertNotEquals(first.binding().sessionId(), otherProvider.binding().sessionId());
        assertEquals("READY_FOR_REAL_VALIDATION", first.binding().providerTrustState());
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertEquals(3, paths.count());
        }
    }

    @Test
    void fallbackEvidenceIsExplicitlyMarkedAndDoesNotClaimChatIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-fallback-");
        var location = new ProjectApplicationService().init(root).location();
        var result = new ProviderSessionBindingService().ensure(location, "codex", null);

        assertTrue(result.fallbackEvidence());
        assertTrue(Files.exists(root.resolve(".synesis/local/providers/codex.bootstrap-key")));
        assertEquals("FALLBACK", result.fallbackEvidence() ? "FALLBACK" : "EXPLICIT");
    }

    @Test
    void malformedBindingFailsClosedWithoutReplacingProjectIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-malformed-");
        var location = new ProjectApplicationService().init(root).location();
        Path binding = root.resolve(".synesis/local/sessions/codex-bad.json");
        Files.createDirectories(binding.getParent());
        Files.writeString(binding, "{broken");

        assertThrows(ProviderSessionBindingService.BindingException.class,
                () -> new ProviderSessionBindingService().list(location, "codex"));
        assertTrue(Files.exists(location.profile().resolve("link/identity.bin")));
    }

    @Test
    void codexHookBootstrapsProjectSessionBeforePolicyEvaluation() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-codex-hook-");
        var location = new ProjectApplicationService().init(root).location();
        String peer = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
        ProjectConfig config = new ProjectConfig(location.projectId(), java.util.Set.of(peer));
        config.save(location.profile().resolve("project.conf"));
        String event = "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"codex-chat-a\","
                + "\"cwd\":\"" + root.toString().replace("\\", "\\\\")
                + "\",\"tool_name\":\"apply_patch\",\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Update File: src/free.txt\\n*** End Patch\"}}";

        HookApplicationService.HookExecutionResult result = new HookApplicationService().codex(
                new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("ALLOWED", result.outcome());
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertTrue(paths.findAny().isPresent());
        }
    }

    @Test
    void antigravityHookBootstrapsProjectSessionBeforePolicyEvaluation() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-antigravity-hook-");
        var location = new ProjectApplicationService().init(root).location();
        String peer = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
        ProjectConfig config = new ProjectConfig(location.projectId(), java.util.Set.of(peer));
        config.save(location.profile().resolve("project.conf"));
        String event = "{\"conversationId\":\"antigravity-chat-a\",\"workspacePaths\":[\""
                + root.toString().replace("\\", "\\\\") + "\"],\"name\":\"write_to_file\",\"TargetFile\":\""
                + root.resolve("free.txt").toString().replace("\\", "\\\\") + "\"}";

        HookApplicationService.HookExecutionResult result = new HookApplicationService().antigravity(root,
                root.resolve(".synesis/local/profile"),
                new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("ALLOWED", result.outcome());
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertTrue(paths.findAny().isPresent());
        }
    }
}
