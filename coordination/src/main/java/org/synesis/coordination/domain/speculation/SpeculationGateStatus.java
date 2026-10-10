package org.synesis.coordination.domain.speculation;

/**
 * Typed outcome of the Git speculation gate.
 */
enum SpeculationGateStatus {
    /**
     * The Git speculation check passed.
     */
    PASSED,
    /**
     * The Git speculation check rejected the change.
     */
    REJECTED
}
