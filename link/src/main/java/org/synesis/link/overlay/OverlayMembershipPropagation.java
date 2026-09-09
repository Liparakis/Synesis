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
 * Propagates signed membership snapshots over currently authenticated direct project peers with
 * bounded fan-out and hop count.
 *
 * <p>Only the declared membership authority may originate a new snapshot.
 * Receivers verify the signed snapshot through {@link OverlayMembershipView} before forwarding it,
 * so propagation does not create a second authority.
 */
public final class OverlayMembershipPropagation {

  private final String localNodeId;
  private final OverlayMembershipView membership;
  private final OverlayPeerRegistry<PeerTransport> peers;
  /**
   * Creates one bounded membership propagator.
   *
   * @param localNodeId local member node ID
   * @param membership  current verified membership snapshot
   * @param peers       authenticated direct peer transports
   */
  public OverlayMembershipPropagation(String localNodeId, OverlayMembershipSnapshot membership,
      Map<String, PeerTransport> peers) {
    this(localNodeId, new OverlayMembershipView(membership), new OverlayPeerRegistry<>(peers));
  }

  /**
   * Creates a propagator backed by refreshable membership and direct-peer views.
   *
   * @param localNodeId local member node ID
   * @param membership  current signed membership view
   * @param peers       mutable authenticated direct-peer registry
   */
  public OverlayMembershipPropagation(String localNodeId, OverlayMembershipView membership,
      OverlayPeerRegistry<PeerTransport> peers) {
    OverlayCodecSupport.requireNodeId(localNodeId);
    this.localNodeId = localNodeId;
    this.membership = Objects.requireNonNull(membership, "membership");
    this.peers = Objects.requireNonNull(peers, "peers");
  }

  /**
   * Publishes one newer snapshot from the declared membership authority.
   *
   * @param candidate newer signed membership snapshot
   * @param now       current time
   * @return completion of all attempted peer sends
   * @throws GeneralSecurityException if the local node is not the authority or the snapshot is not
   *                                  accepted
   */
  public CompletionStage<Void> publish(OverlayMembershipSnapshot candidate, Instant now)
      throws GeneralSecurityException {
    Objects.requireNonNull(candidate, "candidate membership");
    Objects.requireNonNull(now, "now");
    if (!localNodeId.equals(candidate.authorityNodeId())) {
      throw new GeneralSecurityException("membership publisher is not the declared authority");
    }
    if (membership.accept(candidate, now) != OverlayMembershipView.Acceptance.ACCEPTED) {
      return CompletableFuture.completedFuture(null);
    }
    return sendToPeers(OverlayMembershipPropagationFrame.create(candidate,
        OverlayMembershipPropagationFrame.MAX_HOPS), null);
  }

  /**
   * Accepts and, when budget remains, propagates one frame received from a directly authenticated
   * peer.
   *
   * @param immediateSenderNodeId authenticated direct sender
   * @param frame                 received membership propagation frame
   * @param now                   current time
   * @return completion of all attempted peer sends
   * @throws GeneralSecurityException if sender or membership authentication is invalid
   */
  public CompletionStage<Void> receive(String immediateSenderNodeId,
      OverlayMembershipPropagationFrame frame,
      Instant now) throws GeneralSecurityException {
    Objects.requireNonNull(immediateSenderNodeId, "immediate sender node ID");
    Objects.requireNonNull(frame, "frame");
    Objects.requireNonNull(now, "now");
    OverlayMembershipSnapshot current = membership.current();
    if (!current.allows(immediateSenderNodeId) || !peers.contains(immediateSenderNodeId)) {
      throw new GeneralSecurityException("membership sender is not a direct project peer");
    }
    if (membership.accept(frame.membership(), now) != OverlayMembershipView.Acceptance.ACCEPTED
        || frame.remainingHops() == 0) {
      return CompletableFuture.completedFuture(null);
    }
    return sendToPeers(frame.forwardOneHop(), immediateSenderNodeId);
  }

  private CompletionStage<Void> sendToPeers(OverlayMembershipPropagationFrame frame,
      String excludedPeer) {
    List<CompletableFuture<Void>> sends = new ArrayList<>();
    for (var entry : peers.snapshot().entrySet()) {
      if (entry.getKey().equals(excludedPeer) || !membership.current().allows(entry.getKey())) {
        continue;
      }
      try {
        CompletionStage<Void> send = entry.getValue().send(frame);
        if (send == null) {
          return CompletableFuture.failedFuture(
              new IllegalStateException("membership peer transport returned no completion"));
        }
        sends.add(send.toCompletableFuture());
      } catch (RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }
    return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new));
  }

  /**
   * Transport binding for one authenticated direct peer.
   */
  @FunctionalInterface
  public interface PeerTransport {

    /**
     * Sends one membership propagation frame.
     *
     * @param frame bounded signed-snapshot wrapper
     * @return completion of the physical send
     */
    CompletionStage<Void> send(OverlayMembershipPropagationFrame frame);
  }
}
