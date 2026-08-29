package org.synesis.workspace.lifecycle.reconciliation;

/**
 * Narrowly defined executable reconciliation actions supported in Post-MVP Hardening Slice 3.
 *
 * @since 1.0
 */
public enum ReconciliationAction {
    /**
     * Fences a dead session without inferring abandonment or releasing claims.
     */
    MARK_SESSION_SUSPENDED,

    /**
     * Releases active semantic ownership held by an abandoned or cancelled task.
     */
    RELEASE_SUSPENDED_OWNERSHIP,

    /**
     * Prepares an immutable recovery snapshot while retaining suspended claims.
     */
    HOLD_SUSPENDED_RECOVERY,

    /**
     * Invalidates capability dependencies provided by an abandoned or cancelled task.
     */
    INVALIDATE_SUSPENDED_DEPENDENCIES,

    /**
     * Finalizes an abandoned provider session with abandonment outcome.
     */
    FINALIZE_SUSPENDED_SESSION,

    /**
     * Resumes protected fast-forward control-branch advancement for an interrupted successful integration commit.
     */
    RESUME_VERIFIED_INTEGRATION_ADVANCEMENT,

    /**
     * Finalizes missing terminal events for an already-advanced control-branch integration commit.
     */
    FINALIZE_ALREADY_ADVANCED_INTEGRATION,

    /**
     * Closes an active validation context associated with an abandoned or cancelled requester session.
     */
    CLOSE_SUSPENDED_VALIDATION_CONTEXT
}
