package org.synesis.link.candidate;

import java.time.Duration;

/**
 * Bounded safe diagnostic for one candidate-pair attempt.
 *
 * @param pairId stable pair identifier
 * @param category bounded outcome category
 * @param phase bounded attempt phase name
 * @param duration local attempt duration
 */
public record ConnectionAttemptDiagnostic(String pairId, ConnectionFailureCategory category,
        String phase, Duration duration) { }
