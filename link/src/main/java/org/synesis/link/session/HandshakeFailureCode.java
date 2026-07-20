package org.synesis.link.session;

/** Stable categories for authentication failures before session exposure. */
public enum HandshakeFailureCode {
    /** The remote node is not the configured expected identity. */
    IDENTITY_MISMATCH,
    /** The remote proof or its transcript binding is invalid. */
    IDENTITY_PROOF_INVALID,
    /** The peers have no common protocol version. */
    UNSUPPORTED_PROTOCOL_VERSION,
    /** A previously accepted transcript was presented again. */
    HANDSHAKE_REPLAY_REJECTED,
    /** The received handshake bytes are malformed or oversized. */
    MALFORMED_HANDSHAKE,
    /** A second control stream was opened for the authenticated connection. */
    DUPLICATE_CONTROL_STREAM,
    /** An unexpected internal failure prevented establishment. */
    INTERNAL_FAILURE
}
