package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.synesis.link.identity.NodeIdentity;

/**
 * Routes bounded opaque overlay frames through currently available physical peer transports.
 *
 * <p>The forwarder never decodes the inner `SLK1` or `SLE1` record. A
 * destination callback receives the same opaque bytes that arrived over the last physical hop; only
 * the destination's E2E session can interpret them.
 */
public final class OverlayForwarder {

  private final String localNodeId;
  private final OverlayMembershipView membership;
  private final OverlayTopologyView topology;
  private final OverlayDuplicateGuard duplicateGuard;
  private final OverlayPeerRegistry<PeerTransport> peers;
  private final DeliveryHandler deliveryHandler;
  private final String relayNodeId;
  private final RelayTransport relayTransport;
  /**
   * Creates a forwarder for one local project member.
   *
   * @param localNodeId     local node ID
   * @param membership      current verified membership snapshot
   * @param topology        signed topology view
   * @param duplicateGuard  bounded duplicate guard
   * @param peers           authenticated direct peer transports by node ID
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
   * @param localNodeId     local node ID
   * @param membership      current verified membership snapshot
   * @param topology        signed topology view
   * @param duplicateGuard  bounded duplicate guard
   * @param peers           authenticated direct peer transports by node ID
   * @param deliveryHandler local destination callback
   * @param relayNodeId     configured relay node ID
   * @param relayTransport  authenticated live relay transport
   */
  public OverlayForwarder(String localNodeId, OverlayMembershipSnapshot membership,
      OverlayTopologyView topology, OverlayDuplicateGuard duplicateGuard,
      Map<String, PeerTransport> peers, DeliveryHandler deliveryHandler, String relayNodeId,
      RelayTransport relayTransport) {
    this(localNodeId, new OverlayMembershipView(membership), topology, duplicateGuard,
        new OverlayPeerRegistry<>(peers), deliveryHandler, relayNodeId, relayTransport);
  }
  /**
   * Creates a forwarder backed by refreshable membership and direct-peer views.
   *
   * @param localNodeId     local node ID
   * @param membership      current signed membership view
   * @param topology        signed topology view
   * @param duplicateGuard  bounded duplicate guard
   * @param peers           mutable direct-peer binding registry
   * @param deliveryHandler local destination callback
   */
  public OverlayForwarder(String localNodeId, OverlayMembershipView membership,
      OverlayTopologyView topology, OverlayDuplicateGuard duplicateGuard,
      OverlayPeerRegistry<PeerTransport> peers, DeliveryHandler deliveryHandler) {
    this(localNodeId, membership, topology, duplicateGuard, peers, deliveryHandler, null, null);
  }

