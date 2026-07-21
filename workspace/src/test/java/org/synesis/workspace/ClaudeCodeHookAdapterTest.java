package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

final class ClaudeCodeHookAdapterTest {

    @Test
    void blocksEditOnConstrainedScopeAndPreservesTargetFile() throws Exception {
        Path tempDir = Files.createTempDirectory("hook-test-");
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

            // Test Pre-Tool Edit Event targeting protected file
            ClaudeCodeHookAdapter adapter = new ClaudeCodeHookAdapter(tempDir);
            String preToolEventJson = """
                    {
                      "hook_event": {
                        "type": "pre_tool_execution",
                        "tool_name": "str_replace_editor",
                        "tool_input": {
                          "command": "str_replace",
                          "path": "src/protocol/RecordMessage.java",
                          "old_str": "RecordMessage",
                          "new_str": "TamperedMessage"
                        }
                      }
                    }
                    """;

            ClaudeCodeHookAdapter.Result result = adapter.processJson(preToolEventJson);

            assertEquals(ClaudeCodeHookAdapter.Outcome.BLOCKED, result.outcome());
            assertTrue(result.responseJson().contains("\"decision\": \"deny\""), result.responseJson());
            assertTrue(result.responseJson().contains("Lock protocol wire format"), result.responseJson());

            // Prove target file content remains completely unchanged
            assertEquals(initialContent, Files.readString(protectedFile));

            // Test Pre-Tool Edit Event targeting unconstrained scope -> ALLOWED
            String unconstrainedJson = """
                    {
                      "hook_event": {
                        "type": "pre_tool_execution",
                        "tool_name": "str_replace_editor",
                        "tool_input": {
                          "path": "src/ui/Component.java"
                        }
                      }
                    }
                    """;

            ClaudeCodeHookAdapter.Result allowedResult = adapter.processJson(unconstrainedJson);
            assertEquals(ClaudeCodeHookAdapter.Outcome.ALLOWED, allowedResult.outcome());
            assertTrue(allowedResult.responseJson().contains("\"decision\": \"allow\""), allowedResult.responseJson());

        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void handlesMalformedJsonAndUnsupportedTools() throws Exception {
        Path tempDir = Files.createTempDirectory("hook-test-malformed-");
        try {
            ClaudeCodeHookAdapter adapter = new ClaudeCodeHookAdapter(tempDir);

            // Unsupported non-file tool (e.g. Bash) -> ALLOWED (with UNSUPPORTED outcome)
            String bashJson = """
                    {
                      "tool_name": "Bash",
                      "tool_input": { "command": "ls -l" }
                    }
                    """;
            ClaudeCodeHookAdapter.Result bashResult = adapter.processJson(bashJson);
            assertEquals(ClaudeCodeHookAdapter.Outcome.UNSUPPORTED, bashResult.outcome());
            assertTrue(bashResult.responseJson().contains("\"decision\": \"allow\""));

            // Missing path -> INVALID_INPUT (deny)
            String missingPathJson = """
                    {
                      "tool_name": "Edit",
                      "tool_input": {}
                    }
                    """;
            ClaudeCodeHookAdapter.Result missingResult = adapter.processJson(missingPathJson);
            assertEquals(ClaudeCodeHookAdapter.Outcome.INVALID_INPUT, missingResult.outcome());
            assertTrue(missingResult.responseJson().contains("\"decision\": \"deny\""));

        } finally {
            cleanup(tempDir);
        }
    }

    private static void cleanup(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
