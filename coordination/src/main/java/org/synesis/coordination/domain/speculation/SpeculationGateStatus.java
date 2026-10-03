package org.synesis.coordination.domain.speculation;

/**
 * Typed outcome of the Git speculation gate.
 */
public enum SpeculationGateStatus {
    /**
     * The Git speculation check passed.
     */
    PASSED,
    /**
     * The Git speculation check rejected the change.
     */
    REJECTED
}
