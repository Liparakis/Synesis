package org.synesis.workspace.lifecycle.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Exercises what repair execution records as done, refused, or failed.
 */
class RepairExecutionTest {

  private static final String STALE_LOCK = "{ \"pid\": 9999999 }";

  @Test
  void refusesAnActionWhoseTargetChangedAfterPlanning(@TempDir Path tempDir) throws Exception {
    Path root = initialized(tempDir);
    Path lockFile = ProjectAdministrativeLayout.cleanupExecutionLock(root);
    Files.writeString(lockFile, STALE_LOCK);
    RepairService service = new RepairService();
    RepairPlan plan = service.preparePlan(root);

    String changed = "{ \"pid\": 9999998 }";
    Files.writeString(lockFile, changed);
    RepairService.ExecutionResult result = service.executePlan(root, plan.planId());

    assertEquals(0, result.completedCount());
    assertEquals(1, result.skippedStaleCount());
    assertEquals(changed, Files.readString(lockFile));
    assertTrue(result.journalRecords().getFirst().details().contains("changed since the plan"));
  }

  @Test
  void archivingAPlanDirectoryDoesNotClaimToHaveArchivedAnything(@TempDir Path tempDir) throws Exception {
    Path root = initialized(tempDir);
    Path corrupt = ProjectAdministrativeLayout.cleanupPlans(root).resolve("broken.json");
    Files.createDirectories(corrupt.getParent());
    Files.writeString(corrupt, "{ not json");
    RepairService service = new RepairService();
    RepairPlan plan = service.preparePlan(root);
    assertTrue(plan.entries().stream().anyMatch(e -> e.action() == RepairAction.ARCHIVE_CORRUPT_ADMIN_PLAN && e.executable()));

    RepairService.ExecutionResult result = service.executePlan(root, plan.planId());

    assertEquals(0, result.completedCount());
    assertEquals(1, result.failedCount());
    assertTrue(result.journalRecords().getFirst().details().contains("Nothing archived"));
    assertTrue(Files.exists(corrupt));
  }

  @Test
  void doesNotRemoveTheRepairLockTheExecutorHolds(@TempDir Path tempDir) throws Exception {
    Path root = initialized(tempDir);
    Files.writeString(ProjectAdministrativeLayout.repairExecutionLock(root), STALE_LOCK);
    RepairService service = new RepairService();
    RepairPlan plan = service.preparePlan(root);
    assertTrue(plan.entries().stream().anyMatch(e -> e.action() == RepairAction.REMOVE_VERIFIED_STALE_REPAIR_LOCK && e.executable()));

    RepairService.ExecutionResult result = service.executePlan(root, plan.planId());

    assertEquals(0, result.completedCount());
    assertEquals(0, result.failedCount());
    assertEquals(1, result.skippedUnsupportedCount());
    assertTrue(result.journalRecords().getFirst().details().contains("held by this executor"));
  }

  @Test
  void anActionWithNoImplementationIsNotRecordedAsCompleted(@TempDir Path tempDir) throws Exception {
    Path root = initialized(tempDir);
    Path target = ProjectAdministrativeLayout.adminDirectory(root).resolve("derived.idx");
    Files.writeString(target, "x");
    RepairPlanEntry entry = new RepairPlanEntry(1, "entry-1", DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK, RepairAction.REBUILD_DERIVED_ADMIN_INDEX, target.toString(), Sha256.hex(target), true,
        List.of("repair_supported"), "rebuild", false);
    RepairPlan plan = new RepairPlanStore().createAndSave(root, "project-a", "doctor-fp", List.of(entry));

    RepairService.ExecutionResult result = new RepairService().executePlan(root, plan.planId());

    assertEquals(0, result.completedCount());
    assertEquals(1, result.skippedUnsupportedCount());
    assertEquals("SKIPPED_UNSUPPORTED", result.journalRecords().getFirst().status());
  }

  @Test
  @SuppressWarnings("try")
  void reacquiringTheRepairLockInTheSameJvmReportsItAsHeld(@TempDir Path tempDir) throws Exception {
    Path root = initialized(tempDir);

    try (RepairExecutionLock _ = RepairExecutionLock.acquire(root, "plan-1")) {
      assertThrows(IOException.class, () -> RepairExecutionLock.acquire(root, "plan-2"));
    }

    assertFalse(Files.exists(ProjectAdministrativeLayout.repairExecutionLock(root)));
    RepairExecutionLock.acquire(root, "plan-3").close();
  }

  private static Path initialized(Path tempDir) throws Exception {
    new ProjectApplicationService().init(tempDir);
    Files.createDirectories(ProjectAdministrativeLayout.adminDirectory(tempDir));
    return tempDir;
  }
}
