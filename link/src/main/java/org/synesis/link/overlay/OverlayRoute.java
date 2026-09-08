package org.synesis.link.overlay;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable logical route decision independent of any physical transport.
 */
public final class OverlayRoute {

    /** Route classes ordered by the default selector preference. */
    public enum Kind {
        /** A usable direct physical peer session exists. */
        DIRECT,
        /** One or more authorized project peers must forward the frame. */
        PEER_TRANSIT,
        /** A configured live organization relay is the fallback. */
        ORGANIZATION_RELAY,
        /** No currently usable route exists. */
        UNREACHABLE
    }

    private final Kind kind;
    private final String destinationNodeId;
    private final List<String> path;
    private final String relayNodeId;

    private OverlayRoute(Kind kind, String destinationNodeId, List<String> path, String relayNodeId) {
        this.kind = Objects.requireNonNull(kind, "route kind");
        OverlayCodecSupport.requireNodeId(destinationNodeId);
        this.destinationNodeId = destinationNodeId;
        this.path = List.copyOf(Objects.requireNonNull(path, "route path"));
        this.relayNodeId = relayNodeId;
        if (kind == Kind.DIRECT && (!this.path.equals(List.of(destinationNodeId)) || relayNodeId != null)) {
            throw new IllegalArgumentException("direct route shape is invalid");
        }
        if (kind == Kind.PEER_TRANSIT) {
            if (this.path.size() < 2 || this.path.size() > OverlayForwardingFrame.MAX_HOPS
                    || !this.path.get(this.path.size() - 1).equals(destinationNodeId)
                    || this.path.stream().anyMatch(value -> !isNodeId(value))
                    || this.path.size() != new HashSet<>(this.path).size() || relayNodeId != null) {
                throw new IllegalArgumentException("peer-transit route shape is invalid");
            }
        }
        if (kind == Kind.ORGANIZATION_RELAY
                && (relayNodeId == null || relayNodeId.isBlank() || !this.path.isEmpty())) {
            throw new IllegalArgumentException("relay route shape is invalid");
        }
        if (kind == Kind.UNREACHABLE && (!this.path.isEmpty() || relayNodeId != null)) {
            throw new IllegalArgumentException("unreachable route shape is invalid");
        }
    }

    /**
     * Creates a direct route.
     *
     * @param destinationNodeId destination node ID
     * @return direct route
     */
    public static OverlayRoute direct(String destinationNodeId) {
        return new OverlayRoute(Kind.DIRECT, destinationNodeId, List.of(destinationNodeId), null);
    }

    /**
     * Creates a peer-transit route.
     *
     * @param path next-hop through final destination, excluding the local node
     * @return peer-transit route
     */
    public static OverlayRoute peerTransit(List<String> path) {
        Objects.requireNonNull(path, "route path");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("peer-transit route requires a path");
        }
        return new OverlayRoute(Kind.PEER_TRANSIT, path.get(path.size() - 1), path, null);
    }

    /**
     * Creates a live relay route.
     *
     * @param destinationNodeId destination node ID
     * @param relayNodeId configured relay identity or endpoint label
     * @return relay route
     */
    public static OverlayRoute organizationRelay(String destinationNodeId, String relayNodeId) {
        return new OverlayRoute(Kind.ORGANIZATION_RELAY, destinationNodeId, List.of(),
                Objects.requireNonNull(relayNodeId, "relay node ID"));
    }

    /**
     * Creates an unreachable result.
     *
     * @param destinationNodeId destination node ID
     * @return unreachable route
     */
    public static OverlayRoute unreachable(String destinationNodeId) {
        return new OverlayRoute(Kind.UNREACHABLE, destinationNodeId, List.of(), null);
    }

    /**
     * Returns the route class.
     *
     * @return route class
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the final destination.
     *
     * @return destination node ID
     */
    public String destinationNodeId() {
        return destinationNodeId;
    }

    /**
     * Returns the peer path, excluding the local node.
     *
     * @return immutable peer path
     */
    public List<String> path() {
        return path;
    }

    /**
     * Returns the next physical peer or relay endpoint.
     *
     * @return next hop when one exists
     */
    public Optional<String> nextHop() {
        if (kind == Kind.PEER_TRANSIT) {
            return Optional.of(path.get(0));
        }
        if (kind == Kind.DIRECT) {
            return Optional.of(destinationNodeId);
        }
        return Optional.ofNullable(relayNodeId);
    }

    /**
     * Returns the configured relay identity or endpoint label.
     *
     * @return relay ID when this is a relay route
     */
    public Optional<String> relayNodeId() {
        return Optional.ofNullable(relayNodeId);
    }

    private static boolean isNodeId(String value) {
        try {
            OverlayCodecSupport.requireNodeId(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
