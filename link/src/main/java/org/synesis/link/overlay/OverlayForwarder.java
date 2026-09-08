package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Routes bounded opaque overlay frames through currently available physical
 * peer transports.
 *
 * <p>The forwarder never decodes the inner `SLK1` or `SLE1` record. A
 * destination callback receives the same opaque bytes that arrived over the
 * last physical hop; only the destination's E2E session can interpret them.
 */
public final class OverlayForwarder {

    /** Transport binding for one already-authenticated direct peer. */
    @FunctionalInterface
    public interface PeerTransport {

        /**
         * Sends one hop-local frame to the authenticated peer.
         *
         * @param frame bounded frame with one hop consumed
         * @return completion of the physical exchange
         */
        CompletionStage<Void> send(OverlayForwardingFrame frame);
    }

    /** Transport binding for one configured live organization relay. */
    @FunctionalInterface
    public interface RelayTransport {

        /**
         * Sends one hop-local frame to the authenticated relay.
         *
         * @param frame bounded frame with the local hop consumed
         * @return completion of live relay forwarding
         */
        CompletionStage<Void> send(OverlayForwardingFrame frame);
    }

    /** Destination callback for one opaque logical record. */
    @FunctionalInterface
    public interface DeliveryHandler {

        /**
         * Delivers one frame to the local destination endpoint.
         *
         * @param frame bounded final frame
         * @return completion of local processing
         */
        CompletionStage<Void> deliver(OverlayForwardingFrame frame);
    }

    private final String localNodeId;
    private final OverlayMembershipSnapshot membership;
    private final OverlayTopologyView topology;
    private final OverlayDuplicateGuard duplicateGuard;
    private final Map<String, PeerTransport> peers;
    private final DeliveryHandler deliveryHandler;
    private final String relayNodeId;
    private final RelayTransport relayTransport;

    /**
     * Creates a forwarder for one local project member.
     *
     * @param localNodeId local node ID
     * @param membership current verified membership snapshot
     * @param topology signed topology view
     * @param duplicateGuard bounded duplicate guard
     * @param peers authenticated direct peer transports by node ID
     * @param deliveryHandler local destination callback
     */
    public OverlayForwarder(String localNodeId, OverlayMembershipSnapshot membership,
            OverlayTopologyView topology, OverlayDuplicateGuard duplicateGuard,
            Map<String, PeerTransport> peers, DeliveryHandler deliveryHandler) {
        this(localNodeId, membership, topology, duplicateGuard, peers, deliveryHandler, null, null);
    }

    /**
     * Creates a forwarder with an optional live relay fallback.
     *
     * @param localNodeId local node ID
     * @param membership current verified membership snapshot
     * @param topology signed topology view
     * @param duplicateGuard bounded duplicate guard
     * @param peers authenticated direct peer transports by node ID
     * @param deliveryHandler local destination callback
     * @param relayNodeId configured relay node ID
     * @param relayTransport authenticated live relay transport
     */
    public OverlayForwarder(String localNodeId, OverlayMembershipSnapshot membership,
            OverlayTopologyView topology, OverlayDuplicateGuard duplicateGuard,
            Map<String, PeerTransport> peers, DeliveryHandler deliveryHandler, String relayNodeId,
            RelayTransport relayTransport) {
        OverlayCodecSupport.requireNodeId(localNodeId);
        this.localNodeId = localNodeId;
        this.membership = Objects.requireNonNull(membership, "membership");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.duplicateGuard = Objects.requireNonNull(duplicateGuard, "duplicate guard");
        this.peers = Map.copyOf(Objects.requireNonNull(peers, "peers"));
        this.peers.keySet().forEach(OverlayCodecSupport::requireNodeId);
        this.deliveryHandler = Objects.requireNonNull(deliveryHandler, "delivery handler");
        if ((relayNodeId == null) != (relayTransport == null)) {
            throw new IllegalArgumentException("relay identity and transport must be supplied together");
        }
        if (relayNodeId != null) {
            OverlayCodecSupport.requireNodeId(relayNodeId);
        }
        this.relayNodeId = relayNodeId;
        this.relayTransport = relayTransport;
    }

    /**
     * Routes or delivers one frame without exposing its inner payload.
     *
     * @param frame received or locally-created frame
     * @param now current time
     * @return completion of forwarding or destination processing
     */
    public CompletionStage<Void> forward(OverlayForwardingFrame frame, Instant now) {
        return forwardFrom(null, frame, now);
    }

