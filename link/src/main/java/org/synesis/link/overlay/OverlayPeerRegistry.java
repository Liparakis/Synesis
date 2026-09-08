package org.synesis.link.overlay;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable bounded registry of currently available direct-peer bindings.
 *
 * <p>The registry contains transport bindings only. Membership and route
 * authorization remain enforced by the consumer using the registry.
 *
 * @param <T> direct-peer binding type
 */
public final class OverlayPeerRegistry<T> {

    private final ConcurrentHashMap<String, T> bindings = new ConcurrentHashMap<>();

    /**
     * Creates an empty direct-peer registry.
     */
    public OverlayPeerRegistry() {
    }

    /**
     * Creates a registry populated with the supplied bindings.
     *
     * @param initial initial node-to-binding map
     */
    public OverlayPeerRegistry(Map<String, T> initial) {
        Objects.requireNonNull(initial, "initial bindings").forEach(this::bind);
    }

    /**
     * Binds or replaces one direct peer.
     *
     * @param nodeId authenticated peer node ID
     * @param binding transport binding
     */
    public synchronized void bind(String nodeId, T binding) {
        OverlayCodecSupport.requireNodeId(nodeId);
        Objects.requireNonNull(binding, "binding");
        if (!bindings.containsKey(nodeId) && bindings.size() >= OverlayMembershipSnapshot.MAX_MEMBERS) {
            throw new IllegalStateException("direct-peer registry bound reached");
        }
        bindings.put(nodeId, binding);
    }

    /**
     * Removes one direct peer binding if present.
     *
     * @param nodeId peer node ID
     * @return removed binding, or {@code null}
     */
    public T unbind(String nodeId) {
        OverlayCodecSupport.requireNodeId(nodeId);
        return bindings.remove(nodeId);
    }

    /**
     * Returns whether a direct peer binding exists.
     *
     * @param nodeId peer node ID
     * @return true when bound
     */
    public boolean contains(String nodeId) {
        OverlayCodecSupport.requireNodeId(nodeId);
        return bindings.containsKey(nodeId);
    }

    /**
     * Returns one direct-peer binding.
     *
     * @param nodeId peer node ID
     * @return binding, or {@code null}
     */
    public T binding(String nodeId) {
        OverlayCodecSupport.requireNodeId(nodeId);
        return bindings.get(nodeId);
    }

    /**
     * Returns a stable snapshot of the current bindings.
     *
     * @return immutable node-to-binding map
     */
    public Map<String, T> snapshot() {
        return Map.copyOf(bindings);
    }

    /**
     * Returns the currently bound peer IDs.
     *
     * @return immutable peer ID set
     */
    public Set<String> nodeIds() {
        return Set.copyOf(bindings.keySet());
    }
}
