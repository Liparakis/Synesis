package org.synesis.workspace.transport.control.network;

import java.util.List;
import java.util.Objects;

/**
 * Safe overlay route summary.
 *
 * @param kind              route kind
 * @param destinationNodeId destination node
 * @param path              route node path
 * @param nextHop           next hop node
 * @param relayNodeId       relay node, or empty
 */
public record NetworkRoute(String kind, String destinationNodeId, List<String> path, String nextHop, String relayNodeId) {

    /**
     * Validates and copies the route path.
     */
    public NetworkRoute {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(destinationNodeId, "destination node ID");
        path = Objects.requireNonNull(path, "path").stream().limit(32).toList();
        nextHop = nextHop == null ? "" : nextHop;
        relayNodeId = relayNodeId == null ? "" : relayNodeId;
    }
}
