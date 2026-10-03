package org.synesis.workspace.transport.control.network;

import java.util.Objects;

/**
 * Safe overlay graph edge.
 *
 * @param fromNodeId source node
 * @param toNodeId   destination node
 * @param status     edge state
 */
public record OverlayEdge(String fromNodeId, String toNodeId, String status) {

    /**
     * Validates the edge summary.
     */
    public OverlayEdge {
        Objects.requireNonNull(fromNodeId, "from node ID");
        Objects.requireNonNull(toNodeId, "to node ID");
        Objects.requireNonNull(status, "status");
    }
}
