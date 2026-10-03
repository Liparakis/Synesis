package org.synesis.workspace.transport.control.network;

import java.util.Objects;

/**
 * Safe relay state supplied by the runtime owner.
 *
 * @param status            relay status
 * @param localAddress      safe relay address label
 * @param activeConnections current live relay connection count
 * @param configured        whether relay configuration exists
 * @param connected         whether this node has a live relay connection
 * @param relayIdentity     authenticated relay identity, or empty
 * @param authorized        whether this node is authorized by relay policy
 * @param activeRouteUsage  current logical routes using the relay
 */
public record RelayState(String status, String localAddress, int activeConnections, boolean configured, boolean connected, String relayIdentity, boolean authorized, int activeRouteUsage) {

    /**
     * Validates safe relay state and normalizes optional labels.
     */
    public RelayState {
        Objects.requireNonNull(status, "status");
        localAddress = localAddress == null ? "" : localAddress;
        relayIdentity = relayIdentity == null ? "" : relayIdentity;
        if (activeConnections < 0 || activeRouteUsage < 0) {
            throw new IllegalArgumentException("relay counters cannot be negative");
        }
    }

    /**
     * Returns the conservative disabled relay state.
     *
     * @return disabled state
     */
    public static RelayState disabled() {
        return new RelayState("DISABLED", "", 0, false, false, "", false, 0);
    }
}
