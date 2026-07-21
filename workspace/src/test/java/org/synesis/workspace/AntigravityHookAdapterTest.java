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

final class AntigravityHookAdapterTest {

    @Test
    void blocksReplaceFileContentOnConstrainedScope() throws Exception {
        Path tempDir = Files.createTempDirectory("antigravity-test-replace-");
        try {
            // Setup profile
            NodeIdentity identity = new IdentityBootstrap(tempDir.resolve("link")).loadOrCreate().identity();
            UUID projectId = UUID.randomUUID();
            ProjectConfig config = new ProjectConfig(projectId, java.util.Set.of("sl1-" + "1".repeat(64)));
            config.save(tempDir.resolve("project.conf"));

            Path protectedFile = tempDir.resolve("src/protocol/RecordMessage.java");
            Files.createDirectories(protectedFile.getParent());
            String initialContent = "package org.synesis.protocol;\npublic class RecordMessage {}";
            Files.writeString(protectedFile, initialContent);

            // Add active BLOCK constraint
            Ed25519Signer signer = Ed25519Signer.from(identity);
            DecisionRecord constraintRecord = ProjectConstraint.createTypedRecord(projectId, UUID.randomUUID(),
                    identity.nodeId(), ProjectConstraint.Effect.BLOCK, "src/protocol/**",
                    "Lock protocol wire format", "Protocol formats frozen.", signer);

            DecisionStore store = new DecisionStore(tempDir.resolve("records"), projectId);
            assertEquals(DecisionStore.SaveResult.APPLIED, store.save(constraintRecord, null));

            AntigravityHookAdapter adapter = new AntigravityHookAdapter(tempDir);
            String payload = """
                    {
                      "toolCall": {
                        "name": "replace_file_content",
                        "args": {
                          "TargetFile": "%s",
                          "Instruction": "Change protocol format",
                          "Description": "Add new field"
                        }
                      },
                      "stepIdx": 4,
                      "conversationId": "test-conv-123",
                      "workspacePaths": [ "%s" ]
                    }
                    """.formatted(escape(protectedFile.toString()), escape(tempDir.toString()));

            AntigravityHookAdapter.Result result = adapter.processJson(payload);

            assertEquals(AntigravityHookAdapter.Outcome.BLOCKED, result.outcome());
            assertTrue(result.responseJson().contains("\"decision\": \"deny\""), result.responseJson());
            assertTrue(result.responseJson().contains("Lock protocol wire format"), result.responseJson());

            // Target file remains unchanged
            assertEquals(initialContent, Files.readString(protectedFile));

        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void blocksWriteToFileAndMultiReplaceFileContent() throws Exception {
        Path tempDir = Files.createTempDirectory("antigravity-test-tools-");
        try {
            NodeIdentity identity = new IdentityBootstrap(tempDir.resolve("link")).loadOrCreate().identity();
            UUID projectId = UUID.randomUUID();
            new ProjectConfig(projectId, java.util.Set.of("sl1-" + "1".repeat(64))).save(tempDir.resolve("project.conf"));

            Path protectedFile = tempDir.resolve("src/protocol/NewFile.java");

            Ed25519Signer signer = Ed25519Signer.from(identity);
            DecisionRecord record = ProjectConstraint.createTypedRecord(projectId, UUID.randomUUID(),
                    identity.nodeId(), ProjectConstraint.Effect.BLOCK, "src/protocol/**",
                    "Lock protocol wire format", "Frozen.", signer);
            new DecisionStore(tempDir.resolve("records"), projectId).save(record, null);

            AntigravityHookAdapter adapter = new AntigravityHookAdapter(tempDir);

            // Test write_to_file
            String writePayload = """
                    {
                      "toolCall": {
                        "name": "write_to_file",
                        "args": { "TargetFile": "%s" }
                      },
                      "workspacePaths": [ "%s" ]
                    }
                    """.formatted(escape(protectedFile.toString()), escape(tempDir.toString()));
            AntigravityHookAdapter.Result writeRes = adapter.processJson(writePayload);
            assertEquals(AntigravityHookAdapter.Outcome.BLOCKED, writeRes.outcome());
            assertTrue(writeRes.responseJson().contains("\"decision\": \"deny\""));

            // Test multi_replace_file_content
            String multiPayload = """
                    {
                      "toolCall": {
                        "name": "multi_replace_file_content",
                        "args": { "TargetFile": "%s" }
                      },
                      "workspacePaths": [ "%s" ]
                    }
                    """.formatted(escape(protectedFile.toString()), escape(tempDir.toString()));
            AntigravityHookAdapter.Result multiRes = adapter.processJson(multiPayload);
            assertEquals(AntigravityHookAdapter.Outcome.BLOCKED, multiRes.outcome());
            assertTrue(multiRes.responseJson().contains("\"decision\": \"deny\""));

        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void evaluatesWarningsAndUnconstrainedActions() throws Exception {
        Path tempDir = Files.createTempDirectory("antigravity-test-warn-");
        try {
            NodeIdentity identity = new IdentityBootstrap(tempDir.resolve("link")).loadOrCreate().identity();
            UUID projectId = UUID.randomUUID();
            new ProjectConfig(projectId, java.util.Set.of("sl1-" + "1".repeat(64))).save(tempDir.resolve("project.conf"));

            Path warnFile = tempDir.resolve("src/ui/Panel.java");
            Path allowFile = tempDir.resolve("docs/readme.txt");

            Ed25519Signer signer = Ed25519Signer.from(identity);
            DecisionRecord record = ProjectConstraint.createTypedRecord(projectId, UUID.randomUUID(),
                    identity.nodeId(), ProjectConstraint.Effect.WARN, "src/ui/**",
                    "UI Style Warning", "Ensure theme consistency.", signer);
            new DecisionStore(tempDir.resolve("records"), projectId).save(record, null);

            AntigravityHookAdapter adapter = new AntigravityHookAdapter(tempDir);

            // Test WARN scope -> force_ask decision
            String warnPayload = """
                    {
                      "toolCall": {
                        "name": "replace_file_content",
                        "args": { "TargetFile": "%s" }
                      },
                      "workspacePaths": [ "%s" ]
                    }
                    """.formatted(escape(warnFile.toString()), escape(tempDir.toString()));
            AntigravityHookAdapter.Result warnRes = adapter.processJson(warnPayload);
            assertEquals(AntigravityHookAdapter.Outcome.WARNING, warnRes.outcome());
            assertTrue(warnRes.responseJson().contains("\"decision\": \"force_ask\""));

            // Test Unconstrained scope -> ask decision
            String allowPayload = """
                    {
                      "toolCall": {
                        "name": "replace_file_content",
                        "args": { "TargetFile": "%s" }
                      },
                      "workspacePaths": [ "%s" ]
                    }
                    """.formatted(escape(allowFile.toString()), escape(tempDir.toString()));
            AntigravityHookAdapter.Result allowRes = adapter.processJson(allowPayload);
            assertEquals(AntigravityHookAdapter.Outcome.ALLOWED, allowRes.outcome());
            assertTrue(allowRes.responseJson().contains("\"decision\": \"ask\""));

        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void handlesUnsupportedToolsAndInvalidInputs() throws Exception {
        Path tempDir = Files.createTempDirectory("antigravity-test-invalid-");
        try {
            AntigravityHookAdapter adapter = new AntigravityHookAdapter(tempDir);

            // Unsupported tool run_command -> ask decision + UNSUPPORTED outcome
            String runCmdPayload = """
                    {
                      "toolCall": {
                        "name": "run_command",
                        "args": { "CommandLine": "rm -rf /" }
                      }
                    }
                    """;
            AntigravityHookAdapter.Result unsuppRes = adapter.processJson(runCmdPayload);
            assertEquals(AntigravityHookAdapter.Outcome.UNSUPPORTED, unsuppRes.outcome());
            assertTrue(unsuppRes.responseJson().contains("\"decision\": \"ask\""));

            // Missing TargetFile -> deny decision + INVALID_INPUT outcome
            String missingTargetPayload = """
                    {
                      "toolCall": {
                        "name": "replace_file_content",
                        "args": {}
                      }
                    }
                    """;
            AntigravityHookAdapter.Result missingRes = adapter.processJson(missingTargetPayload);
            assertEquals(AntigravityHookAdapter.Outcome.INVALID_INPUT, missingRes.outcome());
            assertTrue(missingRes.responseJson().contains("\"decision\": \"deny\""));

        } finally {
            cleanup(tempDir);
        }
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
