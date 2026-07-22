package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.Ed25519Signer;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.integration.codex.CodexHookAdapter;
import org.synesis.workspace.provider.ProviderJson;

/** Verifies Codex hook decisions without modifying target files. */
final class CodexHookAdapterTest {
    @Test
    void blocksAnyProtectedPathInAMultiPathPatchAndLeavesFilesUntouched() throws Exception {
        Path root = fixture(ProjectConstraint.Effect.BLOCK, "src/protected/**", "Protected source", "Do not edit");
        try {
            Path protectedFile = root.resolve("src/protected/file.txt");
            Files.createDirectories(protectedFile.getParent());
            Files.writeString(protectedFile, "original");
            String patch = patch("*** Update File: src/free.txt\n*** Update File: src/protected/file.txt");

            CodexHookAdapter.Result result = new CodexHookAdapter().processJson(event(root, "apply_patch", patch));

            assertEquals(CodexHookAdapter.Outcome.BLOCKED, result.outcome());
            assertTrue(result.responseJson().contains("\"permissionDecision\":\"deny\""));
            assertTrue(result.responseJson().contains("Protected source"));
            assertEquals("original", Files.readString(protectedFile));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void allowsWarningsAndUnsupportedToolsWithoutPermissionDecision() throws Exception {
        Path root = fixture(ProjectConstraint.Effect.WARN, "src/warn/**", "Review source", "Review before editing");
        try {
            CodexHookAdapter adapter = new CodexHookAdapter();
            CodexHookAdapter.Result warning = adapter.processJson(event(root, "apply_patch",
                    patch("*** Add File: src/warn/file.txt")));
            CodexHookAdapter.Result allowed = adapter.processJson(event(root, "apply_patch",
                    patch("*** Add File: docs/readme.txt")));
            CodexHookAdapter.Result unsupported = adapter.processJson(event(root, "shell",
                    "ignored"));

            assertEquals(CodexHookAdapter.Outcome.WARNING, warning.outcome());
            assertTrue(warning.responseJson().contains("additionalContext"));
            assertEquals(CodexHookAdapter.Outcome.ALLOWED, allowed.outcome());
            assertEquals("", allowed.responseJson());
            assertEquals(CodexHookAdapter.Outcome.UNSUPPORTED, unsupported.outcome());
            assertEquals("", unsupported.responseJson());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void deniesInvalidInputAndTraversalWithoutApplyingAnything() throws Exception {
        Path root = fixture(ProjectConstraint.Effect.BLOCK, "src/protected/**", "Protected", "Frozen");
        try {
            CodexHookAdapter adapter = new CodexHookAdapter();
            CodexHookAdapter.Result malformed = adapter.processJson(event(root, "apply_patch", "not a patch"));
            CodexHookAdapter.Result traversal = adapter.processJson(event(root, "apply_patch",
                    patch("*** Update File: ../outside.txt")));

            assertEquals(CodexHookAdapter.Outcome.INVALID_INPUT, malformed.outcome());
            assertEquals(CodexHookAdapter.Outcome.INVALID_INPUT, traversal.outcome());
            assertTrue(malformed.responseJson().contains("\"permissionDecision\":\"deny\""));
            assertTrue(traversal.responseJson().contains("\"permissionDecision\":\"deny\""));
            assertFalse(Files.exists(root.resolve("outside.txt")));
        } finally {
            cleanup(root);
        }
    }

    private static Path fixture(ProjectConstraint.Effect effect, String scope, String title, String rationale)
            throws Exception {
        Path root = Files.createTempDirectory("codex-hook-");
        var location = new org.synesis.workspace.application.ProjectApplicationService().init(root).location();
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        new ProjectConfig(location.projectId(), java.util.Set.of("sl1-" + "0".repeat(64)))
                .save(location.profile().resolve("project.conf"));
        var record = ProjectConstraint.createTypedRecord(location.projectId(), UUID.randomUUID(), identity.nodeId(),
                effect, scope, title, rationale, Ed25519Signer.from(identity));
        assertEquals(DecisionStore.SaveResult.APPLIED,
                new DecisionStore(location.profile().resolve("records"), location.projectId()).save(record, null));
        return root;
    }

    private static String event(Path root, String toolName, String command) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", command);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("hook_event_name", "PreToolUse");
        event.put("cwd", root.toString());
        event.put("tool_name", toolName);
        event.put("tool_input", input);
        return ProviderJson.write(event);
    }

    private static String patch(String directives) {
        return "*** Begin Patch\n" + directives + "\n*** End Patch";
    }

    private static void cleanup(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
