package org.synesis.link.overlay;

import java.security.GeneralSecurityException;

/**
 * Fail-closed diagnostic from the bounded live relay.
 */
public final class OverlayRelayException extends GeneralSecurityException {

    private static final long serialVersionUID = 1L;

    /** Relay failure categories. */
    public enum Failure {
        /** The source node is not permitted to use the relay. */
        UNAUTHORIZED_NODE,
        /** The project or destination is not permitted by relay policy. */
        UNAUTHORIZED_PROJECT,
        /** The relay connection bound has been reached. */
        CONNECTION_LIMIT,
        /** The destination is not currently connected. */
        NO_ROUTE,
        /** The frame cannot consume the relay hop. */
        HOP_LIMIT_EXCEEDED,
        /** The frame was already accepted by this relay. */
        DUPLICATE,
        /** The bounded destination queue is full. */
        QUEUE_FULL,
        /** The source exceeded its configured rate budget. */
        RATE_LIMITED
    }

    /** Categorized reason for the relay failure. */
    private final Failure failure;

    /**
     * Creates a categorized relay failure.
     *
     * @param failure bounded failure category
     * @param message diagnostic without payload or key material
     */
    public OverlayRelayException(Failure failure, String message) {
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
}
