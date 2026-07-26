package org.synesis.workspace.lifecycle.cleanup;

/**
 * Closed enumeration of managed Synesis lifecycle resource types.
 *
 * @since 1.0
 */
public enum LifecycleResourceType {
    /**
     * Dedicated worker Git worktree allocated for a provider session.
     */
    WORKER_WORKTREE,

    /**
     * Disposable Git worktree allocated for capability validation.
     */
    VALIDATION_WORKTREE,

    /**
     * Dedicated Git worktree allocated for task integration.
     */
    INTEGRATION_WORKTREE,

    /**
     * Durable provider session binding record.
     */
    PROVIDER_SESSION,

    /**
     * Durable capability request handle or projection item.
     */
    CAPABILITY_REQUEST,

    /**
     * Immutable implementation revision snapshot file.
     */
    IMPLEMENTATION_SNAPSHOT,

    /**
     * Immutable task completion snapshot file.
     */
    TASK_SNAPSHOT,

    /**
     * Provider execution or workspace trust verification evidence file.
     */
    DIAGNOSTIC_EVIDENCE,

    /**
     * Temporary patch or workspace file created during operations.
     */
    TEMPORARY_FILE,

    /**
     * Candidate background MCP server subprocess.
     */
    MCP_PROCESS_CANDIDATE,

    /**
     * Git worktree registration entry in control repository whose directory is missing.
     */
    DANGLING_GIT_WORKTREE,

    /**
     * External directory under workspace root resembling a Synesis resource without durable state.
     */
    UNLINKED_EXTERNAL_WORKSPACE
}
