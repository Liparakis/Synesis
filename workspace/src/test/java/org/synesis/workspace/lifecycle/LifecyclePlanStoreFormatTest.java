package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleResourceFingerprint;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleResourceType;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupClassification;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupPlan;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupPlanEntry;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupPlanStore;
import org.synesis.workspace.lifecycle.cleanup.plan.PersistedCleanupPlan;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationAction;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationPlan;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationPlanEntry;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationPlanStore;
import org.synesis.workspace.lifecycle.repair.RepairAction;
import org.synesis.workspace.lifecycle.repair.RepairPlan;
import org.synesis.workspace.lifecycle.repair.RepairPlanEntry;
import org.synesis.workspace.lifecycle.repair.RepairPlanStore;

/**
 * Pins the persisted bytes of the cleanup, reconciliation, and repair plan files.
 *
 * <p>The content hash covers the plan id, the machine paths, and (for two of the stores) the
 * creation time, so the tests normalize those and recompute the hash from the file text with the
 * hash field replaced by the {@code UNSIGNED} marker.
 */
class LifecyclePlanStoreFormatTest {

  @TempDir Path temp;

  private Path controlRoot;
  private Path workspaceRoot;

  @BeforeEach
  void createProject() throws IOException {
    controlRoot = temp.resolve("control");
    Files.createDirectories(controlRoot.resolve(".synesis"));
    Files.writeString(
        controlRoot.resolve(".synesis/project.json"),
        "{\"projectId\":\"p6-" + UUID.randomUUID() + "\"}",
        StandardCharsets.UTF_8);
    workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
  }

