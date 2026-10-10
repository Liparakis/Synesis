package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.lifecycle.cleanup.execution.CleanupEntryExecutionState;
import org.synesis.workspace.lifecycle.cleanup.execution.CleanupExecutionJournal;
import org.synesis.workspace.lifecycle.cleanup.execution.CleanupExecutionRecord;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleResourceType;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationAction;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationExecutionJournal;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationExecutionRecord;
import org.synesis.workspace.lifecycle.repair.RepairExecutionJournal;

/** Pins the exact JSONL lines the three administrative execution journals append. */
class LifecycleExecutionJournalFormatTest {

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
  void cleanupJournalLineAndCompletedIds() throws IOException {
    new CleanupExecutionJournal(controlRoot, "exec-1")
        .append(cleanupRecord("exec-1", "res-1", CleanupEntryExecutionState.COMPLETED));
    new CleanupExecutionJournal(controlRoot, "exec-2")
        .append(cleanupRecord("exec-2", "res-2", CleanupEntryExecutionState.COMPLETED));
    new CleanupExecutionJournal(controlRoot, "exec-2")
        .append(cleanupRecord("exec-2", "res-3", CleanupEntryExecutionState.EXECUTING));

    assertEquals(
        """
        {"executionId":"exec-1","planId":"plan-1","entryResourceId":"res-1","resourceType":"TEMPORARY_FILE","state":"COMPLETED","preconditionReason":"temporary_file_expired","timestampEpochMillis":1700000000000,"bytesReclaimed":2048,"diagnosticDetails":"deleted"}
        """,
        Files.readString(
            workspaceRoot.resolve("admin/cleanup-executions/exec-1.jsonl"), StandardCharsets.UTF_8));
    assertEquals(
        Set.of("res-1", "res-2"), CleanupExecutionJournal.loadCompletedResourceIds(controlRoot, "plan-1"));
    assertEquals(Set.of(), CleanupExecutionJournal.loadCompletedResourceIds(controlRoot, "plan-2"));
  }

  @Test
  void reconciliationJournalLineAndCompletedIds() throws IOException {
    new ReconciliationExecutionJournal(controlRoot, "exec-1")
        .append(reconciliationRecord("exec-1", "act-1", "COMPLETED"));
    new ReconciliationExecutionJournal(controlRoot, "exec-1")
        .append(reconciliationRecord("exec-1", "act-2", "EXECUTING"));
    new ReconciliationExecutionJournal(controlRoot, "exec-2")
        .append(reconciliationRecord("exec-2", "act-3", "COMPLETED"));

    assertEquals(
        """
        {"executionId":"exec-1","planId":"plan-1","actionId":"act-1","action":"MARK_SESSION_SUSPENDED","targetResourceId":"session-1","state":"COMPLETED","preconditionReason":"lease_expired","timestampEpochMillis":1700000000000,"diagnosticDetails":"suspended"}
        {"executionId":"exec-1","planId":"plan-1","actionId":"act-2","action":"MARK_SESSION_SUSPENDED","targetResourceId":"session-1","state":"EXECUTING","preconditionReason":"lease_expired","timestampEpochMillis":1700000000000,"diagnosticDetails":"suspended"}
        """,
        Files.readString(
            workspaceRoot.resolve("admin/reconciliation-executions/exec-1.jsonl"),
            StandardCharsets.UTF_8));
    assertEquals(
        Set.of("act-1", "act-3"),
        ReconciliationExecutionJournal.loadCompletedActionIds(controlRoot, "plan-1"));
    assertEquals(
        Set.of(), ReconciliationExecutionJournal.loadCompletedActionIds(controlRoot, "plan-2"));
  }

  @Test
  void repairJournalLine() throws IOException {
    RepairExecutionJournal.open(controlRoot, "exec-1")
        .append(
            new RepairExecutionJournal.RepairExecutionRecord(
                "exec-1",
                "repairplan-1",
                "entry-1",
                "REMOVE_VERIFIED_STALE_CLEANUP_LOCK",
                "admin/cleanup.lock",
                "COMPLETED",
                1_700_000_000_000L,
                "removed"));

    assertEquals(
        """
        {"executionId":"exec-1","planId":"repairplan-1","entryId":"entry-1","action":"REMOVE_VERIFIED_STALE_CLEANUP_LOCK","targetPath":"admin/cleanup.lock","status":"COMPLETED","timestampEpochMillis":1700000000000,"details":"removed"}
        """,
        Files.readString(
            workspaceRoot.resolve("admin/repair-executions/exec-1.jsonl"), StandardCharsets.UTF_8));
  }

  private static CleanupExecutionRecord cleanupRecord(
      String executionId, String resourceId, CleanupEntryExecutionState state) {
    return new CleanupExecutionRecord(
        executionId,
        "plan-1",
        resourceId,
        LifecycleResourceType.TEMPORARY_FILE,
        state,
        "temporary_file_expired",
        1_700_000_000_000L,
        2048L,
        "deleted");
  }

  private static ReconciliationExecutionRecord reconciliationRecord(
      String executionId, String actionId, String state) {
    return new ReconciliationExecutionRecord(
        executionId,
        "plan-1",
        actionId,
        ReconciliationAction.MARK_SESSION_SUSPENDED,
        "session-1",
        state,
        "lease_expired",
        1_700_000_000_000L,
        "suspended");
  }
}
