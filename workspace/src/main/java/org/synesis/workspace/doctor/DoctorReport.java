package org.synesis.workspace.doctor;

import java.util.List;
import java.util.Objects;

/**
 * Immutable diagnostic report document encapsulating findings, severity counts, and workflow recommendations.
 *
 * @param schemaVersion             schema version (1)
 * @param reportId                  opaque report identifier
 * @param projectId                 project identity
 * @param timestampEpochMillis      report generation timestamp
 * @param overallStatus             overall repository and runtime health status
 * @param criticalCount             count of CRITICAL findings
 * @param errorCount                count of ERROR findings
 * @param warningCount              count of WARNING findings
 * @param infoCount                 count of INFO findings
 * @param cleanupRecommended        {@code true} if cleanup workflow is recommended
 * @param reconciliationRecommended {@code true} if reconciliation workflow is recommended
 * @param repairAvailable           {@code true} if automated administrative repair is available
 * @param findings                  immutable list of diagnostic findings
 * @since 1.0
 */
public record DoctorReport(
        int schemaVersion,
        String reportId,
        String projectId,
        long timestampEpochMillis,
        DoctorStatus overallStatus,
        int criticalCount,
        int errorCount,
        int warningCount,
        int infoCount,
        boolean cleanupRecommended,
        boolean reconciliationRecommended,
        boolean repairAvailable,
        List<DoctorFinding> findings
) {

    /**
     * Invariant validation.
     */
    public DoctorReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(overallStatus, "overallStatus");
        Objects.requireNonNull(findings, "findings");
    }
}
