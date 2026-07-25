package org.synesis.workspace.cleanup;

/**
 * Execution states recorded for entries in an append-only cleanup execution journal.
 *
 * @since 1.0
 */
public enum CleanupEntryExecutionState {
    /**
     * Entry execution is pending.
     */
    PENDING,

    /**
     * Entry preconditions re-evaluated and verified safe.
     */
    PRECONDITION_VERIFIED,

    /**
     * Cleanup operation currently executing.
     */
    EXECUTING,

    /**
     * Cleanup operation completed successfully and postconditions verified.
     */
    COMPLETED,

    /**
     * Entry skipped because state or preconditions changed since plan preparation.
     */
    SKIPPED_STALE,

    /**
     * Entry skipped because resource is protected, active, dirty, or ineligible.
     */
    SKIPPED_UNSAFE,

    /**
     * Execution failed due to temporary/retryable failure.
     */
    FAILED_RETRYABLE,

    /**
     * Execution failed requiring operator or doctor reconciliation.
     */
    FAILED_REQUIRES_REVIEW
}
