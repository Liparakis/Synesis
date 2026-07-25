package org.synesis.workspace.cleanup;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable persisted cleanup plan document stored outside control repository under
 * external workspace root administration directory.
 *
 * @param schemaVersion                  schema version (1)
 * @param planId                         opaque plan identifier
 * @param projectId                      project identity
 * @param controlRepositoryPath          normalized control repository path
 * @param externalWorkspaceRoot          normalized external workspace root path
 * @param createdAtEpochMillis           creation timestamp in epoch milliseconds
 * @param retentionPolicySnapshot        snapshot of retention policy settings
 * @param durableStateSequence           inventory or durable state sequence version
 * @param totalDiscoveredCount           count of total discovered resources
 * @param totalExecutableCount           count of CLEANUP_ELIGIBLE executable entries
 * @param totalEstimatedReclaimableBytes total estimated reclaimable bytes
 * @param contentHash                    SHA-256 hash of canonical plan content
 * @param entries                        immutable list of plan entries
 * @since 1.0
 */
public record PersistedCleanupPlan(
        int schemaVersion,
        String planId,
        String projectId,
        String controlRepositoryPath,
        String externalWorkspaceRoot,
        long createdAtEpochMillis,
        Map<String, String> retentionPolicySnapshot,
        long durableStateSequence,
        int totalDiscoveredCount,
        int totalExecutableCount,
        long totalEstimatedReclaimableBytes,
        String contentHash,
        List<PersistedCleanupPlanEntry> entries
) {
    /**
     * Invariant validation.
     */
    public PersistedCleanupPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(controlRepositoryPath, "controlRepositoryPath");
        Objects.requireNonNull(externalWorkspaceRoot, "externalWorkspaceRoot");
        Objects.requireNonNull(retentionPolicySnapshot, "retentionPolicySnapshot");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(entries, "entries");
    }
}
