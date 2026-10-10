package org.synesis.workspace.lifecycle.repair;

import java.util.List;
import java.util.Objects;

/**
 * Persisted repair plan document stored outside control repository under external
 * workspace root administration directory.
 *
 * @param schemaVersion           schema version (1)
 * @param planId                  opaque plan identifier
 * @param projectId               project identity
 * @param controlRepositoryPath   normalized control repository path
 * @param externalWorkspaceRoot   normalized external workspace root path
 * @param createdAtEpochMillis    creation timestamp
 * @param doctorReportFingerprint SHA-256 fingerprint of doctor report
 * @param supportedRepairsCount   count of executable supported repair actions
 * @param unsupportedCount        count of unsupported findings
 * @param contentHash             SHA-256 canonical plan content hash
 * @param entries                 repair plan entries in persisted order
 * @since 1.0
 */
public record RepairPlan(int schemaVersion, String planId, String projectId, String controlRepositoryPath, String externalWorkspaceRoot, long createdAtEpochMillis, String doctorReportFingerprint,
                         int supportedRepairsCount, int unsupportedCount, String contentHash, List<RepairPlanEntry> entries) {

    /**
     * Requires non-null plan/project IDs, repository/workspace paths, doctor fingerprint, content
     * hash, and entries.
     */
    public RepairPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(controlRepositoryPath, "controlRepositoryPath");
        Objects.requireNonNull(externalWorkspaceRoot, "externalWorkspaceRoot");
        Objects.requireNonNull(doctorReportFingerprint, "doctorReportFingerprint");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(entries, "entries");
    }

    RepairPlan withContentHash(String hash) {
        return new RepairPlan(schemaVersion, planId, projectId, controlRepositoryPath, externalWorkspaceRoot, createdAtEpochMillis, doctorReportFingerprint, supportedRepairsCount, unsupportedCount, hash, entries);
    }
}
