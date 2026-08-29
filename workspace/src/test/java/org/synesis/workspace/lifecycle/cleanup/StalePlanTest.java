package org.synesis.workspace.lifecycle.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;

class StalePlanTest {

    @Test
    void skipsExecutionWhenPreconditionChangesAtExecutionTime(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path tempFile = workspaceRoot.resolve("temp-stale.tmp-1");
        Files.createDirectories(tempFile.getParent());
        Files.writeString(tempFile, "initial content");

        CleanupPlan rawPlan = new CleanupPlan(
                "test-project",
                System.currentTimeMillis(),
                1, 0, 0, 0, 0, 1, 0, 100L, false,
                List.of(new CleanupPlanEntry(
                        LifecycleResourceType.TEMPORARY_FILE,
                        "temp-stale",
                        tempFile,
                        CleanupClassification.CLEANUP_ELIGIBLE,
                        true,
                        List.of("temporary_file_expired"),
                        100L,
                        "Expired",
                        List.of("temp"),
                        "NOT_APPLICABLE",
                        false,
                        "path_verified",
                        ProcessEvidenceState.NOT_OBSERVED,
                        new LifecycleResourceFingerprint("temp-stale", 1000L, "NONE", "NONE", "", "hash1"),
                        "DELETE_TEMPORARY_FILE"
                ))
        );

        CleanupPlanStore store = new CleanupPlanStore();
        PersistedCleanupPlan saved = store.createAndSave(controlRoot, rawPlan);

        // Delete target file before execution to simulate state change/staleness
        Files.delete(tempFile);

        CleanupExecutionService service = new CleanupExecutionService();
        CleanupExecutionService.CleanupExecutionSummary summary = service.executePlan(controlRoot, saved.planId());

        assertEquals(1, summary.skippedStaleCount());
        assertEquals(0, summary.completedCount());
        assertSame(CleanupEntryExecutionState.SKIPPED_STALE, summary.records()
                .getFirst()
                .state());
    }
}
