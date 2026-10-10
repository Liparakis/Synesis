package org.synesis.workspace.transport.control.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Link, overlay, and relay state supplied by the long-lived runtime owner.
 *
 * @param status  aggregate network status
 * @param peers   connected physical sessions
 * @param routes  projected overlay routes
 * @param overlay overlay membership summary
 * @param relay   relay summary
 */
public record NetworkSnapshot(String status, List<NetworkPeer> peers, List<NetworkRoute> routes, OverlaySummary overlay, RelayState relay) {

    /**
     * Shared response bound for physical peers, overlay members/edges, and routes.
     */
    static final int MAX_ITEMS = 256;

    /**
     * Validates and copies the network view.
     */
    public NetworkSnapshot {
        Objects.requireNonNull(status, "status");
        peers = Objects.requireNonNull(peers, "peers").stream().limit(MAX_ITEMS).toList();
        routes = Objects.requireNonNull(routes, "routes").stream().limit(MAX_ITEMS).toList();
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(relay, "relay");
    }

    /**
     * Returns the safe unconfigured network state used before a runtime owner injects Link and
     * overlay views.
     *
     * @return empty network snapshot
     */
    public static NetworkSnapshot empty() {
        return new NetworkSnapshot("UNCONFIGURED", List.of(), List.of(), new OverlaySummary("UNCONFIGURED", "", 0, 0, ""), new RelayState("UNCONFIGURED", "", 0, false, false, "", false, 0));
    }

    /**
     * Converts the view into its JSON-compatible form. The lists are already bounded by the constructor.
     *
     * @return insertion-ordered network map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("peers", peers.stream().map(NetworkPeer::toMap).toList());
        result.put("routes", routes.stream().map(NetworkRoute::toMap).toList());
        result.put("overlay", overlay.toMap());
        result.put("relay", relay.toMap());
        return result;
    }
}
