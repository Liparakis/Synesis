package org.synesis.workspace.cleanup;

import java.util.List;
import java.util.Objects;

/**
 * Immutable read-only cleanup plan summarizing discovered lifecycle resources,
 * classifications, and estimated reclaimable storage.
 *
 * @param projectId                 durable project identifier
 * @param timestamp                 epoch millisecond timestamp when plan was generated
 * @param discoveredCount           total number of lifecycle resources discovered
 * @param protectedCount            count of PROTECTED resources
 * @param activeCount               count of ACTIVE resources
 * @param recoverableCount          count of RECOVERABLE resources
 * @param diagnosticRetainedCount   count of DIAGNOSTIC_RETAINED resources
 * @param cleanupEligibleCount      count of CLEANUP_ELIGIBLE resources
 * @param orphanedCount             count of ORPHANED resources
 * @param estimatedReclaimableBytes total estimated reclaimable bytes from CLEANUP_ELIGIBLE resources
 * @param diskBudgetWarning         {@code true} if aggregate workspace storage exceeds warning budget
 * @param entries                   ordered list of evaluated cleanup plan entries
 * @since 1.0
 */
public record CleanupPlan(
        String projectId,
        long timestamp,
        int discoveredCount,
        int protectedCount,
        int activeCount,
        int recoverableCount,
        int diagnosticRetainedCount,
        int cleanupEligibleCount,
        int orphanedCount,
        long estimatedReclaimableBytes,
        boolean diskBudgetWarning,
        List<CleanupPlanEntry> entries
) {
    /**
     * Validates non-null record invariants.
     */
    public CleanupPlan {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(entries, "entries");
    }
}
