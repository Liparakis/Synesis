package org.synesis.workspace.application.agent.session;

import java.nio.file.Path;

import java.util.Objects;

/**
 * Request to resolve the exact provider connection into an ambient workspace session.
 *
 * @param projectRoot          canonical control project root path
 * @param provider             stable provider name
 * @param connectionInstanceId unique process connection-instance ID
 * @param taskIntent           optional task intent description
 * @param refresh              whether a session refresh is explicitly requested
 * @param managedAdmission     whether trusted managed proof admission succeeded
 */
public record SessionResolutionRequest(Path projectRoot, String provider, String connectionInstanceId, AgentTaskIntent taskIntent, boolean refresh, boolean managedAdmission) {

    /**
     * Validates project and exact provider connection inputs.
     */
    public SessionResolutionRequest {
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (connectionInstanceId == null || connectionInstanceId.isBlank()) {
            throw new IllegalArgumentException("connectionInstanceId is required");
        }
    }

    /**
     * Constructs ordinary session-bound resolution parameters.
     *
     * @param projectRoot          project root
     * @param provider             provider identifier
     * @param connectionInstanceId exact connection selector
     * @param taskIntent           optional task intent
     * @param refresh              explicit refresh flag
     */
    public SessionResolutionRequest(Path projectRoot, String provider, String connectionInstanceId, AgentTaskIntent taskIntent, boolean refresh) {
        this(projectRoot, provider, connectionInstanceId, taskIntent, refresh, false);
    }
}
