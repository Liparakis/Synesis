package org.synesis.workspace.transport.control.network;

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
}
