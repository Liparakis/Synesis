package org.synesis.workspace.application.agent.workflow;

/**
 * Result of checking a worktree for publishable changes. Shared by the completion rules that
 * produce it and the review wait that consumes it.
 */
enum Publishability {

    /**
     * Claim-covered publishable changes exist.
     */
    PUBLISHABLE,

    /**
     * The inspection succeeded and found nothing to publish.
     */
    NOT_PUBLISHABLE,

    /**
     * No worktree is known or the inspection failed; never treated as permission.
     */
    UNKNOWN
}
