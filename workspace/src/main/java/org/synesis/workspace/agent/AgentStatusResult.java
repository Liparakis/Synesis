package org.synesis.workspace.agent;

import java.util.Objects;

/**
 * Bounded result payload for readiness and workspace status responses.
 *
 * @param workspace concise workspace state identifier (e.g. "isolated")
 * @param pending   number of pending coordination items
 * @since 1.0
 */
public record AgentStatusResult(String workspace, int pending) {

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
