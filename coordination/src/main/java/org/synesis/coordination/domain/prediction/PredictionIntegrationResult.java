package org.synesis.coordination.domain.prediction;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Typed decision and stable output tokens from the prediction integration gate.
 *
 * @param outcome     semantic integration decision
 * @param reasonCode  typed reason for the decision
 * @param statusToken stable status token for current CLI output
 * @param reasonToken stable reason token for current CLI output
 */
public record PredictionIntegrationResult(IntegrationGateStatus outcome, IntegrationGateReason reasonCode, String statusToken, String reasonToken) {

    /**
     * Validates and initializes the gate result.
     *
     * @param outcome     semantic integration decision
     * @param reasonCode  typed reason for the decision
     * @param statusToken stable status token for current CLI output
     * @param reasonToken stable reason token for current CLI output
     */
    public PredictionIntegrationResult {
        CoordinationNullException.requireNonNull(outcome, "outcome");
        CoordinationNullException.requireNonNull(reasonCode, "reason code");
        CoordinationNullException.requireNonNull(statusToken, "status token");
        CoordinationNullException.requireNonNull(reasonToken, "reason token");
    }

    /**
     * Returns whether integration is permitted.
     *
     * @return {@code true} only when the gate accepts integration
     */
    public boolean accepted() {
        return outcome == IntegrationGateStatus.ACCEPTED;
    }

    /**
     * Returns the stable status token emitted by the CLI.
     *
     * @return stable status token
     */
    public String status() {
        return statusToken;
    }

    /**
     * Returns the stable reason token emitted by the CLI.
     *
     * @return stable reason token
     */
    public String reason() {
        return reasonToken;
    }
}
