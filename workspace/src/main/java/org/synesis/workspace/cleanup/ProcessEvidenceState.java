package org.synesis.workspace.cleanup;

/**
 * Conservative process evidence classification.
 *
 * @since 1.0
 */
public enum ProcessEvidenceState {
    /**
     * Process PID exists and verified to match expected executable and command line.
     */
    LIVE_VERIFIED,

    /**
     * Process PID exists but command line or identity details are unverified.
     */
    LIVE_UNVERIFIED,

    /**
     * Process PID or stdio handle is not observed in system process list.
     */
    NOT_OBSERVED,

    /**
     * Process PID exists but belongs to a reused or mismatched non-Synesis process.
     */
    PID_REUSED_OR_MISMATCHED,

    /**
     * Platform process inspection is unavailable or restricted.
     */
    PROCESS_EVIDENCE_UNAVAILABLE
}
