package org.synesis.workspace.transport.control.network;


import java.security.GeneralSecurityException;

import java.time.Clock;
import java.time.Instant;

import java.util.Comparator;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayRoute;
import org.synesis.link.overlay.OverlayRouteSelector;
import org.synesis.link.overlay.OverlayTopologyPolicy;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.PeerSession;
import static org.synesis.workspace.transport.control.network.NetworkSnapshot.MAX_ITEMS;

/**
 * Adapts authoritative Link and overlay objects into the public-safe control plane network read
 * model.
 *
 * <p>This class owns no Link session, membership, topology, route, or relay lifecycle. A
 * long-lived runtime supplies one bounded, thread-safe {@link LinkState} snapshot through the
 * source function. The adapter only applies the existing overlay route selector and copies safe
 * fields into control-plane DTOs; it never derives membership from an invitation, an address, or
 * an unsigned peer claim.
 *
 * <p>The source is evaluated once per {@link #get()} call. {@link LinkState} copies its session
 * and adjacency collections, but the contained sessions and overlay views remain live; the
 * projection does not promise an atomic observation across them.
 *
 * @since 1.0
 */
public final class LinkNetworkProjection implements Supplier<NetworkSnapshot> {

    /**
     * Source of authoritative Link and overlay state; evaluated once per {@link #get()}.
     */
    private final Supplier<LinkState> source;

    /**
     * Time source for membership and topology expiry.
     */
    private final Clock clock;

    /**
     * Policy that decides the desired overlay edges shown in the public graph.
     */
    private final OverlayTopologyPolicy topologyPolicy;

    /**
     * Creates a projection with the default topology policy and UTC clock.
     *
     * @param source authoritative Link/overlay state source
     */
    public LinkNetworkProjection(Supplier<LinkState> source) {
        this(source, Clock.systemUTC(), new OverlayTopologyPolicy());
    }

