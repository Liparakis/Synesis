package org.synesis.link.candidate;

import java.time.Duration;
import java.util.Objects;

/**
 * Safe bounded outcome for one provider invocation.
 *
 * @param providerId stable provider identifier
 * @param category bounded outcome category
 * @param acceptedCandidates accepted candidate count
 * @param rejectedCandidates rejected or bounded-away candidate count
 * @param duration local provider duration
 */
public record CandidateProviderDiagnostic(String providerId, CandidateProviderFailureCategory category,
        int acceptedCandidates, int rejectedCandidates, Duration duration) {
    /** Validates the immutable diagnostic. */
    public CandidateProviderDiagnostic {
        Objects.requireNonNull(providerId, "provider ID");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(duration, "duration");
        if (providerId.isBlank() || acceptedCandidates < 0 || rejectedCandidates < 0 || duration.isNegative()) {
            throw new IllegalArgumentException("invalid provider diagnostic");
        }
    }
}
