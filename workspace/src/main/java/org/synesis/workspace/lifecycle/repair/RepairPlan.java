package org.synesis.workspace.lifecycle.repair;

import java.util.List;
import java.util.Objects;

/**
 * Immutable persisted repair plan document stored outside control repository under
 * external workspace root administration directory.
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
 * @param entries                 immutable list of repair plan entries
 * @since 1.0
 */
public record RepairPlan(
        int schemaVersion,
        String planId,
        String projectId,
        String controlRepositoryPath,
        String externalWorkspaceRoot,
        long createdAtEpochMillis,
        String doctorReportFingerprint,
        int supportedRepairsCount,
        int unsupportedCount,
        String contentHash,
        List<RepairPlanEntry> entries
) {

    /**
     * Invariant validation.
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
}