    /**
     * Routes one frame received from an authenticated direct peer.
     *
     * @param immediateSenderNodeId authenticated physical sender, or null for a local origin
     * @param frame received or locally-created frame
     * @param now current time
     * @return completion of forwarding or destination processing
     */
    public CompletionStage<Void> forwardFrom(String immediateSenderNodeId, OverlayForwardingFrame frame,
            Instant now) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(now, "now");
        try {
            validateFrame(frame, now);
            if (immediateSenderNodeId != null && (!isNodeId(immediateSenderNodeId)
                    || (!peers.containsKey(immediateSenderNodeId)
                            && !immediateSenderNodeId.equals(relayNodeId)))) {
                return failed(new OverlayForwardingException(OverlayForwardingException.Failure.UNAUTHORIZED_PROJECT,
                        "physical sender is not an authorized direct peer"));
            }
            if (frame.destinationNodeId().equals(localNodeId)) {
                if (!duplicateGuard.firstSeen(frame, now)) {
                    return failed(new OverlayForwardingException(OverlayForwardingException.Failure.DUPLICATE,
                            "duplicate overlay frame"));
                }
                CompletionStage<Void> delivered = deliveryHandler.deliver(frame);
                return delivered == null ? failed(new OverlayForwardingException(
                        OverlayForwardingException.Failure.DESTINATION_UNAVAILABLE,
                        "destination delivery is unavailable")) : delivered;
            }
            if (frame.remainingHops() == 0) {
                return failed(new OverlayForwardingException(OverlayForwardingException.Failure.HOP_LIMIT_EXCEEDED,
                        "overlay hop budget exhausted"));
            }
            OverlayRoute route = OverlayRouteSelector.select(localNodeId, frame.destinationNodeId(), membership,
                    peers.keySet(), topology, now, relayNodeId);
            if (route.kind() == OverlayRoute.Kind.ORGANIZATION_RELAY) {
                if (relayTransport == null) {
                    return failed(new OverlayForwardingException(
                            OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
                            "selected relay has no transport binding"));
                }
                if (!duplicateGuard.firstSeen(frame, now)) {
                    return failed(new OverlayForwardingException(OverlayForwardingException.Failure.DUPLICATE,
                            "duplicate overlay frame"));
                }
                CompletionStage<Void> sent = relayTransport.send(frame.forwardOneHop());
                return sent == null ? failed(new OverlayForwardingException(
                        OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
                        "relay transport returned no completion")) : sent;
            }
            if (route.kind() != OverlayRoute.Kind.DIRECT && route.kind() != OverlayRoute.Kind.PEER_TRANSIT) {
                return failed(new OverlayForwardingException(OverlayForwardingException.Failure.NO_ROUTE,
                        "no peer-transit route to destination"));
            }
            String nextHop = route.nextHop().orElseThrow(() -> new OverlayForwardingException(
                    OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE, "route has no next hop"));
            PeerTransport transport = peers.get(nextHop);
            if (transport == null) {
                return failed(new OverlayForwardingException(OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
                        "selected next hop is unavailable"));
            }
            if (!duplicateGuard.firstSeen(frame, now)) {
                return failed(new OverlayForwardingException(OverlayForwardingException.Failure.DUPLICATE,
                        "duplicate overlay frame"));
            }
            CompletionStage<Void> sent = transport.send(frame.forwardOneHop());
            return sent == null ? failed(new OverlayForwardingException(
                    OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
                    "next-hop transport returned no completion")) : sent;
        } catch (OverlayForwardingException failure) {
            return failed(failure);
        } catch (GeneralSecurityException failure) {
            return failed(failure);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    private void validateFrame(OverlayForwardingFrame frame, Instant now) throws GeneralSecurityException {
        if (!membership.projectId().equals(frame.projectId())) {
            throw new OverlayForwardingException(OverlayForwardingException.Failure.PROJECT_MISMATCH,
                    "overlay frame project mismatch");
        }
        if (!membership.isUsableAt(now) || !membership.allows(localNodeId)
                || !membership.allows(frame.destinationNodeId())) {
            throw new OverlayForwardingException(OverlayForwardingException.Failure.UNAUTHORIZED_PROJECT,
                    "overlay frame member is unauthorized");
        }
    }

    private static CompletionStage<Void> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    private static boolean isNodeId(String nodeId) {
        try {
            OverlayCodecSupport.requireNodeId(nodeId);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
