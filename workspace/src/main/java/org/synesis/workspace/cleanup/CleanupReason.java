package org.synesis.workspace.cleanup;

import java.util.Objects;

/**
 * Stable, machine-readable cleanup evaluation reason codes.
 *
 * @since 1.0
 */
public enum CleanupReason {
    /**
     * Resource is the control project checkout or root.
     */
    CONTROL_CHECKOUT_PROTECTED("control_checkout_protected"),

    /**
     * Resource is a long-term cryptographic identity key file.
     */
    IDENTITY_FILE_PROTECTED("identity_file_protected"),

    /**
     * Resource is the immutable signed event log.
     */
    EVENT_LOG_PROTECTED("event_log_protected"),

    /**
     * Resource is bound to an active provider session.
     */
    ACTIVE_SESSION("active_session"),

    /**
     * Resource is bound to an active coordination task.
     */
    ACTIVE_TASK("active_task"),

    /**
     * Resource is bound to an active capability validation.
     */
    ACTIVE_VALIDATION("active_validation"),

    /**
     * Resource is bound to an active integration attempt.
     */
    ACTIVE_INTEGRATION("active_integration"),

    /**
     * Resource is bound to active semantic scope ownership.
     */
    ACTIVE_OWNERSHIP("active_ownership"),

    /**
     * Resource contains uncommitted changes or recoverable state.
     */
    RECOVERABLE_CHANGES("recoverable_changes"),

    /**
     * Worktree has uncommitted or untracked changes.
     */
    DIRTY_WORKTREE("dirty_worktree"),

    /**
     * Resource is still within its configured retention window.
     */
    RETENTION_WINDOW_ACTIVE("retention_window_active"),

    /**
     * Failed or diagnostic worktree retained for troubleshooting.
     */
    DIAGNOSTIC_RETENTION("diagnostic_retention"),

    /**
     * Worktree session is finalized and worktree is clean.
     */
    FINALIZED_AND_CLEAN("finalized_and_clean"),

    /**
     * Integration attempt is integrated and worktree is clean.
     */
    INTEGRATED_AND_CLEAN("integrated_and_clean"),

    /**
     * Git worktree directory exists but is not registered in control repository.
     */
    GIT_REGISTRATION_MISSING("git_registration_missing"),

    /**
     * Durable record exists but expected filesystem path is missing.
     */
    FILESYSTEM_RESOURCE_MISSING("filesystem_resource_missing"),

    /**
     * Filesystem directory exists but durable record is missing.
     */
    DURABLE_RECORD_MISSING("durable_record_missing"),

    /**
     * Durable event log or session record state is ambiguous.
     */
    DURABLE_STATE_AMBIGUOUS("durable_state_ambiguous"),

    /**
     * Path escapes external workspace root directory.
     */
    PATH_OUTSIDE_WORKSPACE_ROOT("path_outside_workspace_root"),

    /**
     * Path contains symlink or identity verification failure.
     */
    PATH_IDENTITY_UNVERIFIED("path_identity_unverified"),

    /**
     * Git common directory does not match control repository.
     */
    GIT_REPOSITORY_MISMATCH("git_repository_mismatch"),

    /**
     * Snapshot is referenced by active or historical durable state.
     */
    SNAPSHOT_STILL_REFERENCED("snapshot_still_referenced"),

    /**
     * Snapshot cleanup is not supported in current version.
     */
    SNAPSHOT_CLEANUP_NOT_SUPPORTED("snapshot_cleanup_not_supported"),

    /**
     * Process PID identity could not be verified.
     */
    PROCESS_IDENTITY_UNVERIFIED("process_identity_unverified"),

    /**
     * Process is suspected stale due to absent host process.
     */
    SUSPECTED_STALE_PROCESS("suspected_stale_process"),

    /**
     * Temporary file has expired beyond retention deadline.
     */
    TEMPORARY_FILE_EXPIRED("temporary_file_expired"),

    /**
     * Aggregate storage exceeds warning threshold budget.
     */
    DISK_BUDGET_WARNING("disk_budget_warning");

    private final String code;

    CleanupReason(String code) {
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * Returns the stable machine-readable code.
     *
     * @return code string
     */
    public String code() {
        return code;
    }
}
