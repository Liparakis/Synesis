package org.synesis.workspace.lifecycle.cleanup;

/**
 * Closed enumeration of conceptual lifecycle retention classifications.
 *
 * @since 1.0
 */
public enum CleanupClassification {
    /**
     * Absolute deletion prohibition. Includes control checkout, identity keys, and event log.
     */
    PROTECTED,

    /**
     * Resource is actively referenced by an active session, task, or process. Retained intact.
     */
    ACTIVE,

    /**
     * Interrupted or dirty resource containing uncommitted changes or recoverable state. Retained.
     */
    RECOVERABLE,

    /**
     * Failed or completed resource retained for operator diagnostic diffing or within retention window.
     */
    DIAGNOSTIC_RETAINED,

    /**
     * Proven finalized, clean, and expired resource safe for future automated cleanup.
     */
    CLEANUP_ELIGIBLE,

    /**
     * Disconnected or unregistered resource requiring human review or doctor reconciliation.
     */
    ORPHANED
}
