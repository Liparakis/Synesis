package org.synesis.workspace.lifecycle.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

/** Exercises cleanup journal replay and restart recovery. */
class JournalAndRestartTest {

    @Test
    void recordsJournalAndLoadsCompletedEntriesOnRestart(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        CleanupExecutionJournal journal = new CleanupExecutionJournal(controlRoot, "exec-run-1");
        journal.append(new CleanupExecutionRecord(
                "exec-run-1", "plan-100", "res-1", LifecycleResourceType.TEMPORARY_FILE,
                CleanupEntryExecutionState.COMPLETED, "file_deleted", System.currentTimeMillis(),
                500L, "Deleted"
        ));

        Set<String> completed = CleanupExecutionJournal.loadCompletedResourceIds(controlRoot, "plan-100");
        assertEquals(1, completed.size());
        assertTrue(completed.contains("res-1"));
    }
}
