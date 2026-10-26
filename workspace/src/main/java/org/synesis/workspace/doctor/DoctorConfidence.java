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
    AMBIGUOUS,

    /**
     * Not a defect: something a person may want to act on, which changes no health status.
     */
    ADVICE;

    /**
     * Returns how a report marks the finding for a person: confirmed, suspected or advice. Evidence that is strong but
     * not exact, and evidence that is contradictory, are suspected.
     *
     * @return lowercase mark
     */
    public String mark() {
        return switch (this) {
            case CONFIRMED -> "confirmed";
            case ADVICE -> "advice";
            case HIGH_CONFIDENCE, SUSPECTED, AMBIGUOUS -> "suspected";
        };
    }
}
