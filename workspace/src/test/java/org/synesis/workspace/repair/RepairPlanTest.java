package org.synesis.workspace.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

public class RepairPlanTest {

    @Test
    public void testRepairPlanPrepareShowExecuteRollback(@TempDir Path tempDir) throws Exception {
        ProjectApplicationService projectService = new ProjectApplicationService();
        projectService.init(tempDir);

        Path workspaceRoot = org.synesis.workspace.cleanup.LifecyclePathVerifier.resolveWorkspaceRoot(tempDir);
        Path adminDir = workspaceRoot.resolve("admin");
        Files.createDirectories(adminDir);
        Path lockFile = adminDir.resolve("cleanup-execution.lock");
        Files.writeString(lockFile, "{ \"pid\": 9999999 }");

        RepairService repairService = new RepairService();

        // 1. Prepare Plan
        RepairPlan plan = repairService.preparePlan(tempDir);
        assertNotNull(plan.planId());
        assertTrue(plan.supportedRepairsCount() > 0);

        // 2. Show Plan
        RepairPlan loadedPlan = repairService.showPlan(tempDir, plan.planId());
        assertEquals(plan.contentHash(), loadedPlan.contentHash());

        // 3. Execute Plan
        RepairService.ExecutionResult result = repairService.executePlan(tempDir, plan.planId());
        assertEquals(1, result.completedCount());
        assertFalse(Files.exists(lockFile), "Stale lock file should have been removed");

        // 4. Rollback Execution
        repairService.rollback(tempDir, result.executionId());
        assertTrue(Files.exists(lockFile), "Rollback should restore administrative file");
    }
}
