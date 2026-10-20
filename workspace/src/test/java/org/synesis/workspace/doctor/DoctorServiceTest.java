package org.synesis.workspace.doctor;

import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleInventoryService;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupEligibilityService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * Exercises workspace doctor diagnostics and failure classification.
 */
public class DoctorServiceTest {

  private String previousUserHome;

  @BeforeEach
  void isolateUserHome() throws IOException {
    previousUserHome = System.getProperty("user.home");
    System.setProperty("user.home", Files.createTempDirectory("synesis-doctor-home-").toString());
  }

  @AfterEach
  void restoreUserHome() {
    if (previousUserHome == null) {
      System.clearProperty("user.home");
    } else {
      System.setProperty("user.home", previousUserHome);
    }
  }

  @Test
  public void testDoctorReadOnlyGuarantee(@TempDir Path tempDir) throws Exception {
    ProjectApplicationService projectService = new ProjectApplicationService();
    projectService.init(tempDir);

    long countBefore = countFiles(tempDir);

    DoctorService doctorService = new DoctorService();
    DoctorReport report = doctorService.diagnose(tempDir);

    long countAfter = countFiles(tempDir);
    assertEquals(countBefore, countAfter,
        "DoctorService must create, modify, or delete zero files");
    assertNotNull(report);
    // The durable command namespace is host-wide, so prior tests may leave valid terminal
    // evidence or dead anchors for the existing cleanup workflow to report.
    assertTrue(report.overallStatus() == DoctorStatus.HEALTHY
            || report.overallStatus() == DoctorStatus.DEGRADED,
        report.findings()
            .toString());
    assertTrue(report.findings()
            .stream()
            .allMatch(f -> f.code() == DoctorFindingCode.HEALTHY
                || f.code() == DoctorFindingCode.COMMAND_NAMESPACE_RECONCILIATION_REQUIRED
                || f.code() == DoctorFindingCode.COMMAND_CAPACITY_OR_RETENTION),
        report.findings()
            .toString());
  }

  @Test
  public void testDoctorDetectionUninitialized(@TempDir Path tempDir) {
    DoctorService doctorService = new DoctorService();
    DoctorReport report = doctorService.diagnose(tempDir);

    assertEquals(DoctorStatus.UNHEALTHY, report.overallStatus());
    assertTrue(report.findings()
        .stream()
        .anyMatch(f -> f.code() == DoctorFindingCode.PROJECT_NOT_INITIALIZED));
  }

  @Test
  public void testDoctorSeverityAndStatusMapping(@TempDir Path tempDir) throws Exception {
    ProjectApplicationService projectService = new ProjectApplicationService();
    projectService.init(tempDir);

    Path workspaceRoot = org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier.resolveWorkspaceRoot(
        tempDir);
    Path adminDir = workspaceRoot.resolve("admin");
    Files.createDirectories(adminDir);
    Files.writeString(adminDir.resolve("cleanup-execution.lock"), "{ \"pid\": 9999999 }");

    DoctorService doctorService = new DoctorService();
    DoctorReport report = doctorService.diagnose(tempDir);

    assertEquals(DoctorStatus.DEGRADED, report.overallStatus());
    assertTrue(report.repairAvailable());
    assertTrue(report.findings()
        .stream()
        .anyMatch(f -> f.code() == DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK));
  }

  @Test
  public void testDoctorReportsAnInstallerDirectoryThatLostItsLauncher(@TempDir Path tempDir)
      throws Exception {
    Path bin = Files.createDirectories(tempDir.resolve("installer/Synesis/bin"));

    assertTrue(launcherFindings(tempDir, bin.resolve("synesis")),
        "the installer's directory exists and holds no launcher");
  }

  @Test
  public void testDoctorAcceptsTheLauncherWhereTheInstallerPutsIt(@TempDir Path tempDir)
      throws Exception {
    Path bin = Files.createDirectories(tempDir.resolve("installer/Synesis/bin"));
    Path launcher = Files.writeString(bin.resolve("synesis"), "#!/bin/sh\n");

    assertFalse(launcherFindings(tempDir, launcher));
  }

  @Test
  public void testDoctorDoesNotReportAMachineWithoutAnInstallation(@TempDir Path tempDir)
      throws Exception {
    assertFalse(launcherFindings(tempDir, tempDir.resolve("installer/Synesis/bin/synesis")));
  }

  private static boolean launcherFindings(Path project, Path launcher) throws Exception {
    new ProjectApplicationService().init(project);
    DoctorService doctor = new DoctorService(new ProjectApplicationService(),
        new LifecycleInventoryService(),
        new CleanupEligibilityService(),
        new SessionLeaseService(),
        new SessionLeaseStore(),
        ProcessInspector.system(), () -> launcher);
    return doctor.diagnose(project).findings().stream()
        .anyMatch(f -> f.code() == DoctorFindingCode.MISSING_INSTALLED_LAUNCHER);
  }

  private long countFiles(Path dir) throws IOException {
    try (var stream = Files.walk(dir)) {
      return stream.count();
    }
  }
}
