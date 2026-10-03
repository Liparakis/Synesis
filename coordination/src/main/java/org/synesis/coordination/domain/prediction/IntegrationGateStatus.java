package org.synesis.coordination.domain.prediction;

/**
 * Typed outcome returned by the prediction integration gate.
 */
public enum IntegrationGateStatus {
    /**
     * Integration is allowed.
     */
    ACCEPTED,
    /**
     * Integration is rejected.
     */
    REJECTED
}
