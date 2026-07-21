package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.Ed25519Signer;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.integration.claude.ClaudeCodeHookAdapter;

final class ClaudeCodeHookAdapterTest {

    @Test
    void blocksEditOnConstrainedScopeWithOfficialPreToolUseContract() throws Exception {
        Path tempDir = Files.createTempDirectory("hook-test-official-");
        try {
            // Setup profile
            NodeIdentity identity = new IdentityBootstrap(tempDir.resolve("link")).loadOrCreate().identity();
            UUID projectId = UUID.randomUUID();
            ProjectConfig config = new ProjectConfig(projectId, java.util.Set.of("sl1-" + "1".repeat(64)));
            config.save(tempDir.resolve("project.conf"));

            // Create protected file on disk
            Path protectedFile = tempDir.resolve("src/protocol/RecordMessage.java");
            Files.createDirectories(protectedFile.getParent());
            String initialContent = "package org.synesis.protocol;\npublic class RecordMessage {}";
            Files.writeString(protectedFile, initialContent);

            // Add active typed constraint for src/protocol/**
            Ed25519Signer signer = Ed25519Signer.from(identity);
            DecisionRecord constraintRecord = ProjectConstraint.createTypedRecord(projectId, UUID.randomUUID(),
                    identity.nodeId(), ProjectConstraint.Effect.BLOCK, "src/protocol/**",
                    "Lock protocol wire format", "Protocol formats frozen.", signer);

            DecisionStore store = new DecisionStore(tempDir.resolve("records"), projectId);
            assertEquals(DecisionStore.SaveResult.APPLIED, store.save(constraintRecord, null));

            // Test Official PreToolUse Event targeting protected file with absolute path
            ClaudeCodeHookAdapter adapter = new ClaudeCodeHookAdapter(tempDir);
            String preToolEventJson = """
                    {
                      "hook_event_name": "PreToolUse",
                      "cwd": "%s",
                      "tool_name": "Edit",
                      "tool_input": {
                        "file_path": "%s",
                        "old_string": "RecordMessage",
                        "new_string": "TamperedMessage"
                      },
                      "tool_use_id": "toolu_01ABC123"
                    }
                    """.formatted(escape(tempDir.toString()), escape(protectedFile.toString()));

            ClaudeCodeHookAdapter.Result result = adapter.processJson(preToolEventJson);

            assertEquals(ClaudeCodeHookAdapter.Outcome.BLOCKED, result.outcome());
            assertTrue(result.responseJson().contains("\"hookEventName\": \"PreToolUse\""), result.responseJson());
            assertTrue(result.responseJson().contains("\"permissionDecision\": \"deny\""), result.responseJson());
            assertTrue(result.responseJson().contains("Lock protocol wire format"), result.responseJson());

            // Prove target file content remains completely unchanged
            assertEquals(initialContent, Files.readString(protectedFile));

            // Test PreToolUse Event targeting unconstrained scope -> ALLOWED ({})
            Path unconstrainedFile = tempDir.resolve("src/ui/Component.java");
            String unconstrainedJson = """
                    {
                      "hook_event_name": "PreToolUse",
                      "cwd": "%s",
                      "tool_name": "Edit",
                      "tool_input": {
                        "file_path": "%s"
                      },
                      "tool_use_id": "toolu_02DEF456"
                    }
                    """.formatted(escape(tempDir.toString()), escape(unconstrainedFile.toString()));

            ClaudeCodeHookAdapter.Result allowedResult = adapter.processJson(unconstrainedJson);
            assertEquals(ClaudeCodeHookAdapter.Outcome.ALLOWED, allowedResult.outcome());
            assertEquals("{}", allowedResult.responseJson().trim());

        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void absoluteToRelativePathResolutionAndBoundaryRejection() throws Exception {
        Path root = Path.of("C:/work/synesis-demo");

        // Absolute path inside project root -> normalized relative path
        String resolvedInside = ClaudeCodeHookAdapter.resolveRelativePath(root, "C:/work/synesis-demo/src/protocol/RecordMessage.java");
        assertEquals("src/protocol/RecordMessage.java", resolvedInside);

        // Relative path -> normalized relative path
        String resolvedRel = ClaudeCodeHookAdapter.resolveRelativePath(root, "src/protocol/RecordMessage.java");
        assertEquals("src/protocol/RecordMessage.java", resolvedRel);

        // Absolute path outside project root -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                ClaudeCodeHookAdapter.resolveRelativePath(root, "C:/work/other-project/src/protocol/RecordMessage.java"));
    }

    @Test
    void supersessionExcludesSupersededConstraintFromActionCheck() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();

        ProjectConstraint constraintA = new ProjectConstraint(idA, "Old Block", "Old rationale",
                ProjectConstraint.ConstraintStatus.ACTIVE, ProjectConstraint.Effect.BLOCK,
                List.of("src/protocol/**"), List.of());

        ProjectConstraint constraintB = new ProjectConstraint(idB, "New Warn", "New rationale",
                ProjectConstraint.ConstraintStatus.ACTIVE, ProjectConstraint.Effect.WARN,
                List.of("src/protocol/**"), List.of(idA)); // Explicitly supersedes A

        List<ProjectConstraint> effective = ProjectConstraint.filterEffectiveActive(List.of(constraintA, constraintB));
        assertEquals(1, effective.size());
        assertEquals(idB, effective.get(0).recordId());
        assertEquals(ProjectConstraint.Effect.WARN, effective.get(0).effect());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\");
    }

    private static void cleanup(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
