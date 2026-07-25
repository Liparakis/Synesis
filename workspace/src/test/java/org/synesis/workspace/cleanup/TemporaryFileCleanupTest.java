package org.synesis.workspace.cleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TemporaryFileCleanupTest {

    @Test
    void removesExpiredTemporaryFileAndEmptyParentDir(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path tempDirSub = workspaceRoot.resolve("tmp-dir");
        Files.createDirectories(tempDirSub);
        Path tempFile = tempDirSub.resolve("file.tmp-1");
        Files.writeString(tempFile, "temp content");

        CleanupPlan rawPlan = new CleanupPlan(
                "test-project",
                System.currentTimeMillis(),
                1, 0, 0, 0, 0, 1, 0, 12L, false,
                List.of(new CleanupPlanEntry(
                        LifecycleResourceType.TEMPORARY_FILE, "file.tmp-1", tempFile,
                        CleanupClassification.CLEANUP_ELIGIBLE, true, List.of("temporary_file_expired"), 12L, "Expired",
                        List.of("temp"), "NOT_APPLICABLE", false, "path_verified", ProcessEvidenceState.NOT_OBSERVED,
                        new LifecycleResourceFingerprint("file.tmp-1", 1000L, "NONE", "NONE", "", "h1"), "DELETE_TEMPORARY_FILE"
                ))
        );

        CleanupPlanStore store = new CleanupPlanStore();
        PersistedCleanupPlan saved = store.createAndSave(controlRoot, rawPlan);

        CleanupExecutionService service = new CleanupExecutionService();
        CleanupExecutionService.CleanupExecutionSummary summary = service.executePlan(controlRoot, saved.planId());

        assertEquals(1, summary.completedCount());
        assertFalse(Files.exists(tempFile));
        assertFalse(Files.exists(tempDirSub));
    }
}
