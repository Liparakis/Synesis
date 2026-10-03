package org.synesis.coordination.domain.prediction;

import org.synesis.coordination.domain.speculation.SpeculationGateResult;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Combines prediction resolution with the existing Git speculation gate.
 */
public final class PredictionIntegrationGate {

    /**
     * Creates no instances; all gate evaluation is stateless.
     */
    @SuppressWarnings("unused")
    private PredictionIntegrationGate() {
    }

    /**
     * Evaluates whether a Git result may integrate a prediction.
     *
     * @param predictionResolved whether the owner implementation is available
     * @param gitResult          underlying Git gate result
     * @return integration result
     */
    public static PredictionIntegrationResult evaluate(boolean predictionResolved, SpeculationGateResult gitResult) {
        CoordinationNullException.requireNonNull(gitResult, "git result");
        if (!predictionResolved) {
            return new PredictionIntegrationResult(IntegrationGateStatus.REJECTED, IntegrationGateReason.UNRESOLVED_PREDICTION, "INTEGRATION_GATE=REJECTED", "UNRESOLVED_PREDICTION");
        }
        if (!gitResult.accepted()) {
            return new PredictionIntegrationResult(IntegrationGateStatus.REJECTED, IntegrationGateReason.SPECULATION_REJECTED, "INTEGRATION_GATE=REJECTED", gitResult.diagnostics());
        }
        return new PredictionIntegrationResult(IntegrationGateStatus.ACCEPTED, IntegrationGateReason.PREDICTION_RESOLVED, "INTEGRATION_GATE=ACCEPTED", "PREDICTION_RESOLVED");
    }
}
