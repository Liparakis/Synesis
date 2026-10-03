package org.synesis.workspace.application.agent.workflow;

import java.nio.file.Path;

import java.util.Objects;

/**
 * Request parameters for projecting the highest-priority next action for one provider session.
 *
 * @param projectRoot          control project root path
 * @param provider             provider identifier
 * @param connectionInstanceId exact provider connection instance
 * @param completionRequested  whether this invocation explicitly requests completion projection
 */
public record NextActionRequest(Path projectRoot, String provider, String connectionInstanceId, boolean completionRequested) {

    /**
     * Validates request identity and project-root inputs.
     */
    public NextActionRequest {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
    }

    /**
     * Constructs an ordinary next-action poll.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId exact provider connection instance
     */
    public NextActionRequest(Path projectRoot, String provider, String connectionInstanceId) {
        this(projectRoot, provider, connectionInstanceId, false);
    }
}
