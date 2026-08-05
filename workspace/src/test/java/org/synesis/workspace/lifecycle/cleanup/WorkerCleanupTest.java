package org.synesis.workspace.lifecycle.cleanup;

import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.infrastructure.process.ProcessInspector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerCleanupTest {

    private static void runGit(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    @Test
    void removesFinalizedCleanWorktreeSafely(@TempDir Path tempDir) throws Exception {
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
        Path wtPath = workspaceRoot.resolve("worktrees/session-clean");
        Files.createDirectories(wtPath.getParent());

        runGit(controlRoot, "worktree", "add", "-b", "feature-clean", wtPath.toString(), "HEAD");
        String head = runGitOutput(wtPath, "rev-parse", "HEAD");
        String commonDir = runGitOutput(wtPath, "rev-parse", "--git-common-dir");

        CleanupPlan rawPlan = new CleanupPlan(
                "test-project",
                System.currentTimeMillis(),
                1, 0, 0, 0, 0, 1, 0, 4096L, false,
                List.of(new CleanupPlanEntry(
                        LifecycleResourceType.WORKER_WORKTREE, "wt-clean", wtPath,
                        CleanupClassification.CLEANUP_ELIGIBLE, true, List.of("finalized_and_clean"), 4096L, "Clean",
                        List.of("session-clean"), "REGISTERED", false, "path_verified", ProcessEvidenceState.NOT_OBSERVED,
                        new LifecycleResourceFingerprint("wt-clean", 1000L, head, commonDir, "clean", "h1"), "DELETE_WORKTREE_DIRECTORY"
                ))
        );

        CleanupPlanStore store = new CleanupPlanStore();
        PersistedCleanupPlan saved = store.createAndSave(controlRoot, rawPlan);

        CleanupExecutionService service = new CleanupExecutionService();
        CleanupExecutionService.CleanupExecutionSummary summary = service.executePlan(controlRoot, saved.planId());

        assertEquals(1, summary.completedCount());
        assertEquals(0, summary.failedCount());
        assertFalse(Files.exists(wtPath));
    }

    private static String runGitOutput(Path root, String... args) throws Exception {
        return org.synesis.workspace.test.TestGit.output(root, args);
    }
}
