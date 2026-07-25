package org.synesis.workspace.cleanup;

import java.util.List;
import java.util.Objects;

/**
 * Persisted entry record inside an immutable persisted cleanup plan.
 *
 * @param schemaVersion     schema version
 * @param resourceType      managed lifecycle resource type
 * @param resourceId        stable resource identifier
 * @param resourcePath      resource path string, or empty if virtual
 * @param classification    retention classification
 * @param eligible          {@code true} if resource meets all cleanup criteria
 * @param reasons           list of stable reason codes
 * @param estimatedBytes    estimated storage size in bytes
 * @param pathSafetyCode    path safety verification code
 * @param fingerprint       immutable state fingerprint
 * @param proposedOperation proposed cleanup operation
 * @since 1.0
 */
public record PersistedCleanupPlanEntry(
        int schemaVersion,
        LifecycleResourceType resourceType,
        String resourceId,
        String resourcePath,
        CleanupClassification classification,
        boolean eligible,
        List<String> reasons,
        long estimatedBytes,
        String pathSafetyCode,
        LifecycleResourceFingerprint fingerprint,
        String proposedOperation
) {
    /**
     * Invariant validation.
     */
    public PersistedCleanupPlanEntry {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(pathSafetyCode, "pathSafetyCode");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(proposedOperation, "proposedOperation");
    }
}
