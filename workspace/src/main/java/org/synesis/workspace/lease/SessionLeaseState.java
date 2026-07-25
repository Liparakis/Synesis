package org.synesis.workspace.lease;

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
     * Session process death is conclusively verified beyond abandonment grace period.
     */
    ABANDONMENT_ELIGIBLE,

    /**
     * Process liveness or identity evidence is ambiguous or unverified.
     */
    AMBIGUOUS,

    /**
     * Session was closed cleanly on stdio EOF or graceful shutdown.
     */
    CLOSED_CLEANLY
}
