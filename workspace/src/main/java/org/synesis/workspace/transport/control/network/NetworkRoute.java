package org.synesis.workspace.transport.control.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("destinationNodeId", destinationNodeId);
        result.put("path", path);
        result.put("nextHop", nextHop);
        result.put("relayNodeId", relayNodeId);
        return result;
    }
}
