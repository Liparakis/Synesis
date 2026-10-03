package org.synesis.workspace.doctor;

/**
 * Diagnostic finding severity levels.
 *
 * @since 1.0
 */
public enum DoctorSeverity {
    /**
     * Informational finding requiring no corrective action.
     */
    INFO,

    /**
     * Warning indicating degraded status or recommended maintenance.
     */
    WARNING,

    /**
     * Error indicating unoperational or invalid component state.
     */
    ERROR,

    /**
     * Critical safety violation requiring immediate operator intervention.
     */
    CRITICAL
}
