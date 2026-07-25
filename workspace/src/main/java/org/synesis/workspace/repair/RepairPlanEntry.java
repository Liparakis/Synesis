package org.synesis.workspace.repair;

import java.util.List;
import java.util.Objects;
import org.synesis.workspace.doctor.DoctorFindingCode;

/**
 * Single entry inside an immutable persisted repair plan.
 *
 * @param schemaVersion     schema version (1)
 * @param entryId           stable entry identifier
 * @param findingCode       associated doctor finding code
 * @param action            proposed repair action
 * @param targetPath        relative or normalized target path string
 * @param targetFingerprint SHA-256 target fingerprint hash
 * @param executable        {@code true} if repair action is supported and preconditions met
 * @param reasons           list of machine-readable status/precondition reason codes
 * @param summary           concise summary string
 * @param backupRequired    {@code true} if pre-mutation backup is required
 * @since 1.0
 */
public record RepairPlanEntry(
        int schemaVersion,
        String entryId,
        DoctorFindingCode findingCode,
        RepairAction action,
        String targetPath,
        String targetFingerprint,
        boolean executable,
        List<String> reasons,
        String summary,
        boolean backupRequired
) {
    /**
     * Invariant validation.
     */
    public RepairPlanEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(findingCode, "findingCode");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(targetFingerprint, "targetFingerprint");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(summary, "summary");
    }
}
