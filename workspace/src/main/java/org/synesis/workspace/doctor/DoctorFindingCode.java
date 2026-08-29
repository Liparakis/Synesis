package org.synesis.workspace.doctor;

/**
 * Closed enumeration of stable machine-readable diagnostic finding codes.
 *
 * @since 1.0
 */
public enum DoctorFindingCode {
    /**
     * Component and repository state fully operational.
     */
    HEALTHY("doctor_healthy"),

    /**
     * Synesis executable or launcher script missing from system installation directory.
     */
    MISSING_INSTALLED_LAUNCHER("installed_launcher_missing"),

    /**
     * Installed Synesis version mismatches expected runtime build version.
     */
    INSTALLED_VERSION_MISMATCH("installed_version_mismatch"),

    /**
     * Candidate stale MCP server process detected.
     */
    STALE_MCP_PROCESS_CANDIDATE("stale_mcp_process_candidate"),

    /**
     * MCP server process identity evidence is ambiguous or contradictory.
     */
    MCP_PROCESS_IDENTITY_AMBIGUOUS("process_identity_ambiguous"),

    /**
     * Target directory is not an initialized Synesis project.
     */
    PROJECT_NOT_INITIALIZED("project_not_initialized"),

    /**
     * Project identity configuration file is invalid or corrupted.
     */
    PROJECT_IDENTITY_INVALID("project_identity_invalid"),

    /**
     * Project namespace mismatches expected project ID.
     */
    PROJECT_NAMESPACE_MISMATCH("project_namespace_mismatch"),

    /**
     * Control Git checkout has uncommitted or untracked changes.
     */
    CONTROL_CHECKOUT_DIRTY("control_checkout_dirty"),

    /**
     * Control Git branch HEAD has diverged from expected tracking ref.
     */
    CONTROL_BRANCH_MOVED("control_branch_moved"),

    /**
     * Git repository root or commit SHA mismatches project record.
     */
    GIT_REPOSITORY_IDENTITY_MISMATCH("git_repository_identity_mismatch"),

    /**
     * Signed event log cryptographic signature or digest chain verification failed.
     */
    EVENT_LOG_VERIFICATION_FAILURE("event_log_verification_failure"),

    /**
     * Unsupported event log or projection schema version detected.
     */
    UNSUPPORTED_STATE_SCHEMA("unsupported_state_schema"),

    /**
     * Task snapshot reference exists in event log but snapshot file is missing.
     */
    SNAPSHOT_MISSING("snapshot_missing"),

    /**
     * Task snapshot payload SHA-256 hash mismatches recorded commit SHA.
     */
    SNAPSHOT_HASH_MISMATCH("snapshot_hash_mismatch"),

    /**
     * Durable task or coordination state is ambiguous.
     */
    DURABLE_STATE_AMBIGUOUS("durable_state_ambiguous"),

    /**
     * Active session binding exists without assigned worker worktree directory.
     */
    SESSION_WITHOUT_WORKTREE("session_without_worktree"),

    /**
     * Worker worktree directory exists without active session binding.
     */
    WORKTREE_WITHOUT_SESSION("worktree_without_session"),

    /**
     * Semantic capability ownership held by suspected stale provider session.
     */
    OWNERSHIP_HELD_BY_SUSPECTED_STALE_SESSION("ownership_held_by_stale_session"),

    /**
     * Active validation context associated with abandoned session.
     */
    ABANDONED_VALIDATION_CONTEXT("abandoned_validation_context"),

    /**
     * Integration attempt interrupted before branch advancement.
     */
    ABANDONED_INTEGRATION_ATTEMPT("abandoned_integration_attempt"),

    /**
     * Provider session lease missed heartbeat renewal.
     */
    STALE_SESSION_LEASE("stale_session_lease"),

    /**
     * Provider session liveness is ambiguous.
     */
    AMBIGUOUS_SESSION_LIVENESS("ambiguous_session_liveness"),

    /**
     * Safe lifecycle cleanup recommended for retention-expired resources.
     */
    CLEANUP_RECOMMENDED("cleanup_recommended"),

    /**
     * Disk storage budget warning for project admin directory.
     */
    DISK_BUDGET_WARNING("disk_budget_warning"),

    /**
     * Unregistered orphan resource detected under project admin directory.
     */
    ORPHANED_RESOURCE_DETECTED("orphaned_resource_detected"),

    /**
     * Persisted cleanup plan is stale relative to current resource state.
     */
    CLEANUP_PLAN_STALE("cleanup_plan_stale"),

    /**
     * Previous cleanup execution run interrupted before completion.
     */
    INCOMPLETE_CLEANUP_EXECUTION("incomplete_cleanup_execution"),