    /**
     * Creates an injectable projection for runtime and deterministic tests.
     *
     * @param source         authoritative Link/overlay state source
     * @param clock          time source used for membership and topology expiry
     * @param topologyPolicy desired-edge policy used for the public graph view
     */
    public LinkNetworkProjection(Supplier<LinkState> source, Clock clock, OverlayTopologyPolicy topologyPolicy) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.topologyPolicy = Objects.requireNonNull(topologyPolicy, "topology policy");
    }

    /**
     * Builds one bounded, safe network snapshot from the current Link state.
     *
     * @return control-plane network snapshot; malformed or unavailable optional overlay state is
     *         reported as a degraded/unconfigured view rather than being fabricated as healthy
     */
    @Override
    public NetworkSnapshot get() {
        LinkState state = Objects.requireNonNull(source.get(), "Link state source returned null");
        Instant now = clock.instant();
        List<NetworkPeer> peers = peers(state.sessions());
        RelayState relay = state.relay();
        Optional<OverlayMembershipSnapshot> membership = state.membership().map(OverlayMembershipView::current);
        if (membership.isEmpty()) {
            return new NetworkSnapshot(peers.isEmpty() ? "UNCONFIGURED" : "DEGRADED", peers, List.of(), new OverlaySummary("UNCONFIGURED", "", 0, 0, ""), relay);
        }

        OverlayMembershipSnapshot current = membership.orElseThrow();
        boolean usable = current.isUsableAt(now) && current.allows(state.localNodeId());
        List<OverlayMember> members = current.members().stream().map(member -> new OverlayMember(member.nodeId(), memberStatus(member))).limit(MAX_ITEMS).toList();
        List<OverlayEdge> directEdges = directEdges(state.localNodeId(), state.directPeerIds(), current, peers);
        List<OverlayEdge> desiredEdges = desiredEdges(current);
        // Routes from an unusable membership or a missing topology would be fabricated, so report none.
        List<NetworkRoute> routes = usable && state.topology().isPresent() ? routes(state, current, state.topology().orElseThrow(), now, relay) : List.of();
        String overlayStatus = usable ? "CURRENT" : "UNUSABLE";
        String networkStatus = usable ? "HEALTHY" : "DEGRADED";
        OverlaySummary overlay = new OverlaySummary(overlayStatus, current.authorityNodeId(), current.revision(), current.members().size(), current.expiresAt().toString(), members, directEdges, desiredEdges);
        return new NetworkSnapshot(networkStatus, peers, routes, overlay, relay);
    }

    /**
     * Projects live sessions into peers, ordered by node id and capped at {@link NetworkSnapshot#MAX_ITEMS}.
     *
     * @param sessions live sessions; {@code null} entries are skipped
     * @return public peer views
     */
    private static List<NetworkPeer> peers(List<PeerSession> sessions) {
        return sessions.stream().filter(Objects::nonNull).sorted(Comparator.comparing(PeerSession::remoteNodeId)).limit(MAX_ITEMS).map(LinkNetworkProjection::peer).toList();
    }

    /**
     * Projects one session into a peer, translating its liveness into a coarse health value.
     *
     * @param session live session
     * @return public peer view
     */
    private static NetworkPeer peer(PeerSession session) {
        LivenessState liveness = session.livenessState();
        String health = switch (liveness) {
            case LIVE -> "HEALTHY";
            case SUSPECT -> "DEGRADED";
            case CONNECTING -> "CONNECTING";
            default -> "UNAVAILABLE";
        };
        return new NetworkPeer(session.remoteNodeId(), session.sessionId().toString(), liveness.name(), session.isUsable(), session.establishedAt().toString(), true, health);
    }

    /**
     * Projects the local node's direct links into edges, ordered by peer id and capped at
     * {@link NetworkSnapshot#MAX_ITEMS}.
     *
     * @param localNodeId   local node id, the source of every edge
     * @param directPeerIds peers with a direct transport
     * @param membership    current membership, which decides authorization
     * @param peers         projected peers, which decide usability
     * @return direct edges
     */
    private static List<OverlayEdge> directEdges(String localNodeId, Set<String> directPeerIds, OverlayMembershipSnapshot membership, List<NetworkPeer> peers) {
        Set<String> usablePeers = peers.stream().filter(NetworkPeer::usable).map(NetworkPeer::nodeId).collect(Collectors.toUnmodifiableSet());
        return directPeerIds.stream().sorted().limit(MAX_ITEMS).map(nodeId -> new OverlayEdge(localNodeId, nodeId, directEdgeState(nodeId, membership, usablePeers))).toList();
    }

    /**
     * Classifies a direct edge. Authorization wins over usability so a non-member is never shown as reachable.
     *
     * @param nodeId      remote node id
     * @param membership  current membership
     * @param usablePeers ids of peers with a usable session
     * @return {@code UNAUTHORIZED}, {@code DIRECT}, or {@code UNAVAILABLE}
     */
    private static String directEdgeState(String nodeId, OverlayMembershipSnapshot membership, Set<String> usablePeers) {
        if (!membership.allows(nodeId)) {
            return "UNAUTHORIZED";
        }
        return usablePeers.contains(nodeId) ? "DIRECT" : "UNAVAILABLE";
    }

    /**
     * Projects the desired topology edges, ordered by source then target and capped at {@link NetworkSnapshot#MAX_ITEMS}.
     *
     * @param membership current membership
     * @return desired edges
     */
    private List<OverlayEdge> desiredEdges(OverlayMembershipSnapshot membership) {
        return topologyPolicy.desiredNeighbors(membership.members()).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream().sorted().map(neighbor -> new OverlayEdge(entry.getKey(), neighbor, "DESIRED"))).limit(MAX_ITEMS).toList();
    }

    /**
     * Selects a route to every other active member, ordered by destination and capped at {@link NetworkSnapshot#MAX_ITEMS}.
     *
     * <p>The relay is offered to the selector only when it is connected, authorized, and named.
     *
     * @param state      runtime state
     * @param membership current membership
     * @param topology   current topology
     * @param now        evaluation time
     * @param relay      relay summary
     * @return one route per destination
     */
    private static List<NetworkRoute> routes(LinkState state, OverlayMembershipSnapshot membership, OverlayTopologyView topology, Instant now, RelayState relay) {
        String relayNodeId = relay.connected() && relay.authorized() && !relay.relayIdentity().isBlank() ? relay.relayIdentity() : null;
        return membership.members().stream().filter(member -> member.status() == OverlayMembershipSnapshot.STATUS_ACTIVE).map(OverlayMembershipSnapshot.Member::nodeId)
                .filter(nodeId -> !nodeId.equals(state.localNodeId())).sorted().limit(MAX_ITEMS).map(destination -> {
                    OverlayRoute route = selectRoute(state, destination, membership, topology, now, relayNodeId);
                    return new NetworkRoute(route.kind().name(), route.destinationNodeId(), route.path(), route.nextHop().orElse(""), route.relayNodeId().orElse(""));
                }).toList();
    }

    /**
     * Runs the overlay route selector, treating inconsistent overlay state as unreachable.
     *
     * @param state       runtime state
     * @param destination destination node id
     * @param membership  current membership
     * @param topology    current topology
     * @param now         evaluation time
     * @param relayNodeId usable relay node id, or {@code null}
     * @return the selected route, or an unreachable route when selection fails
     */
    private static OverlayRoute selectRoute(LinkState state, String destination, OverlayMembershipSnapshot membership, OverlayTopologyView topology, Instant now, String relayNodeId) {
        try {
            return OverlayRouteSelector.select(state.localNodeId(), destination, membership, state.directPeerIds(), topology, now, relayNodeId);
        } catch (GeneralSecurityException invalidState) {
            return OverlayRoute.unreachable(destination);
        }
    }

    /**
     * Names a member's status for the public view.
     *
     * @param member overlay member
     * @return {@code ACTIVE}, or {@code REVOKED} for any other status
     */
    private static String memberStatus(OverlayMembershipSnapshot.Member member) {
        return member.status() == OverlayMembershipSnapshot.STATUS_ACTIVE ? "ACTIVE" : "REVOKED";
    }
}