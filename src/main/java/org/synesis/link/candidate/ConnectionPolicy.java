package org.synesis.link.candidate;

import java.time.Duration;

/**
 * Immutable bounds for one staggered connection race.
 *
 * @param maxCandidatePairs maximum input pairs considered
 * @param maxAttempts maximum attempts launched
 * @param maxConcurrentAttempts maximum simultaneous attempts
 * @param staggerDelay delay between launches
 * @param perAttemptTimeout individual attempt deadline
 * @param globalRaceTimeout whole-race deadline
 * @param loserCleanupTimeout cleanup budget for losing attempts
 * @param maxDiagnostics maximum retained attempt diagnostics
 */
public record ConnectionPolicy(int maxCandidatePairs, int maxAttempts, int maxConcurrentAttempts,
        Duration staggerDelay, Duration perAttemptTimeout, Duration globalRaceTimeout,
        Duration loserCleanupTimeout, int maxDiagnostics) {
    /** Validates race limits and ordered timeouts. */
    public ConnectionPolicy {
        if (maxCandidatePairs < 1 || maxAttempts < 1 || maxConcurrentAttempts < 1 || maxDiagnostics < 1
                || maxConcurrentAttempts > maxAttempts || maxAttempts > maxCandidatePairs
                || staggerDelay.isNegative() || perAttemptTimeout.isZero() || perAttemptTimeout.isNegative()
                || globalRaceTimeout.compareTo(perAttemptTimeout) < 0 || loserCleanupTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid connection policy");
        }
    }
}
