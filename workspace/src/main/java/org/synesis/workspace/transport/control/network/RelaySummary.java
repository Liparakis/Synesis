package org.synesis.workspace.transport.control.network;

import java.util.Objects;

/**
 * Relay summary.
 *
 * @param status            relay status
 * @param localAddress      local relay address
 * @param activeConnections active relay connection count
 * @param configured        whether relay configuration exists
 * @param connected         whether the relay client is connected
 * @param relayIdentity     relay node identity
 * @param authorized        whether the local node is authorized
 * @param activeRouteUsage  active route usage count
 */
public record RelaySummary(String status, String localAddress, int activeConnections, boolean configured, boolean connected, String relayIdentity, boolean authorized, int activeRouteUsage) {

    /**
     * Creates a relay summary with conservative state defaults.
     *
     * @param status            relay status
     * @param localAddress      local relay address
     * @param activeConnections active relay connection count
     */
    public RelaySummary(String status, String localAddress, int activeConnections) {
        this(status, localAddress, activeConnections, !"UNCONFIGURED".equals(status) && !"DISABLED".equals(status), "CONNECTED".equals(status), "", false, 0);
    }

    /**
     * Validates the relay summary.
     */
    public RelaySummary {
        Objects.requireNonNull(status, "status");
        localAddress = localAddress == null ? "" : localAddress;
    }
}