  @AfterEach
  void removeWorkspace() throws IOException {
    if (Files.exists(workspaceRoot)) {
      try (Stream<Path> files = Files.walk(workspaceRoot)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(file);
        }
      }
    }
  }

  @Test
  void cleanupPlanFileFormat() throws IOException {
    CleanupPlanStore store = new CleanupPlanStore();
    PersistedCleanupPlan saved = store.createAndSave(controlRoot, cleanupPlan());

    assertTrue(saved.planId().matches("plan-[0-9a-f]{32}"));
    Path file = workspaceRoot.resolve("admin/cleanup-plans").resolve(saved.planId() + ".json");
    String text = Files.readString(file, StandardCharsets.UTF_8);

    assertEquals(
        """
        {"schemaVersion":1,"planId":"<PLAN>","projectId":"project-a","controlRepositoryPath":"<CONTROL>","externalWorkspaceRoot":"<WORKSPACE>","createdAtEpochMillis":1700000000000,"retentionPolicySnapshot":{"workerRetentionHours":"24","validationRetentionHours":"24","integrationRetentionHours":"24","evidenceRetentionDays":"7","temporaryFileRetentionHours":"1"},"durableStateSequence":1700000000000,"totalDiscoveredCount":2,"totalExecutableCount":1,"totalEstimatedReclaimableBytes":2048,"contentHash":"<HASH>","entries":[{"schemaVersion":1,"resourceType":"TEMPORARY_FILE","resourceId":"temp-1","resourcePath":"","classification":"CLEANUP_ELIGIBLE","eligible":true,"reasons":["temporary_file_expired"],"estimatedBytes":2048,"pathSafetyCode":"path_verified","proposedOperation":"DELETE_TEMPORARY_FILE","fingerprint":{"normalizedIdentity":"temp-1","durableStateVersion":7,"gitHead":"","gitCommonDir":"","cleanStatusDigest":"","metadataHash":"hash-1"}},{"schemaVersion":1,"resourceType":"WORKER_WORKTREE","resourceId":"worker-1","resourcePath":"","classification":"PROTECTED","eligible":false,"reasons":["control_checkout_protected","dirty"],"estimatedBytes":0,"pathSafetyCode":"control_checkout_protected","proposedOperation":"NONE","fingerprint":{"normalizedIdentity":"worker-1","durableStateVersion":9,"gitHead":"abc123","gitCommonDir":"common","cleanStatusDigest":"digest","metadataHash":"hash-2"}}]}
        """
            .strip(),
        normalize(text, saved.planId(), saved.contentHash()));
    assertContentHashCovers(text, saved.contentHash());
    PersistedCleanupPlan loaded = store.load(controlRoot, saved.planId());
    assertEquals(saved.contentHash(), loaded.contentHash());
    assertEquals(saved.entries().get(1), loaded.entries().get(1));
    // A null fingerprint field is written as "" and loads back as "".
    assertEquals("", loaded.entries().get(0).fingerprint().gitHead());

    Files.writeString(
        file,
        text.replace("\"totalDiscoveredCount\":2", "\"totalDiscoveredCount\":3"),
        StandardCharsets.UTF_8);
    IOException rejected =
        assertThrows(IOException.class, () -> store.load(controlRoot, saved.planId()));
    assertEquals(
        "Cleanup plan content hash integrity verification failed for " + saved.planId(),
        rejected.getMessage());
  }

  @Test
  void reconciliationPlanFileFormat() throws IOException {
    ReconciliationPlanStore store = new ReconciliationPlanStore();
    ReconciliationPlan saved =
        store.createAndSave(
            controlRoot,
            "project-a",
            5,
            List.of(
                new ReconciliationPlanEntry(
                    1,
                    "act-1",
                    ReconciliationAction.MARK_SESSION_SUSPENDED,
                    "session-1",
                    true,
                    List.of("lease_expired", "no_live_process"),
                    "lease expired; process absent"),
                new ReconciliationPlanEntry(
                    1,
                    "act-2",
                    ReconciliationAction.HOLD_SUSPENDED_RECOVERY,
                    "task-2",
                    false,
                    List.of(),
                    "")));

    assertTrue(saved.planId().matches("recplan-[0-9a-f]{32}"));
    Path file =
        workspaceRoot.resolve("admin/reconciliation-plans").resolve(saved.planId() + ".json");
    String text = Files.readString(file, StandardCharsets.UTF_8);

    assertEquals(
        """
        {"schemaVersion":1,"planId":"<PLAN>","projectId":"project-a","controlRepositoryPath":"<CONTROL>","externalWorkspaceRoot":"<WORKSPACE>","createdAtEpochMillis":<MILLIS>,"totalInspectedCount":5,"executableCount":1,"contentHash":"<HASH>","entries":[{"schemaVersion":1,"actionId":"act-1","action":"MARK_SESSION_SUSPENDED","targetResourceId":"session-1","executable":true,"reasons":["lease_expired","no_live_process"],"preconditionSummary":"lease expired; process absent"},{"schemaVersion":1,"actionId":"act-2","action":"HOLD_SUSPENDED_RECOVERY","targetResourceId":"task-2","executable":false,"reasons":[],"preconditionSummary":""}]}
        """
            .strip(),
        millisMasked(normalize(text, saved.planId(), saved.contentHash())));
    assertContentHashCovers(text, saved.contentHash());
    assertEquals(saved, store.load(controlRoot, saved.planId()));

    Files.writeString(
        file,
        text.replace("\"executableCount\":1", "\"executableCount\":2"),
        StandardCharsets.UTF_8);
    IOException rejected =
        assertThrows(IOException.class, () -> store.load(controlRoot, saved.planId()));
    assertEquals(
        "Reconciliation plan content hash integrity verification failed for " + saved.planId(),
        rejected.getMessage());
  }

  @Test
  void repairPlanFileFormat() throws IOException {
    RepairPlanStore store = new RepairPlanStore();
    RepairPlan saved =
        store.createAndSave(
            controlRoot,
            "project-a",
            "doctor-fingerprint",
            List.of(
                new RepairPlanEntry(
                    1,
                    "entry-1",
                    DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK,
                    RepairAction.REMOVE_VERIFIED_STALE_CLEANUP_LOCK,
                    "admin/cleanup.lock",
                    "fp-1",
                    true,
                    List.of("lock_stale"),
                    "Remove the stale lock",
                    false),
                new RepairPlanEntry(
                    1,
                    "entry-2",
                    DoctorFindingCode.CORRUPT_CLEANUP_PLAN,
                    RepairAction.ARCHIVE_CORRUPT_ADMIN_PLAN,
                    "admin/cleanup-plans/x.json",
                    "fp-2",
                    false,
                    List.of(),
                    "Archive the plan",
                    true)));

    assertTrue(saved.planId().matches("repairplan-[0-9a-f]{32}"));
    Path file = workspaceRoot.resolve("admin/repair-plans").resolve(saved.planId() + ".json");
    String text = Files.readString(file, StandardCharsets.UTF_8);

    assertEquals(
        """
        {"schemaVersion":1,"planId":"<PLAN>","projectId":"project-a","controlRepositoryPath":"<CONTROL>","externalWorkspaceRoot":"<WORKSPACE>","createdAtEpochMillis":<MILLIS>,"doctorReportFingerprint":"doctor-fingerprint","supportedRepairsCount":1,"unsupportedCount":1,"contentHash":"<HASH>","entries":[{"schemaVersion":1,"entryId":"entry-1","findingCode":"STALE_CLEANUP_EXECUTION_LOCK","action":"REMOVE_VERIFIED_STALE_CLEANUP_LOCK","targetPath":"admin/cleanup.lock","targetFingerprint":"fp-1","executable":true,"reasons":["lock_stale"],"summary":"Remove the stale lock","backupRequired":false},{"schemaVersion":1,"entryId":"entry-2","findingCode":"CORRUPT_CLEANUP_PLAN","action":"ARCHIVE_CORRUPT_ADMIN_PLAN","targetPath":"admin/cleanup-plans/x.json","targetFingerprint":"fp-2","executable":false,"reasons":[],"summary":"Archive the plan","backupRequired":true}]}
        """
            .strip(),
        millisMasked(normalize(text, saved.planId(), saved.contentHash())));
    assertContentHashCovers(text, saved.contentHash());
    assertEquals(saved, store.load(controlRoot, saved.planId()));

    Files.writeString(
        file,
        text.replace("\"supportedRepairsCount\":1", "\"supportedRepairsCount\":2"),
        StandardCharsets.UTF_8);
    IOException rejected =
        assertThrows(IOException.class, () -> store.load(controlRoot, saved.planId()));
    assertEquals(
        "Repair plan content hash integrity verification failed for " + saved.planId(),
        rejected.getMessage());
  }

  private CleanupPlan cleanupPlan() {
    return new CleanupPlan(
        "project-a",
        1_700_000_000_000L,
        2,
        1,
        0,
        0,
        0,
        1,
        0,
        2048L,
        false,
        List.of(
            new CleanupPlanEntry(
                LifecycleResourceType.TEMPORARY_FILE,
                "temp-1",
                null,
                CleanupClassification.CLEANUP_ELIGIBLE,
                true,
                List.of("temporary_file_expired"),
                2048L,
                "Expired",
                List.of("temp"),
                "NOT_APPLICABLE",
                false,
                "path_verified",
                ProcessEvidenceState.NOT_OBSERVED,
                new LifecycleResourceFingerprint("temp-1", 7L, null, null, null, "hash-1"),
                "DELETE_TEMPORARY_FILE"),
            new CleanupPlanEntry(
                LifecycleResourceType.WORKER_WORKTREE,
                "worker-1",
                null,
                CleanupClassification.PROTECTED,
                false,
                List.of("control_checkout_protected", "dirty"),
                0L,
                "Protected",
                List.of(),
                "REGISTERED",
                true,
                "control_checkout_protected",
                ProcessEvidenceState.NOT_OBSERVED,
                new LifecycleResourceFingerprint("worker-1", 9L, "abc123", "common", "digest", "hash-2"),
                "NONE")));
  }

  private String normalize(String text, String planId, String contentHash) {
    return text.replace(
            ProviderJson.write(controlRoot.toAbsolutePath().normalize().toString()), "\"<CONTROL>\"")
        .replace(ProviderJson.write(workspaceRoot.toString()), "\"<WORKSPACE>\"")
        .replace(planId, "<PLAN>")
        .replace(contentHash, "<HASH>");
  }

  private static String millisMasked(String text) {
    return text.replaceAll("\"createdAtEpochMillis\":\\d+", "\"createdAtEpochMillis\":<MILLIS>");
  }

  private static void assertContentHashCovers(String text, String contentHash) {
    String unsigned =
        text.replace("\"contentHash\":\"" + contentHash + "\"", "\"contentHash\":\"UNSIGNED\"");
    assertEquals(Sha256.hex(unsigned), contentHash);
  }
}
