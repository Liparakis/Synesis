package org.synesis.workspace.lifecycle.reconciliation;

import java.util.Objects;

/**
 * Single journal record capturing action execution state in an append-only JSONL journal.
 *
 * @param executionId          unique execution run identifier
 * @param planId               persisted plan identifier
 * @param actionId             action identifier
 * @param action               reconciliation action
 * @param targetResourceId     target resource identifier
 * @param state                action execution state string (e.g. COMPLETED, SKIPPED_STALE, FAILED_REQUIRES_REVIEW)
 * @param preconditionReason   precondition evaluation reason code
 * @param timestampEpochMillis timestamp in epoch milliseconds
 * @param diagnosticDetails    human-readable diagnostic summary
 * @since 1.0
 */
public record ReconciliationExecutionRecord(
        String executionId,
        String planId,
        String actionId,
        ReconciliationAction action,
        String targetResourceId,
        String state,
        String preconditionReason,
        long timestampEpochMillis,
        String diagnosticDetails
) {

    /**
     * Invariant validation.
     */
    public ReconciliationExecutionRecord {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetResourceId, "targetResourceId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preconditionReason, "preconditionReason");
        Objects.requireNonNull(diagnosticDetails, "diagnosticDetails");
    }
}
