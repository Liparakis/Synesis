package org.synesis.link.session;

/**
 * Bounded public failure for a handshake that never produced a session.
 *
 * <p>The message contains only the stable category; remote bytes and key
 * material are intentionally excluded.
 */
public final class HandshakeException extends Exception {

    private static final long serialVersionUID = 1L;
    /** Stable category retained for callers. */
    private final HandshakeFailureCode code;

    /**
     * Creates a categorized handshake failure.
     *
     * @param code stable failure category
     */
    public HandshakeException(HandshakeFailureCode code) {
        super(code.name());
        this.code = code;
    }

    /**
     * Returns the stable failure category.
     *
     * @return failure code
     */
    public HandshakeFailureCode code() { return code; }
}
