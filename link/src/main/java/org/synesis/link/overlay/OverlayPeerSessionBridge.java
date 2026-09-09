package org.synesis.link.overlay;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.synesis.link.session.PeerSession;

/**
 * Adapts the logical overlay to the existing bounded authenticated application stream while
 * preserving one {@link PeerSession} per physical peer.
 */
public final class OverlayPeerSessionBridge {

  private OverlayPeerSessionBridge() {
  }

  /**
   * Creates an outbound transport binding for one direct peer session.
   *
   * @param session authenticated direct peer session
   * @return overlay transport binding
   */
  public static OverlayForwarder.PeerTransport outbound(PeerSession session) {
    Objects.requireNonNull(session, "session");
    return frame -> session.requestApplication(frame.encoded()).thenApply(response -> null);
  }

  /**
   * Creates an inbound application callback for one local forwarder.
   *
   * <p>The callback returns an empty bounded response as an acknowledgement;
   * no application plaintext is put on the physical stream.
   *
   * @param forwarder local overlay forwarder
   * @param clock     time source for expiry and replay checks
   * @return callback suitable for {@link PeerSession.ApplicationStreamHandler}
   */
  public static PeerSession.ApplicationStreamHandler inbound(OverlayForwarder forwarder,
      Clock clock) {
    Objects.requireNonNull(forwarder, "forwarder");
    Objects.requireNonNull(clock, "clock");
    return (remoteNodeId, payload) -> {
      try {
        OverlayForwardingFrame frame = OverlayForwardingFrame.decode(payload);
        return forwarder.forwardFrom(remoteNodeId, frame, Instant.now(clock))
            .thenApply(ignored -> new byte[0]);
      } catch (IOException | RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    };
  }

  /**
   * Creates an inbound callback that accepts both opaque routed frames and bounded signed topology
   * propagation frames.
   *
   * @param forwarder           local logical-message forwarder
   * @param topologyPropagation local topology propagation component
   * @param clock               time source for expiry and replay checks
   * @return callback suitable for {@link PeerSession.ApplicationStreamHandler}
   */
  public static PeerSession.ApplicationStreamHandler inbound(OverlayForwarder forwarder,
      OverlayTopologyPropagation topologyPropagation, Clock clock) {
    Objects.requireNonNull(forwarder, "forwarder");
    Objects.requireNonNull(topologyPropagation, "topology propagation");
    Objects.requireNonNull(clock, "clock");
    return (remoteNodeId, payload) -> {
      try {
        if (OverlayTopologyPropagationFrame.hasMagic(payload)) {
          OverlayTopologyPropagationFrame frame = OverlayTopologyPropagationFrame.decode(payload);
          return topologyPropagation.receive(remoteNodeId, frame, Instant.now(clock))
              .thenApply(ignored -> new byte[0]);
        }
        OverlayForwardingFrame frame = OverlayForwardingFrame.decode(payload);
        return forwarder.forwardFrom(remoteNodeId, frame, Instant.now(clock))
            .thenApply(ignored -> new byte[0]);
      } catch (IOException | GeneralSecurityException | RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    };
  }

  /**
   * Creates an inbound callback that accepts bounded membership updates, topology propagation, and
   * opaque routed frames.
   *
   * @param forwarder             local logical-message forwarder
   * @param topologyPropagation   local topology propagation component
   * @param membershipPropagation local membership propagation component
   * @param clock                 time source for expiry and replay checks
   * @return callback suitable for {@link PeerSession.ApplicationStreamHandler}
   */
  public static PeerSession.ApplicationStreamHandler inbound(OverlayForwarder forwarder,
      OverlayTopologyPropagation topologyPropagation,
      OverlayMembershipPropagation membershipPropagation,
      Clock clock) {
    Objects.requireNonNull(forwarder, "forwarder");
    Objects.requireNonNull(topologyPropagation, "topology propagation");
    Objects.requireNonNull(membershipPropagation, "membership propagation");
    Objects.requireNonNull(clock, "clock");
    return (remoteNodeId, payload) -> {
      try {
        if (OverlayMembershipPropagationFrame.hasMagic(payload)) {
          OverlayMembershipPropagationFrame frame = OverlayMembershipPropagationFrame.decode(
              payload);
          return membershipPropagation.receive(remoteNodeId, frame, Instant.now(clock))
              .thenApply(ignored -> new byte[0]);
        }
        if (OverlayTopologyPropagationFrame.hasMagic(payload)) {
          OverlayTopologyPropagationFrame frame = OverlayTopologyPropagationFrame.decode(payload);
          return topologyPropagation.receive(remoteNodeId, frame, Instant.now(clock))
              .thenApply(ignored -> new byte[0]);
        }
        OverlayForwardingFrame frame = OverlayForwardingFrame.decode(payload);
        return forwarder.forwardFrom(remoteNodeId, frame, Instant.now(clock))
            .thenApply(ignored -> new byte[0]);
      } catch (IOException | GeneralSecurityException | RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    };
  }
}