    /**
     * Stale cleanup execution lock file present.
     */
    STALE_CLEANUP_EXECUTION_LOCK("stale_cleanup_lock"),

    /**
     * Stale reconciliation execution lock file present.
     */
    STALE_RECONCILIATION_EXECUTION_LOCK("stale_reconciliation_lock"),

    /**
     * Stale repair execution lock file present.
     */
    STALE_REPAIR_LOCK("stale_repair_lock"),

    /**
     * Persisted cleanup plan file corrupted.
     */
    CORRUPT_CLEANUP_PLAN("corrupt_admin_plan"),

    /**
     * Persisted reconciliation plan file corrupted.
     */
    CORRUPT_RECONCILIATION_PLAN("corrupt_admin_plan"),

    /**
     * Execution journal file corrupted.
     */
    CORRUPT_EXECUTION_JOURNAL("corrupt_admin_journal"),

    /**
     * Previous repair execution run interrupted before completion.
     */
    INCOMPLETE_REPAIR_EXECUTION("incomplete_repair_execution"),

    /**
     * Provider configuration file missing.
     */
    PROVIDER_CONFIG_MISSING("provider_config_missing"),

    /**
     * Provider configuration file malformed or invalid JSON.
     */
    PROVIDER_CONFIG_MALFORMED("provider_config_malformed"),

    /**
     * Provider configuration file outdated or missing required parameters.
     */
    PROVIDER_CONFIG_OUTDATED("provider_config_outdated"),

    /**
     * Duplicate Synesis MCP server entry detected in provider configuration file.
     */
    PROVIDER_CONFIG_SYNSESIS_ENTRY_DUPLICATED("provider_config_duplicate_entry"),

    /**
     * Provider configuration requires a prepared migration.
     */
    PROVIDER_MIGRATION_REQUIRED("provider_migration_required"),

    /**
     * Provider configuration schema is unsupported.
     */
    PROVIDER_CONFIG_UNSUPPORTED_SCHEMA("provider_config_unsupported_schema"),

    /**
     * Provider migration metadata is incomplete or missing.
     */
    PROVIDER_MIGRATION_METADATA_MISSING("provider_migration_metadata_missing"),

    /**
     * Project metadata requires a prepared migration.
     */
    PROJECT_MIGRATION_REQUIRED("project_migration_required"),

    /**
     * Project schema is newer or otherwise unsupported.
     */
    PROJECT_SCHEMA_UNSUPPORTED("project_schema_unsupported"),

    /**
     * A migration journal or backup is incomplete.
     */
    MIGRATION_STATE_INCOMPLETE("migration_state_incomplete"),

    /**
     * Update transaction has not reached a terminal state.
     */
    UPDATE_TRANSACTION_INCOMPLETE("update_transaction_incomplete"),

    /**
     * Replay verification failed after migration.
     */
    POST_MIGRATION_REPLAY_FAILED("post_migration_replay_failed"),

    /**
     * Migration backup or journal evidence is missing or corrupt.
     */
    MIGRATION_BACKUP_MISSING("migration_backup_missing"),

    /**
     * An active session blocks project migration.
     */
    ACTIVE_SESSION_BLOCKS_MIGRATION("active_session_blocks_project_migration"),

    /**
     * Project metadata restoration is pending or incomplete.
     */
    PROJECT_RESTORATION_PENDING("project_restoration_pending"),

    /**
     * Project metadata restoration failed and needs review.
     */
    PROJECT_RESTORATION_REQUIRES_REVIEW("project_restoration_requires_human_review"),

    /**
     * A failed migration was restored successfully.
     */
    PROJECT_MIGRATION_RESTORED("project_migration_restored"),

    /**
     * Rollback restoration did not reach a verified terminal state.
     */
    ROLLBACK_RESTORATION_INCOMPLETE("rollback_restoration_incomplete"),

    /**
     * Durable command namespace contains unsupported or corrupt state.
     */
    COMMAND_NAMESPACE_UNSAFE("command_namespace_unsafe"),

    /**
     * Durable command namespace requires migration or reconciliation.
     */
    COMMAND_NAMESPACE_RECONCILIATION_REQUIRED("command_namespace_reconciliation_required"),

    /**
     * Durable command capacity or retention requires operator action.
     */
    COMMAND_CAPACITY_OR_RETENTION("command_capacity_or_retention"),

    /**
     * Durable command admission observed a lease-gap or deferred mutation.
     */
    COMMAND_ADMISSION_DEFERRED("command_admission_deferred");

    private final String value;

    DoctorFindingCode(String value) {
        this.value = value;
    }

    /**
     * Returns stable machine-readable string representation.
     *
     * @return lowercase string representation
     */
    public String value() {
        return value;
    }
}
