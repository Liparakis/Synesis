package org.synesis.link.overlay;

import java.security.GeneralSecurityException;

/**
 * Diagnosable fail-closed error from the bounded project forwarder.
 */
public final class OverlayForwardingException extends GeneralSecurityException {

  private static final long serialVersionUID = 1L;
  /**
   * Categorized reason for the forwarding failure.
   */
  private final Failure failure;

  /**
   * Creates one categorized forwarding failure.
   *
   * @param failure bounded failure category
   * @param message diagnostic without payload or key material
   */
  public OverlayForwardingException(Failure failure, String message) {
    super(message);
    this.failure = java.util.Objects.requireNonNull(failure, "failure");
  }

  /**
   * Returns the bounded failure category.
   *
   * @return category
   */
  public Failure failure() {
    return failure;
  }

  /**
   * Bounded forwarding failure categories.
   */
  public enum Failure {
    /**
     * The frame names a different project.
     */
    PROJECT_MISMATCH,
    /**
     * The local or final node is not an active member.
     */
    UNAUTHORIZED_PROJECT,
    /**
     * The frame was already accepted by this forwarding node.
     */
    DUPLICATE,
    /**
     * The frame cannot consume another hop.
     */
    HOP_LIMIT_EXCEEDED,
    /**
     * No authorized peer-transit route is currently available.
     */
    NO_ROUTE,
    /**
     * A selected next hop has no usable transport binding.
     */
    NEXT_HOP_UNAVAILABLE,
    /**
     * The destination delivery callback is not available.
     */
    DESTINATION_UNAVAILABLE
  }
}
