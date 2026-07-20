package org.synesis.cli.diagnostics;

import java.util.Objects;

/**
 * Immutable local-only readiness result for {@code synesis doctor}.
 *
 * @param javaReady Java runtime meets the supported minimum
 * @param profileReady profile directory is accessible without creation
 * @param identityReady existing identity is valid, or identity is absent
 * @param candidatesReady at least one local candidate was enumerated
 * @param quicReady Link QUIC classes initialized successfully
 * @param identityDetail redacted identity detail
 * @param candidateDetail bounded candidate detail
 * @param quicDetail redacted QUIC detail
 * @since 1.0
 */
public record ReadinessReport(boolean javaReady, boolean profileReady, boolean identityReady,
        boolean candidatesReady, boolean quicReady, String identityDetail, String candidateDetail,
        String quicDetail) {
    /** Validates safe report strings. */
    public ReadinessReport {
        Objects.requireNonNull(identityDetail, "identityDetail");
        Objects.requireNonNull(candidateDetail, "candidateDetail");
        Objects.requireNonNull(quicDetail, "quicDetail");
    }

    /**
     * Returns whether all required local checks passed.
     *
     * @return true when the report is ready
     */
    public boolean ready() { return javaReady && profileReady && identityReady && candidatesReady && quicReady; }
}
