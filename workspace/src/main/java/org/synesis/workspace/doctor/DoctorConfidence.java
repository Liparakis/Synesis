package org.synesis.workspace.doctor;

/**
 * Diagnostic finding confidence level.
 *
 * @since 1.0
 */
public enum DoctorConfidence {
    /**
     * Diagnostic state confirmed by exact cryptographic or filesystem evidence.
     */
    CONFIRMED,

    /**
     * Diagnostic state supported by strong process and lease evidence.
     */
    HIGH_CONFIDENCE,

    /**
     * Diagnostic state suspected due to incomplete evidence.
     */
    SUSPECTED,

    /**
     * Diagnostic state ambiguous or contradictory.
     */
    AMBIGUOUS
}
