package org.synesis.workspace.cleanup;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Single evaluation entry in a read-only cleanup plan.
 *
 * @param resourceType          type of managed lifecycle resource
 * @param resourceId            stable resource identifier
 * @param resourcePath          path to resource, or {@code null} if virtual
 * @param classification        retention classification
 * @param eligible              {@code true} if resource meets all cleanup criteria
 * @param reasons               list of stable reason codes
 * @param estimatedBytes        estimated storage footprint in bytes
 * @param retentionState        human-readable retention state description
 * @param durableReferences     list of durable event/record references
 * @param gitRegistrationState  Git worktree registration status description
 * @param isDirty               {@code true} if worktree contains uncommitted changes
 * @param pathSafetyCode        path verification result code
 * @param processEvidenceState  conservative process liveness state
 * @param fingerprint           immutable state fingerprint
 * @param proposedAction        proposed action for future cleanup execution
 * @since 1.0
 */
public record CleanupPlanEntry(
        LifecycleResourceType resourceType,
        String resourceId,
        Path resourcePath,
        CleanupClassification classification,
        boolean eligible,
        List<String> reasons,
        long estimatedBytes,
        String retentionState,
        List<String> durableReferences,
        String gitRegistrationState,
        boolean isDirty,
        String pathSafetyCode,
        ProcessEvidenceState processEvidenceState,
        LifecycleResourceFingerprint fingerprint,
        String proposedAction
) {
    /**
     * Validates non-null record invariants.
     */
    public CleanupPlanEntry {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(retentionState, "retentionState");
        Objects.requireNonNull(durableReferences, "durableReferences");
        Objects.requireNonNull(gitRegistrationState, "gitRegistrationState");
        Objects.requireNonNull(pathSafetyCode, "pathSafetyCode");
        Objects.requireNonNull(processEvidenceState, "processEvidenceState");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(proposedAction, "proposedAction");
    }
}
