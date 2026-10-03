package org.synesis.workspace.transport.control.network;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.session.PeerSession;

/**
 * Captured session and adjacency collections with live runtime-owned overlay views.
 *
 * @param localNodeId   local authenticated Link node ID
 * @param sessions      authenticated physical peer sessions
 * @param membership    current verified membership view, when configured
 * @param topology      current verified topology view, when configured
 * @param directPeerIds currently bound direct adjacency IDs
 * @param relay         safe relay-owner state
 */
public record LinkState(String localNodeId, List<PeerSession> sessions, Optional<OverlayMembershipView> membership, Optional<OverlayTopologyView> topology, Set<String> directPeerIds, RelayState relay) {

    /**
     * Validates and defensively copies the runtime view. The contained Link view objects remain
     * owned by the runtime source.
     */
    public LinkState {
        Objects.requireNonNull(localNodeId, "local node ID");
        if (localNodeId.isBlank()) {
            throw new IllegalArgumentException("local node ID must not be blank");
        }
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(topology, "topology");
        directPeerIds = Set.copyOf(Objects.requireNonNull(directPeerIds, "direct peer IDs"));
        Objects.requireNonNull(relay, "relay");
    }
}
