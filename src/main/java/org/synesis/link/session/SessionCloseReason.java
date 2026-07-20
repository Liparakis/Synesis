package org.synesis.link.session;

/** Safe machine-readable reasons for terminal session closure. */
public enum SessionCloseReason {
    /** Local caller requested graceful shutdown. */
    LOCAL_REQUEST,
    /** The authenticated peer requested graceful shutdown. */
    REMOTE_REQUEST,
    /** A protocol violation made continued use unsafe. */
    PROTOCOL_ERROR,
    /** The underlying transport closed before graceful exchange completed. */
    TRANSPORT_CLOSED,
    /** An internal failure prevented continued operation. */
    INTERNAL_FAILURE,
    /** Application-level peer silence exceeded the expiry bound. */
    LIVENESS_EXPIRED
}
