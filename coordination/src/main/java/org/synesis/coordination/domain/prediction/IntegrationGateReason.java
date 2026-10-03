package org.synesis.coordination.domain.prediction;

/**
 * Machine-readable reasons returned by the prediction integration gate.
 */
public enum IntegrationGateReason {
    /**
     * The prediction remains unresolved.
     */
    UNRESOLVED_PREDICTION,
    /**
     * The Git speculation gate rejected the change.
     */
    SPECULATION_REJECTED,
    /**
     * The prediction is resolved and integration may proceed.
     */
    PREDICTION_RESOLVED,
    /**
     * The rejection reason has no more specific category.
     */
    OTHER
}
