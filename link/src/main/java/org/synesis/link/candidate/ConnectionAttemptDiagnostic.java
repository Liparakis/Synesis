package org.synesis.link.candidate;

/**
 * Bounded safe diagnostic for one candidate-pair attempt.
 *
 * @param pairId   stable pair identifier
 * @param category bounded outcome category
 */
public record ConnectionAttemptDiagnostic(String pairId, ConnectionFailureCategory category) {

}
