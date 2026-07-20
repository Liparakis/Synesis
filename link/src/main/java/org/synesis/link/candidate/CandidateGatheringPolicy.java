package org.synesis.link.candidate;

import java.time.Duration;

/**
 * Immutable bounds and privacy policy for one candidate gathering operation.
 *
 * @param maxProviders maximum concurrently considered providers
 * @param maxCandidatesPerProvider maximum accepted values from one provider
 * @param maxTotalCandidates maximum retained candidates
 * @param providerTimeout per-provider deadline
 * @param totalTimeout global gathering deadline
 * @param allowLoopback whether loopback addresses are usable
 * @param allowPrivate whether private IPv4 addresses are usable
 * @param allowGlobalIpv6 whether global IPv6 addresses are usable
 * @param allowMappedIpv4 whether mapped IPv4 candidates are usable
 * @param allowServerReflexive whether server-reflexive candidates are usable
 */
public record CandidateGatheringPolicy(
        int maxProviders,
        int maxCandidatesPerProvider,
        int maxTotalCandidates,
        Duration providerTimeout,
        Duration totalTimeout,
        boolean allowLoopback,
        boolean allowPrivate,
        boolean allowGlobalIpv6,
        boolean allowMappedIpv4,
        boolean allowServerReflexive) {

    /**
     * Returns conservative direct-connectivity defaults with no reflexive service.
     *
     * @return immutable default policy
     */
    public static CandidateGatheringPolicy defaults() {
        return new CandidateGatheringPolicy(8, 16, 32, Duration.ofSeconds(2), Duration.ofSeconds(5),
                false, true, true, true, false);
    }

    /** Validates positive bounds and ordered deadlines. */
    public CandidateGatheringPolicy {
        if (maxProviders < 1 || maxCandidatesPerProvider < 1 || maxTotalCandidates < 1) {
            throw new IllegalArgumentException("candidate bounds must be positive");
        }
        if (maxCandidatesPerProvider > maxTotalCandidates) {
            throw new IllegalArgumentException("per-provider bound exceeds total bound");
        }
        if (providerTimeout.isZero() || providerTimeout.isNegative()
                || totalTimeout.isZero() || totalTimeout.isNegative()
                || totalTimeout.compareTo(providerTimeout) < 0) {
            throw new IllegalArgumentException("candidate timeouts are invalid");
        }
    }
}
