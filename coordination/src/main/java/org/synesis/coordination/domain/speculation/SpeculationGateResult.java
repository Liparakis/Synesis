package org.synesis.coordination.domain.speculation;

import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Result of the fail-closed speculation gate.
 *
 * @param outcome     semantic speculation decision
 * @param statusToken stable status token exposed to existing callers
 * @param diagnostics bounded diagnostic details for the caller
 */
public record SpeculationGateResult(SpeculationGateStatus outcome, String statusToken, String diagnostics) {

    /**
     * Preserves the existing provider-facing result constructor.
     *
     * @param accepted    whether integration was accepted
     * @param status      projected status
     * @param diagnostics diagnostics explaining the gate result
     */
    public SpeculationGateResult(boolean accepted, String status, String diagnostics) {
        this(accepted ? SpeculationGateStatus.PASSED : SpeculationGateStatus.REJECTED, status, diagnostics);
    }

    /**
     * Validates bounded gate output.
     */
    public SpeculationGateResult {
        CoordinationNullException.requireNonNull(outcome, "outcome");
        CoordinationNullException.requireNonNull(statusToken, "status");
        CoordinationNullException.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Returns whether the typed outcome permits speculation.
     *
     * @return whether the typed outcome permits speculation.
     */
    public boolean accepted() {
        return outcome == SpeculationGateStatus.PASSED;
    }

    /**
     * Returns the existing status token used by provider adapters.
     *
     * @return the existing status token used by provider adapters.
     */
    public String status() {
        return statusToken;
    }
}
