package org.synesis.workspace.application.project;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of creating project configuration.
 *
 * @param projectId  project identifier
 * @param nodeId     local node identifier
 * @param peerNodeId configured peer identifier
 * @param created    whether configuration was created
 */
public record ProjectCreateResult(UUID projectId, String nodeId, String peerNodeId, boolean created) {

    /**
     * Validates the project creation result.
     */
    public ProjectCreateResult {
        Objects.requireNonNull(projectId, "project ID");
        Objects.requireNonNull(nodeId, "node ID");
        Objects.requireNonNull(peerNodeId, "peer node ID");
    }
}
