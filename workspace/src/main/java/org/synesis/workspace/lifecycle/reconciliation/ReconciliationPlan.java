package org.synesis.workspace.lifecycle.reconciliation;

import java.util.List;
import java.util.Objects;

/**
 * Persisted reconciliation plan document stored outside control repository under external
 * workspace root administration directory.
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
 * @param entries               plan entries in persisted order
 * @since 1.0
 */
public record ReconciliationPlan(int schemaVersion, String planId, String projectId, String controlRepositoryPath, String externalWorkspaceRoot, long createdAtEpochMillis, int totalInspectedCount, int executableCount,
                                 String contentHash, List<ReconciliationPlanEntry> entries) {

    /**
     * Requires non-null plan/project IDs, repository/workspace paths, content hash, and entries.
     */
    public ReconciliationPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(controlRepositoryPath, "controlRepositoryPath");
        Objects.requireNonNull(externalWorkspaceRoot, "externalWorkspaceRoot");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(entries, "entries");
    }

    ReconciliationPlan withContentHash(String hash) {
        return new ReconciliationPlan(schemaVersion, planId, projectId, controlRepositoryPath, externalWorkspaceRoot, createdAtEpochMillis, totalInspectedCount, executableCount, hash, entries);
    }
}
