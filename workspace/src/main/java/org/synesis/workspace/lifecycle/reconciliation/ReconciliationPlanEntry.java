package org.synesis.workspace.lifecycle.reconciliation;

import java.util.List;
import java.util.Objects;

/**
 * Persisted entry record inside an immutable persisted reconciliation plan.
 *
 * @param schemaVersion       schema version (1)
 * @param actionId            stable entry identifier
 * @param action              proposed reconciliation action
 * @param targetResourceId    target session, task, or integration identifier
 * @param executable          {@code true} if action meets all reconciliation criteria
 * @param reasons             list of machine-readable precondition/status reason codes
 * @param preconditionSummary summary of re-verified preconditions
 * @since 1.0
 */
public record ReconciliationPlanEntry(
        int schemaVersion,
        String actionId,
        ReconciliationAction action,
        String targetResourceId,
        boolean executable,
        List<String> reasons,
        String preconditionSummary
) {

    /**
     * Invariant validation.
     */
    public ReconciliationPlanEntry {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetResourceId, "targetResourceId");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(preconditionSummary, "preconditionSummary");
    }
}
