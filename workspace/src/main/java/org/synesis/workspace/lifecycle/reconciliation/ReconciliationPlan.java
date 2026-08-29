package org.synesis.workspace.lifecycle.reconciliation;

import java.util.List;
import java.util.Objects;

/**
 * Immutable persisted reconciliation plan document stored outside control repository under
 * external workspace root administration directory.
 *
 * @param schemaVersion         schema version (1)
 * @param planId                opaque plan identifier
 * @param projectId             project identity
 * @param controlRepositoryPath normalized control repository path
 * @param externalWorkspaceRoot normalized external workspace root path
 * @param createdAtEpochMillis  creation timestamp
 * @param totalInspectedCount   count of total inspected sessions/integrations
 * @param executableCount       count of executable reconciliation actions
 * @param contentHash           SHA-256 hash of canonical plan content
 * @param entries               immutable list of plan entries
 * @since 1.0
 */
public record ReconciliationPlan(
        int schemaVersion,
        String planId,
        String projectId,
        String controlRepositoryPath,
        String externalWorkspaceRoot,
        long createdAtEpochMillis,
        int totalInspectedCount,
        int executableCount,
        String contentHash,
        List<ReconciliationPlanEntry> entries
) {

    /**
     * Invariant validation.
     */
    public ReconciliationPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(controlRepositoryPath, "controlRepositoryPath");
        Objects.requireNonNull(externalWorkspaceRoot, "externalWorkspaceRoot");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(entries, "entries");
    }
}
