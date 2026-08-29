package org.synesis.workspace.lifecycle.cleanup;

import java.util.Objects;

/**
 * Single journal record capturing entry execution state in an append-only JSONL journal.
 *
 * @param executionId          unique execution run identifier
 * @param planId               persisted plan identifier
 * @param entryResourceId      resource identifier
 * @param resourceType         managed lifecycle resource type
 * @param state                entry execution state
 * @param preconditionReason   precondition evaluation reason code
 * @param timestampEpochMillis timestamp in epoch milliseconds
 * @param bytesReclaimed       actual bytes reclaimed
 * @param diagnosticDetails    human-readable diagnostic summary
 * @since 1.0
 */
public record CleanupExecutionRecord(
        String executionId,
        String planId,
        String entryResourceId,
        LifecycleResourceType resourceType,
        CleanupEntryExecutionState state,
        String preconditionReason,
        long timestampEpochMillis,
        long bytesReclaimed,
        String diagnosticDetails
) {

    /**
     * Invariant validation.
     */
    public CleanupExecutionRecord {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(entryResourceId, "entryResourceId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preconditionReason, "preconditionReason");
        Objects.requireNonNull(diagnosticDetails, "diagnosticDetails");
    }
}
