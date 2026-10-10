package org.synesis.workspace.transport.control.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Safe overlay membership node.
 *
 * @param nodeId member node identity
 * @param status member status
 */
public record OverlayMember(String nodeId, String status) {

    /**
     * Validates the membership node summary.
     */
    public OverlayMember {
        Objects.requireNonNull(nodeId, "node ID");
        Objects.requireNonNull(status, "status");
    }

    Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("status", status);
        return result;
    }
}
