package org.synesis.workspace.lifecycle.lease;

/**
 * Derived liveness states for provider session leases.
 *
 * @since 1.0
 */
public enum SessionLeaseState {
    /**
     * Session lease is active and process is verified alive within heartbeat window.
     */
    ACTIVE,

    /**
     * Session heartbeat or process observation missed beyond stale threshold.
     */
    SUSPECTED_STALE,

    /**
     * Session process absence is verified beyond the recovery grace period;
     * this only permits fencing and recovery preparation.
     */
    RECOVERY_ELIGIBLE,

    /**
     * Process liveness or identity evidence is ambiguous or unverified.
     */
    AMBIGUOUS,

    /**
     * Session was closed cleanly on stdio EOF or graceful shutdown.
     */
    CLOSED_CLEANLY
}
