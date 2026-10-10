package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.synesis.workspace.lifecycle.AdministrativeJournal;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Append-only execution journal tracking reconciliation execution steps for crash safety and
 * idempotency.
 *
 * <p>Journals are stored under
 * {@code
 * %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\reconciliation-executions\<execution-id>.jsonl}.
 *
 * @since 1.0
 */
public final class ReconciliationExecutionJournal {

    private final AdministrativeJournal journal;

    /**
     * Creates and opens an append-only reconciliation execution journal.
     *
     * @param controlRoot control project root path
     * @param executionId execution run identifier
     * @throws IOException if directory creation fails
     */
    public ReconciliationExecutionJournal(Path controlRoot, String executionId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(executionId, "executionId");

        this.journal = new AdministrativeJournal(ProjectAdministrativeLayout.reconciliationExecutions(controlRoot.toAbsolutePath().normalize()), executionId);
    }

    /**
     * Reads all historical execution journals under the project's administration directory to find
     * action IDs that were previously completed for a plan.
     *
     * @param controlRoot control project root path
     * @param planId      target plan identifier
     * @return set of action IDs that have reached COMPLETED state for this plan
     */
    public static Set<String> loadCompletedActionIds(Path controlRoot, String planId) {
        return AdministrativeJournal.completedIds(controlRoot, ProjectAdministrativeLayout::reconciliationExecutions, planId, "actionId", ReconciliationExecutionRecord.COMPLETED);
    }

    /**
     * Appends an execution record to the journal file.
     *
     * @param record execution record to append
     * @throws IOException if writing fails
     */
    public void append(ReconciliationExecutionRecord record) throws IOException {
        Objects.requireNonNull(record, "record");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", record.executionId());
        map.put("planId", record.planId());
        map.put("actionId", record.actionId());
        map.put("action", record.action().name());
        map.put("targetResourceId", record.targetResourceId());
        map.put("state", record.state());
        map.put("preconditionReason", record.preconditionReason());
        map.put("timestampEpochMillis", record.timestampEpochMillis());
        map.put("diagnosticDetails", record.diagnosticDetails());

        journal.append(map);
    }
}
