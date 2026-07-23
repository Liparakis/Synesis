package org.synesis.coordination;

import java.util.Objects;

/** Combines prediction resolution with the existing Git speculation gate. */
public final class PredictionIntegrationGate {
    private PredictionIntegrationGate() { }

    /** Evaluates whether a Git result may integrate a prediction.
     * @param predictionResolved whether the owner implementation is available
     * @param gitResult underlying Git gate result
     * @return integration result
     */
    public static Result evaluate(boolean predictionResolved, SpeculationWorkspace.GateResult gitResult) {
        Objects.requireNonNull(gitResult, "git result");
        if (!predictionResolved) return new Result(false, "INTEGRATION_GATE=REJECTED", "UNRESOLVED_PREDICTION");
        if (!gitResult.accepted()) return new Result(false, "INTEGRATION_GATE=REJECTED", gitResult.diagnostics());
        return new Result(true, "INTEGRATION_GATE=ACCEPTED", "PREDICTION_RESOLVED");
    }

    /** Compact integration-gate result.
     * @param accepted whether integration is allowed
     * @param status compact status
     * @param reason bounded reason
     */
    public record Result(boolean accepted, String status, String reason) {
        /** Validates bounded result text. */
        public Result { Objects.requireNonNull(status, "status"); Objects.requireNonNull(reason, "reason"); }
    }
}
