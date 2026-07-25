package org.synesis.workspace.cleanup;

import java.util.Objects;

/**
 * Immutable fingerprint capturing evidence of resource state to detect plan staleness.
 *
 * @param normalizedIdentity    normalized path or resource identifier
 * @param durableStateVersion   durable event sequence or binding version
 * @param gitHead              Git HEAD commit SHA, or {@code null}
 * @param gitCommonDir         Git common directory path, or {@code null}
 * @param cleanStatusDigest    digest of porcelain status, or {@code null}
 * @param metadataHash         hash of metadata or file properties
 * @since 1.0
 */
public record LifecycleResourceFingerprint(
        String normalizedIdentity,
        long durableStateVersion,
        String gitHead,
        String gitCommonDir,
        String cleanStatusDigest,
        String metadataHash
) {
    /**
     * Validates required component invariants.
     */
    public LifecycleResourceFingerprint {
        Objects.requireNonNull(normalizedIdentity, "normalizedIdentity");
        Objects.requireNonNull(metadataHash, "metadataHash");
    }
}
