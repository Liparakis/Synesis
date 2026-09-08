package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Propagates signed topology advertisements over currently authenticated
 * direct project peers with bounded fan-out and hop count.
 *
 * <p>This component does not create sockets, elect an authority, or relay
 * application payloads. {@link OverlayTopologyView} remains the authenticated
 * local topology read model.
 */
public final class OverlayTopologyPropagation {

    /** Transport binding for one authenticated direct peer. */
    @FunctionalInterface
    public interface PeerTransport {

        /**
         * Sends one topology propagation frame.
         *
         * @param frame bounded signed-advertisement wrapper
         * @return completion of the physical send
         */
        CompletionStage<Void> send(OverlayTopologyPropagationFrame frame);
    }

    private final String localNodeId;
    private final OverlayMembershipView membership;
    private final OverlayTopologyView topology;
    private final OverlayPeerRegistry<PeerTransport> peers;

    /**
     * Creates one bounded topology propagator.
     *
     * @param localNodeId local member node ID
     * @param membership current verified membership snapshot
     * @param topology local topology read model
     * @param peers authenticated direct peer transports
     */
    public OverlayTopologyPropagation(String localNodeId, OverlayMembershipSnapshot membership,
            OverlayTopologyView topology, Map<String, PeerTransport> peers) {
        this(localNodeId, new OverlayMembershipView(membership), topology, new OverlayPeerRegistry<>(peers));
    }

    /**
     * Creates a propagator backed by refreshable membership and direct-peer
     * views.
     *
     * @param localNodeId local member node ID
     * @param membership current signed membership view
     * @param topology local topology read model
     * @param peers mutable authenticated direct-peer registry
     */
    public OverlayTopologyPropagation(String localNodeId, OverlayMembershipView membership,
            OverlayTopologyView topology, OverlayPeerRegistry<PeerTransport> peers) {
        OverlayCodecSupport.requireNodeId(localNodeId);
        this.localNodeId = localNodeId;
        this.membership = Objects.requireNonNull(membership, "membership");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.peers = Objects.requireNonNull(peers, "peers");
    }

    /**
     * Publishes a local signed advertisement and forwards it to direct peers.
     *
     * @param advertisement local signed advertisement
     * @param now current time
     * @return completion of all attempted peer sends
     * @throws GeneralSecurityException if the advertisement is not current or
     *                                  does not originate locally
     */
    public CompletionStage<Void> publish(OverlayTopologyAdvertisement advertisement, Instant now)
            throws GeneralSecurityException {
        Objects.requireNonNull(advertisement, "advertisement");
        Objects.requireNonNull(now, "now");
        OverlayMembershipSnapshot currentMembership = membership.current();
        if (!localNodeId.equals(advertisement.originNodeId())) {
            throw new GeneralSecurityException("topology publisher is not the signed origin");
        }
        if (!currentMembership.allows(localNodeId)
                || topology.accept(advertisement, currentMembership, now)
                        != OverlayTopologyView.Acceptance.ACCEPTED) {
            return CompletableFuture.completedFuture(null);
        }
        return sendToPeers(OverlayTopologyPropagationFrame.create(advertisement,
                OverlayTopologyPropagationFrame.MAX_HOPS), null);
    }

    /**
     * Accepts and, when budget remains, propagates one frame received from a
     * directly authenticated peer.
     *
     * @param immediateSenderNodeId authenticated direct sender
     * @param frame received propagation frame
     * @param now current time
     * @return completion of all attempted peer sends
     * @throws GeneralSecurityException if sender or topology authentication is
     *                                  invalid
     */
    public CompletionStage<Void> receive(String immediateSenderNodeId, OverlayTopologyPropagationFrame frame,
            Instant now) throws GeneralSecurityException {
        Objects.requireNonNull(immediateSenderNodeId, "immediate sender node ID");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(now, "now");
        OverlayMembershipSnapshot currentMembership = membership.current();
        if (!currentMembership.allows(immediateSenderNodeId) || !peers.contains(immediateSenderNodeId)) {
            throw new GeneralSecurityException("topology sender is not a direct project peer");
        }
        if (topology.accept(frame.advertisement(), currentMembership, now) != OverlayTopologyView.Acceptance.ACCEPTED
                || frame.remainingHops() == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return sendToPeers(frame.forwardOneHop(), immediateSenderNodeId);
    }

    private CompletionStage<Void> sendToPeers(OverlayTopologyPropagationFrame frame, String excludedPeer) {
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (var entry : peers.snapshot().entrySet()) {
            if (entry.getKey().equals(excludedPeer) || !membership.current().allows(entry.getKey())) {
                continue;
            }
            try {
                CompletionStage<Void> send = entry.getValue().send(frame);
                if (send == null) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("topology peer transport returned no completion"));
                }
                sends.add(send.toCompletableFuture());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new));
    }
}
