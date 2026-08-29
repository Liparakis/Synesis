package org.synesis.workspace.agent;

import java.util.Objects;

/**
 * Bounded result payload for readiness and workspace status responses.
 *
 * @param workspace   concise workspace state identifier (e.g. "isolated")
 * @param pending     number of pending coordination items
 * @param worktree    assigned worktree path, when available
 * @param instruction next action guidance, when available
 * @since 1.0
 */
public record AgentStatusResult(String workspace, int pending, String worktree, String instruction) {

    /**
     * Creates a status result without worktree guidance.
     *
     * @param workspace concise workspace state identifier
     * @param pending   number of pending coordination items
     */
    public AgentStatusResult(String workspace, int pending) {
        this(workspace, pending, null, null);
    }

    /**
     * Validates status fields.
     */
    public AgentStatusResult {
        Objects.requireNonNull(workspace, "workspace");
        if (pending < 0) {
            throw new IllegalArgumentException("pending count cannot be negative");
        }
    }
}
