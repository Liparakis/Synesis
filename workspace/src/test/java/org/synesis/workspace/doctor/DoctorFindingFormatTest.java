package org.synesis.workspace.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

/**
 * Pins the wording, classification, and evidence fingerprint of the doctor findings that a small
 * constructed project state produces.
 *
 * <p>The healthy finding and the install, command-namespace, session, and migration findings
 * depend on host-wide state or on live processes and are not covered.
 */
class DoctorFindingFormatTest {

  @TempDir Path temp;

  private String previousUserHome;
  private Path workspaceRoot;

  @BeforeEach
  void isolateUserHome() throws Exception {
    previousUserHome = System.getProperty("user.home");
    System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
  }

  @AfterEach
  void restore() throws Exception {
    if (previousUserHome == null) {
      System.clearProperty("user.home");
    } else {
      System.setProperty("user.home", previousUserHome);
    }
    if (workspaceRoot != null && Files.exists(workspaceRoot)) {
      try (Stream<Path> files = Files.walk(workspaceRoot)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(file);
        }
      }
    }
  }

  @Test
  void projectNotInitialized() throws Exception {
    DoctorFinding finding =
        find(
            new DoctorService().diagnose(Files.createDirectories(temp.resolve("empty"))),
            DoctorFindingCode.PROJECT_NOT_INITIALIZED);

    assertFinding(
        finding,
        DoctorSeverity.ERROR,
        DoctorConfidence.CONFIRMED,
        "Project not initialized",
        "Directory is missing .synesis/project.json configuration.",
        "project",
        false,
        DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
        "d5876c86aabd9d8832978c17d14bdfb6fc75299ea747a09579b5f8c44a612d37");
  }

  @Test
  void projectIdentityInvalid() throws Exception {
    Path root = initializedProject();
    Files.writeString(root.resolve(".synesis/project.json"), "{broken");

    DoctorFinding finding =
        find(new DoctorService().diagnose(root), DoctorFindingCode.PROJECT_IDENTITY_INVALID);

    assertFinding(
        finding,
        DoctorSeverity.ERROR,
        DoctorConfidence.CONFIRMED,
        "Invalid project identity",
        "Failed to locate or parse valid Synesis project identity.",
        "project",
        false,
        DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
        "96fe727d3e1622d36b2839cbdde78f9c98e7abeb9ab55ed22fb39a96d62342d3");
  }

  @Test
  void staleExecutionLocks() throws Exception {
    Path root = initializedProject();
    Path admin = Files.createDirectories(workspaceRoot.resolve("admin"));
    for (String lock :
        List.of(
            "cleanup-execution.lock", "reconciliation-execution.lock", "repair-execution.lock")) {
      Files.writeString(admin.resolve(lock), "{ \"pid\": 9999999 }");
    }
    DoctorReport report = new DoctorService().diagnose(root);

    assertFinding(
        find(report, DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK),
        DoctorSeverity.WARNING,
        DoctorConfidence.HIGH_CONFIDENCE,
        "Stale cleanup execution lock",
        "Stale cleanup-execution.lock file present from absent process.",
        "admin_lock",
        true,
        DoctorRecommendation.PREPARE_REPAIR_PLAN,
        "378d84fa983d2345e0b4474f83502f713539f7842acc390d344264d7ab48deec");
    assertFinding(
        find(report, DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK),
        DoctorSeverity.WARNING,
        DoctorConfidence.HIGH_CONFIDENCE,
        "Stale reconciliation execution lock",
        "Stale reconciliation-execution.lock file present from absent process.",
        "admin_lock",
        true,
        DoctorRecommendation.PREPARE_REPAIR_PLAN,
        "b1aea0c6e365cd01447d7b72e5ba9ee1c9e6c375ea1f77ddfae56b8c2fb6ee04");
    assertFinding(
        find(report, DoctorFindingCode.STALE_REPAIR_LOCK),
        DoctorSeverity.WARNING,
        DoctorConfidence.HIGH_CONFIDENCE,
        "Stale repair execution lock",
        "Stale repair-execution.lock file present from absent process.",
        "admin_lock",
        true,
        DoctorRecommendation.PREPARE_REPAIR_PLAN,
        "b45d9124c4136b4540ddea96db546145e80c35fca8cd37a82c3f34606a4f6b3c");
  }

  @Test
  void corruptAdministrativePlans() throws Exception {
    Path root = initializedProject();
    Path admin = workspaceRoot.resolve("admin");
    Files.writeString(
        Files.createDirectories(admin.resolve("cleanup-plans")).resolve("bad-plan.json"), "{broken");
    Files.writeString(
        Files.createDirectories(admin.resolve("reconciliation-plans")).resolve("bad-recon.json"),
        "{broken");
    DoctorReport report = new DoctorService().diagnose(root);

    assertFinding(
        find(report, DoctorFindingCode.CORRUPT_CLEANUP_PLAN),
        DoctorSeverity.WARNING,
        DoctorConfidence.CONFIRMED,
        "Corrupt cleanup plan file corrupted",
        "Corrupt cleanup plan file contains malformed JSON.",
        "admin_plan",
        true,
        DoctorRecommendation.PREPARE_REPAIR_PLAN,
        "4063374977e4c6f5c19c7167e3c6eab6a058ca6713e9f4d449027fba26734684");
    assertFinding(
        find(report, DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN),
        DoctorSeverity.WARNING,
        DoctorConfidence.CONFIRMED,
        "Corrupt reconciliation plan file corrupted",
        "Corrupt reconciliation plan file contains malformed JSON.",
        "admin_plan",
        true,
        DoctorRecommendation.PREPARE_REPAIR_PLAN,
        "4df1aa5793c3c84b536e802dc6ec18fe4b6b505719a95cb6e267dce37b26d7c6");
  }

  private Path initializedProject() throws Exception {
    Path root = Files.createDirectories(temp.resolve("project"));
    new ProjectApplicationService().init(root);
    workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
    return root;
  }

  private static DoctorFinding find(DoctorReport report, DoctorFindingCode code) {
    return report.findings().stream()
        .filter(finding -> finding.code() == code)
        .findFirst()
        .orElseThrow(() -> new AssertionError(code + " not in " + report.findings()));
  }

  private static void assertFinding(
      DoctorFinding finding,
      DoctorSeverity severity,
      DoctorConfidence confidence,
      String summary,
      String explanation,
      String resourceType,
      boolean repairSupported,
      DoctorRecommendation recommendation,
      String fingerprint) {
    assertEquals(severity, finding.severity());
    assertEquals(confidence, finding.confidence());
    assertEquals(summary, finding.summary());
    assertEquals(explanation, finding.explanation());
    assertEquals(resourceType, finding.affectedResourceType());
    assertEquals(repairSupported, finding.repairSupported());
    assertEquals(recommendation, finding.recommendation());
    assertEquals(fingerprint, finding.evidenceFingerprint());
  }
}
