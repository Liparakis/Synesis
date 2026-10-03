package org.synesis.workspace.doctor;

/**
 * Overall repository and runtime health states evaluated by DoctorService.
 *
 * @since 1.0
 */
public enum DoctorStatus {
    /**
     * Component and repository state fully operational with no warnings or errors.
     */
    HEALTHY,

    /**
     * Non-critical warnings or non-blocking maintenance recommended.
     */
    DEGRADED,

    /**
     * Component errors present rendering operations unoperational until addressed.
     */
    UNHEALTHY,

    /**
     * Critical safety or event-log verification failure rendering operations unsafe.
     */
    UNSAFE
}
