package org.synesis.workspace.cleanup;

import java.util.Objects;

/**
 * Stable, machine-readable cleanup evaluation and execution reason codes.
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
    DISK_BUDGET_WARNING("disk_budget_warning"),

    /**
     * Persisted cleanup plan could not be found.
     */
    CLEANUP_PLAN_NOT_FOUND("cleanup_plan_not_found"),

    /**
     * Persisted cleanup plan is malformed or invalid.
     */
    CLEANUP_PLAN_INVALID("cleanup_plan_invalid"),

    /**
     * Cleanup plan preconditions changed since preparation.
     */
    CLEANUP_PLAN_STALE("cleanup_plan_stale"),

    /**
     * Project cleanup lock is currently held by another process.
     */
    CLEANUP_EXECUTION_BUSY("cleanup_execution_busy"),

    /**
     * Entry was already completed in a previous execution run.
     */
    CLEANUP_ENTRY_ALREADY_COMPLETED("cleanup_entry_already_completed"),

    /**
     * Preconditions changed at execution time.
     */
    CLEANUP_PRECONDITION_CHANGED("cleanup_precondition_changed"),

    /**
     * Resource is active at execution time.
     */
    CLEANUP_RESOURCE_ACTIVE("cleanup_resource_active"),

    /**
     * Resource is recoverable at execution time.
     */
    CLEANUP_RESOURCE_RECOVERABLE("cleanup_resource_recoverable"),

    /**
     * Resource is dirty at execution time.
     */
    CLEANUP_RESOURCE_DIRTY("cleanup_resource_dirty"),

    /**
     * Retention window has not expired at execution time.
     */
    CLEANUP_RETENTION_NOT_EXPIRED("cleanup_retention_not_expired"),

    /**
     * Canonical path identity changed at execution time.
     */
    CLEANUP_PATH_IDENTITY_CHANGED("cleanup_path_identity_changed"),

    /**
     * Git repository identity changed at execution time.
     */
    CLEANUP_GIT_IDENTITY_CHANGED("cleanup_git_identity_changed"),

    /**
     * Git HEAD commit SHA changed at execution time.
     */
    CLEANUP_HEAD_CHANGED("cleanup_head_changed"),

    /**
     * Git worktree registration status changed at execution time.
     */
    CLEANUP_REGISTRATION_CHANGED("cleanup_registration_changed"),

    /**
     * Durable event or session state changed at execution time.
     */
    CLEANUP_DURABLE_STATE_CHANGED("cleanup_durable_state_changed"),

    /**
     * Ownership claim reference is present.
     */
    CLEANUP_OWNERSHIP_REFERENCE_PRESENT("cleanup_ownership_reference_present"),

    /**
     * Active dependency reference is present.
     */
    CLEANUP_DEPENDENCY_REFERENCE_PRESENT("cleanup_dependency_reference_present"),

    /**
     * Unexpected content encountered in target directory or file.
     */
    CLEANUP_UNEXPECTED_CONTENT("cleanup_unexpected_content"),

    /**
     * Git worktree remove command failed.
     */
    CLEANUP_GIT_REMOVAL_FAILED("cleanup_git_removal_failed"),

    /**
     * Exact file deletion failed.
     */
    CLEANUP_EXACT_DELETE_FAILED("cleanup_exact_delete_failed"),

    /**
     * Orphan resource was successfully quarantined.
     */
    CLEANUP_QUARANTINE_COMPLETED("cleanup_quarantine_completed"),

    /**
     * Resource type does not support quarantine.
     */
    CLEANUP_QUARANTINE_NOT_SUPPORTED("cleanup_quarantine_not_supported"),

    /**
     * Atomic filesystem move unavailable for quarantine.
     */
    CLEANUP_ATOMIC_MOVE_UNAVAILABLE("cleanup_atomic_move_unavailable"),

    /**
     * Postcondition verification failed after operation.
     */
    CLEANUP_POSTCONDITION_FAILED("cleanup_postcondition_failed"),

    /**
     * Resource requires human review or doctor reconciliation.
     */
    CLEANUP_REQUIRES_HUMAN_REVIEW("cleanup_requires_human_review");

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
