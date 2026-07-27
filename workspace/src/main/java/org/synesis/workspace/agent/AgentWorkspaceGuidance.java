package org.synesis.workspace.agent;

import java.util.Objects;

/**
 * Actionable workspace-location guidance returned when a target exists in the
 * control checkout but not in the worker's assigned worktree.
 *
 * @param controlCheckout control checkout path detected by Synesis
 * @param assignedWorktree worktree owned by this MCP connection
 * @param instruction next action for the agent
 * @since 1.0
 */
public record AgentWorkspaceGuidance(String controlCheckout, String assignedWorktree, String instruction) {
    /** Validates guidance fields. */
    public AgentWorkspaceGuidance {
        Objects.requireNonNull(controlCheckout, "controlCheckout");
        Objects.requireNonNull(assignedWorktree, "assignedWorktree");
        Objects.requireNonNull(instruction, "instruction");
    }
}