  /**
   * Creates a refreshable forwarder with an optional live relay fallback.
   *
   * @param localNodeId     local node ID
   * @param membership      current signed membership view
   * @param topology        signed topology view
   * @param duplicateGuard  bounded duplicate guard
   * @param peers           mutable direct-peer binding registry
   * @param deliveryHandler local destination callback
   * @param relayNodeId     configured relay node ID
   * @param relayTransport  authenticated live relay transport
   */
  public OverlayForwarder(String localNodeId, OverlayMembershipView membership,
      OverlayTopologyView topology, OverlayDuplicateGuard duplicateGuard,
      OverlayPeerRegistry<PeerTransport> peers, DeliveryHandler deliveryHandler, String relayNodeId,
      RelayTransport relayTransport) {
    OverlayCodecSupport.requireNodeId(localNodeId);
    this.localNodeId = localNodeId;
    this.membership = Objects.requireNonNull(membership, "membership");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.duplicateGuard = Objects.requireNonNull(duplicateGuard, "duplicate guard");
    this.peers = Objects.requireNonNull(peers, "peers");
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

  private static CompletionStage<Void> failed(Throwable failure) {
    return CompletableFuture.failedFuture(failure);
  }

  /**
   * Routes or delivers one frame without exposing its inner payload.
   *
   * @param frame received or locally-created frame
   * @param now   current time
   * @return completion of forwarding or destination processing
   */
  public CompletionStage<Void> forward(OverlayForwardingFrame frame, Instant now) {
    return forwardFrom(null, frame, now);
  }

  /**
   * Routes one frame received from an authenticated direct peer.
   *
   * @param immediateSenderNodeId authenticated physical sender, or null for a local origin
   * @param frame                 received or locally-created frame
   * @param now                   current time
   * @return completion of forwarding or destination processing
   */
  public CompletionStage<Void> forwardFrom(String immediateSenderNodeId,
      OverlayForwardingFrame frame,
      Instant now) {
    Objects.requireNonNull(frame, "frame");
    Objects.requireNonNull(now, "now");
    try {
      validateFrame(frame, now);
      OverlayMembershipSnapshot currentMembership = membership.current();
      boolean authorizedImmediateSender =
          NodeIdentity.isNodeId(immediateSenderNodeId)
              && (immediateSenderNodeId.equals(relayNodeId)
              || (currentMembership.allows(immediateSenderNodeId)
              && peers.contains(immediateSenderNodeId)));
      if (immediateSenderNodeId != null && !authorizedImmediateSender) {
        return failed(
            new OverlayForwardingException(OverlayForwardingException.Failure.UNAUTHORIZED_PROJECT,
                "physical sender is not an authorized direct peer"));
      }
      if (frame.destinationNodeId().equals(localNodeId)) {
        return sendOnce(frame, now, deliveryHandler::deliver,
            OverlayForwardingException.Failure.DESTINATION_UNAVAILABLE,
            "destination delivery is unavailable", frame);
      }
      if (frame.remainingHops() == 0) {
        return failed(
            new OverlayForwardingException(OverlayForwardingException.Failure.HOP_LIMIT_EXCEEDED,
                "overlay hop budget exhausted"));
      }
      OverlayRoute route = OverlayRouteSelector.select(localNodeId, frame.destinationNodeId(),
          currentMembership,
          peers.nodeIds(), topology, now, relayNodeId);
      if (route.kind() == OverlayRoute.Kind.ORGANIZATION_RELAY) {
        if (relayTransport == null) {
          return failed(new OverlayForwardingException(
              OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
              "selected relay has no transport binding"));
        }
        return sendOnce(frame, now, relayTransport::send,
            OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
            "relay transport returned no completion", frame.forwardOneHop());
      }
      if (route.kind() != OverlayRoute.Kind.DIRECT
          && route.kind() != OverlayRoute.Kind.PEER_TRANSIT) {
        return failed(new OverlayForwardingException(OverlayForwardingException.Failure.NO_ROUTE,
            "no peer-transit route to destination"));
      }
      String nextHop = route.nextHop().orElseThrow(() -> new OverlayForwardingException(
          OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE, "route has no next hop"));
      PeerTransport transport = peers.binding(nextHop);
      if (transport == null) {
        return failed(
            new OverlayForwardingException(OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
                "selected next hop is unavailable"));
      }
      return sendOnce(frame, now, transport::send,
          OverlayForwardingException.Failure.NEXT_HOP_UNAVAILABLE,
          "next-hop transport returned no completion", frame.forwardOneHop());
    } catch (OverlayForwardingException failure) {
      return failed(failure);
    } catch (GeneralSecurityException failure) {
      return failed(failure);
    } catch (RuntimeException failure) {
      return failed(failure);
    }
  }

  /**
   * Records the frame in the duplicate guard before sending, so a retry after a failed send is
   * rejected as a duplicate.
   */
  private CompletionStage<Void> sendOnce(OverlayForwardingFrame frame, Instant now,
      Function<OverlayForwardingFrame, CompletionStage<Void>> send,
      OverlayForwardingException.Failure missingCompletion, String missingMessage,
      OverlayForwardingFrame outgoing) {
    if (!duplicateGuard.firstSeen(frame, now)) {
      return failed(new OverlayForwardingException(OverlayForwardingException.Failure.DUPLICATE,
          "duplicate overlay frame"));
    }
    CompletionStage<Void> completion = send.apply(outgoing);
    return completion == null
        ? failed(new OverlayForwardingException(missingCompletion, missingMessage)) : completion;
  }

  private void validateFrame(OverlayForwardingFrame frame, Instant now)
      throws GeneralSecurityException {
    OverlayMembershipSnapshot currentMembership = membership.current();
    if (!currentMembership.projectId().equals(frame.projectId())) {
      throw new OverlayForwardingException(OverlayForwardingException.Failure.PROJECT_MISMATCH,
          "overlay frame project mismatch");
    }
    if (!currentMembership.isUsableAt(now) || !currentMembership.allows(localNodeId)
        || !currentMembership.allows(frame.destinationNodeId())) {
      throw new OverlayForwardingException(OverlayForwardingException.Failure.UNAUTHORIZED_PROJECT,
          "overlay frame member is unauthorized");
    }
  }

  /**
   * Transport binding for one already-authenticated direct peer.
   */
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

  /**
   * Transport binding for one configured live organization relay.
   */
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

  /**
   * Destination callback for one opaque logical record.
   */
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
}
