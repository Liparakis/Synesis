package org.synesis.link.session;

/** The two roles used when binding a handshake transcript. */
public enum HandshakeRole {
    /** The endpoint that opens the connection. */
    INITIATOR,
    /** The endpoint that accepts the connection. */
    RESPONDER;

    /**
     * Returns the opposite role.
     *
     * @return opposite handshake role
     */
    public HandshakeRole opposite() {
        return this == INITIATOR ? RESPONDER : INITIATOR;
    }
}
