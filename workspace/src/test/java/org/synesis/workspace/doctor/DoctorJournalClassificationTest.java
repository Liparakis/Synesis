package org.synesis.workspace.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins which findings a transaction journal yields, in emission order, with the suffix of each
 * evidence fingerprint seed.
 */
class DoctorJournalClassificationTest {

  private static List<DoctorFindingCode> codes(String... lines) {
    return DoctorService.classifyJournal(List.of(lines)).stream()
        .map(DoctorService.JournalState::code)
        .toList();
  }

  @Test
  void terminalJournalYieldsNothing() {
    assertEquals(List.of(), codes("state=MIGRATION_PREPARED", "state=MIGRATED"));
    assertEquals(List.of(), codes("outcome=UP_TO_DATE projectId=p"));
  }

  @Test
  void journalWithoutTerminalLastLineIsIncomplete() {
    List<DoctorService.JournalState> states =
        DoctorService.classifyJournal(List.of("state=MIGRATION_PREPARED", "state=BACKUPS_VERIFIED"));

    assertEquals(
        List.of(new DoctorService.JournalState(DoctorFindingCode.UPDATE_TRANSACTION_INCOMPLETE, "")),
        states);
  }

  @Test
  void restoreRequiredWithoutVerifiedOrReviewIsPending() {
    assertEquals(
        List.of(
            DoctorFindingCode.PROJECT_RESTORATION_PENDING,
            DoctorFindingCode.UPDATE_TRANSACTION_INCOMPLETE),
        codes("state=RESTORE_REQUIRED", "state=RESTORING_METADATA"));
    assertEquals(
        List.of(DoctorFindingCode.UPDATE_TRANSACTION_INCOMPLETE),
        codes("state=RESTORE_REQUIRED", "state=RESTORE_VERIFIED"));
  }

  @Test
  void failedRestoredAndReviewMarkersEmitInOrder() {
    assertEquals(
        List.of(
            DoctorFindingCode.PROJECT_RESTORATION_REQUIRES_REVIEW,
            DoctorFindingCode.PROJECT_MIGRATION_RESTORED),
        codes(
            "state=RESTORE_REQUIRED",
            "state=RESTORE_FAILED_REQUIRES_REVIEW x",
            "state=FAILED_RESTORED y"));
  }

  @Test
  void everyOtherMarkerMapsToItsCode() {
    assertEquals(
        List.of(DoctorFindingCode.POST_MIGRATION_REPLAY_FAILED),
        codes("state=post_migration_replay_failed", "state=MIGRATED"));
    assertEquals(
        List.of(DoctorFindingCode.MIGRATION_BACKUP_MISSING),
        codes("state=restore_failed", "state=MIGRATED"));
    assertEquals(
        List.of(DoctorFindingCode.ACTIVE_SESSION_BLOCKS_MIGRATION),
        codes("state=active_session_blocks_project_migration", "state=MIGRATED"));
    assertEquals(
        List.of(DoctorFindingCode.ROLLBACK_RESTORATION_INCOMPLETE),
        codes("state=ROLLBACK_REQUIRES_HUMAN_REVIEW", "state=MIGRATED"));
  }
}
