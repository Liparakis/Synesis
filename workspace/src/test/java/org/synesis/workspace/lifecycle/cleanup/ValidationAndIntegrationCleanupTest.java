package org.synesis.workspace.lifecycle.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;

class ValidationAndIntegrationCleanupTest {

    private static void runGit(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    private static String runGitOutput(Path root, String... args) throws Exception {
        return org.synesis.workspace.test.TestGit.output(root, args);
    }

    @Test
    void removesCompletedValidationWorktree(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        runGit(controlRoot, "init");
        runGit(controlRoot, "config", "user.name", "Test");
        runGit(controlRoot, "config", "user.email", "test@test.com");
        Files.writeString(controlRoot.resolve("README.md"), "# Control\n");
        runGit(controlRoot, "add", "README.md");
        runGit(controlRoot, "commit", "-m", "init");

        new ProjectApplicationService().init(controlRoot);

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path valPath = workspaceRoot.resolve("validation/val-123");
        Files.createDirectories(valPath.getParent());

        runGit(controlRoot, "worktree", "add", "-b", "val-branch-123", valPath.toString(), "HEAD");
        String head = runGitOutput(valPath, "rev-parse", "HEAD");
        String commonDir = runGitOutput(valPath, "rev-parse", "--git-common-dir");

        CleanupPlan rawPlan = new CleanupPlan(
                "test-project",
                System.currentTimeMillis(),
                1, 0, 0, 0, 0, 1, 0, 2048L, false,
                List.of(new CleanupPlanEntry(
                        LifecycleResourceType.VALIDATION_WORKTREE,
                        "val-123",
                        valPath,
                        CleanupClassification.CLEANUP_ELIGIBLE,
                        true,
                        List.of("finalized_and_clean"),
                        2048L,
                        "Clean",
                        List.of("val-123"),
                        "REGISTERED",
                        false,
                        "path_verified",
                        ProcessEvidenceState.NOT_OBSERVED,
                        new LifecycleResourceFingerprint("val-123", 1000L, head, commonDir, "clean", "h1"),
                        "DELETE_VALIDATION_WORKTREE"
                ))
        );

        CleanupPlanStore store = new CleanupPlanStore();
        PersistedCleanupPlan saved = store.createAndSave(controlRoot, rawPlan);

        CleanupExecutionService service = new CleanupExecutionService();
        CleanupExecutionService.CleanupExecutionSummary summary = service.executePlan(controlRoot, saved.planId());

        assertEquals(1, summary.completedCount());
        assertFalse(Files.exists(valPath));
    }
}
