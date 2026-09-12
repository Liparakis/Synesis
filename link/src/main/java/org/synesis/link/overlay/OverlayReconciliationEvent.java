package org.synesis.link.overlay;

import java.util.Objects;

/**
 * Safe diagnostic emitted by overlay adjacency reconciliation.
 *
 * <p>Events contain node IDs and bounded state descriptions only. Candidate
 * addresses, signed records, private keys, and payloads are never included.
 *
 * @param type       event category
 * @param peerNodeId related peer ID, or {@code null} for a local convergence event
 * @param detail     bounded diagnostic detail
 * @since 1.0
 */
public record OverlayReconciliationEvent(Type type, String peerNodeId, String detail) {

  /**
   * Validates an immutable diagnostic event.
   */
  public OverlayReconciliationEvent {
    Objects.requireNonNull(type, "event type");
    Objects.requireNonNull(detail, "event detail");
    if (detail.length() > 256) {
      throw new IllegalArgumentException("event detail exceeds bound");
    }
    if (peerNodeId != null) {
      OverlayCodecSupport.requireNodeId(peerNodeId);
    }
  }

  /**
   * Bounded reconciliation diagnostic categories.
   */
  public enum Type {
    /** A previously usable physical session became terminal. */
    ADJACENCY_LOST,
    /** The desired policy contains an edge without a live local binding. */
    DESIRED_EDGE_MISSING,
    /** A bounded retry was scheduled after a failed connect. */
    RECONNECT_SCHEDULED,
    /** A normal authenticated connector attempt started. */
    RECONNECT_ATTEMPT,
    /** An authenticated session became the peer binding. */
    RECONNECT_AUTHENTICATED,
    /** A returned session failed identity, membership, or readiness checks. */
    RECONNECT_REJECTED,
    /** A connector failed and will be retried when policy permits. */
    RECONNECT_FAILED,
    /** An edge outside the current desired topology was retired. */
    EXTRA_EDGE_RETIRED,
    /** A route may no longer use the withdrawn physical adjacency. */
    ROUTE_WITHDRAWN,
    /** All locally owned desired edges have live authenticated bindings. */
    TOPOLOGY_CONVERGED
  }
}
