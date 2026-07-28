package org.synesis.workspace.lifecycle.reconciliation;

/**
 * Narrowly defined executable reconciliation actions supported in Post-MVP Hardening Slice 3.
 *
 * @since 1.0
 */
public enum ReconciliationAction {
    /**
     * Appends a durable SESSION_ABANDONED event for a dead session beyond grace period.
     */
    MARK_SESSION_ABANDONED,

    /**
     * Releases active semantic ownership held by an abandoned or cancelled task.
     */
    RELEASE_ABANDONED_OWNERSHIP,

    /** Releases active collaboration claims held by an abandoned session. */
    RELEASE_ABANDONED_CLAIMS,

    /**
     * Invalidates capability dependencies provided by an abandoned or cancelled task.
     */
    INVALIDATE_ABANDONED_DEPENDENCIES,

    /**
     * Finalizes an abandoned provider session with abandonment outcome.
     */
    FINALIZE_ABANDONED_SESSION,

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
    CLOSE_ABANDONED_VALIDATION_CONTEXT
}
