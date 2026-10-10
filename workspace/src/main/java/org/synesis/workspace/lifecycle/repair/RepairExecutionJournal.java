package org.synesis.workspace.lifecycle.repair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.lifecycle.AdministrativeJournal;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Append-only execution journal logging repair execution events under
 * {@code
 * %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-executions\<execution-id>.jsonl}.
 *
 * @since 1.0
 */
public final class RepairExecutionJournal {

    private final AdministrativeJournal journal;

    private RepairExecutionJournal(AdministrativeJournal journal) {
        this.journal = journal;
    }

    /**
     * Creates or opens a repair execution journal.
     *
     * @param controlRoot control project root path
     * @param executionId opaque execution ID
     * @return open journal instance
     * @throws IOException if journal directory creation fails
     */
    public static RepairExecutionJournal open(Path controlRoot, String executionId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(executionId, "executionId");

        return new RepairExecutionJournal(new AdministrativeJournal(ProjectAdministrativeLayout.repairExecutions(controlRoot), executionId));
    }

    /**
     * Appends an execution record to the JSONL log file.
     *
     * @param record execution record to append
     * @throws IOException if appending fails
     */
    public void append(RepairExecutionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", record.executionId());
        map.put("planId", record.planId());
        map.put("entryId", record.entryId());
        map.put("action", record.action());
        map.put("targetPath", record.targetPath());
        map.put("status", record.status());
        map.put("timestampEpochMillis", record.timestampEpochMillis());
        map.put("details", record.details());

        journal.append(map);
    }

    /**
     * Immutable execution record.
     *
     * @param executionId          opaque execution ID
     * @param planId               repair plan ID
     * @param entryId              plan entry ID
     * @param action               repair action
     * @param targetPath           target path
     * @param status               execution status state
     * @param timestampEpochMillis timestamp
     * @param details              explanation message
     */
    public record RepairExecutionRecord(String executionId, String planId, String entryId, String action, String targetPath, String status, long timestampEpochMillis, String details) {

        static final String COMPLETED = "COMPLETED";
        static final String SKIPPED_STALE = "SKIPPED_STALE";
        static final String SKIPPED_UNSUPPORTED = "SKIPPED_UNSUPPORTED";
        static final String FAILED_REQUIRES_REVIEW = "FAILED_REQUIRES_REVIEW";
        static final String ROLLED_BACK = "ROLLED_BACK";

        /**
         * Validates non-null field invariants.
         */
        public RepairExecutionRecord {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(targetPath, "targetPath");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(details, "details");
        }
    }
}
