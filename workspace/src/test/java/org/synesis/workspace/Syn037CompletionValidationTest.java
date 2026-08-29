package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.agent.AgentTaskCompletionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;
import org.synesis.workspace.test.PortableTestCommand;
import org.synesis.workspace.test.TestGit;

/**
 * Exercises the configured validation argv through command, completion, and integration paths.
 */
class Syn037CompletionValidationTest {

    private String previousHome;

    private static void configureValidation(Path metadata, java.util.UUID projectId, java.time.Instant createdAt)
            throws Exception {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("schemaVersion", 2);
        value.put("projectId", projectId.toString());
        value.put("createdAt", createdAt.toString());
        value.put("validation", Map.of(
                "argv", PortableTestCommand.validation(),
                "workingDirectory", ".",
                "timeoutSeconds", 120));
        Files.writeString(metadata, ProviderJson.write(value) + System.lineSeparator());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultMap(AgentResponse response) {
        return assertInstanceOf(Map.class, response.result());
    }

    private static void assertEvidenceMetadata(AgentResponse response) {
        assertEvidenceMap(resultMap(response));
    }

    @SuppressWarnings("unchecked")
    private static void assertEvidenceMap(Object value) {
        Map<String, Object> result = assertInstanceOf(Map.class, value);
        assertTrue(result.containsKey("stdoutBytesRead"), result.toString());
        assertTrue(result.containsKey("stderrBytesRead"), result.toString());
        assertTrue(result.containsKey("stdoutBytesRetained"), result.toString());
        assertTrue(result.containsKey("stderrBytesRetained"), result.toString());
        assertTrue(result.containsKey("stdoutTruncated"), result.toString());
        assertTrue(result.containsKey("stderrTruncated"), result.toString());
    }

    private static void git(Path root, String... args) throws Exception {
        TestGit.run(root, args);
    }

    private static String gitOutput(Path root) throws Exception {
        return TestGit.output(root, "status", "--short");
    }

    @BeforeEach
    void isolateProviderManual() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home",
                Files.createTempDirectory("synesis-syn037-home-")
                        .toString());
    }

    @AfterEach
    void restoreProviderManualHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void commandEvidenceFlowsThroughFinishAndIntegration(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/task_tracker.txt"), "pending\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");

        ProjectApplicationService projectService = new ProjectApplicationService();
        ProjectApplicationService.InitResult initialized = projectService.init(root, false);
        configureValidation(initialized.location()
                        .metadataFile(),
                initialized.location()
                        .projectId(),
                initialized.location()
                        .createdAt());
        git(root, "add", ".synesis/project.json");
        git(root, "commit", "-m", "configure validation");
        RepositoryPrivateStateService.ensure(root);
        new ProviderManualService().install("codex");

        AgentSessionService sessions = new AgentSessionService();
        AgentResponse session = sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
                root, "codex", "syn037-codex", null, false));
        assertEquals(AgentStatus.READY, session.status(), session.toJson());

        ProjectApplicationService.ProjectLocation location = projectService.locate(root);
        new WorkspaceCollaborationService().announce(root, "codex", "syn037-codex",
                "Implement task tracker", "src/task_tracker.txt becomes implemented",
                List.of(ResourceSelector.pathExact("src/task_tracker.txt")));
        ProviderSessionBindingService.Binding binding = new ProviderSessionBindingService()
                .find(location, "codex", "syn037-codex")
                .orElseThrow();
        Path lane = Path.of(binding.worktreePath());
        Files.writeString(lane.resolve("src/task_tracker.txt"), "implemented\n");

        List<String> validationArgv = PortableTestCommand.validation();
        AgentResponse gitResult = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                root, "codex", "syn037-codex", List.of("git", "status", "--porcelain")));
        AgentResponse missingResult = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                root, "codex", "syn037-codex", List.of("synesis-deliberately-missing-037")));
        AgentResponse validationResult = new ProjectCommandService().runCommand(new ProjectCommandService.CommandRequest(
                root, "codex", "syn037-codex", validationArgv));
        assertEquals(AgentStatus.COMPLETED, gitResult.status(), gitResult.toJson());
        assertEquals(AgentStatus.BLOCKED, missingResult.status(), missingResult.toJson());
        assertTrue(missingResult.toJson()
                .contains("command_executable_not_found"), missingResult.toJson());
        assertEquals(AgentStatus.COMPLETED, validationResult.status(), validationResult.toJson());
        assertEvidenceMetadata(gitResult);
        assertEvidenceMetadata(missingResult);
        assertEvidenceMetadata(validationResult);

        AgentResponse completed = new AgentTaskCompletionService().completeTask(
                new AgentTaskCompletionService.CompleteTaskRequest(root, "codex", "syn037-codex",
                        "Implement task tracker"));
        assertEquals(AgentStatus.COMPLETED, completed.status(), completed.toJson());
        Map<String, Object> completion = resultMap(completed);
        assertEvidenceMap(completion.get("prePublicationValidation"));
        assertEvidenceMap(completion.get("validation"));
        // Git checkout line endings are platform-dependent; the command and
        // snapshot contract preserves process bytes, so assert the logical
        // file lines rather than requiring LF on Windows.
        assertEquals(List.of("implemented"), Files.readAllLines(root.resolve("src/task_tracker.txt")));

        PredictionEventStore store = new PredictionEventStore(root.resolve(".synesis/coordination"),
                location.projectId());
        var snapshot = store.taskCompletionProjection()
                .allSnapshots()
                .stream()
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("src/task_tracker.txt"), snapshot.changedPaths());
        assertTrue(snapshot.changedPaths()
                .stream()
                .noneMatch(path -> path.startsWith(".synesis/")
                        || path.startsWith(".codex/") || path.contains("provider") || path.contains("hook")));
        assertEquals("", gitOutput(root));
    }
}
