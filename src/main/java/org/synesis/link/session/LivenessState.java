package org.synesis.link.session;

/**
 * Observable liveness and terminal states of one authenticated session.
 *
 * @since 1.0
 */
public enum LivenessState {
    /** Authentication and control readiness are still being completed. */
    CONNECTING,
    /** Recent valid authenticated peer activity is within the healthy bound. */
    LIVE,
    /** Peer activity is late, but recovery is still possible. */
    SUSPECT,
    /** The session has irreversibly expired because peer activity was absent. */
    EXPIRED,
    /** The local caller selected graceful closure. */
    CLOSED_GRACEFULLY,
    /** The authenticated peer selected graceful closure. */
    CLOSED_BY_PEER,
    /** A protocol invariant failed and the session was closed. */
    CLOSED_BY_PROTOCOL,
    /** The transport or implementation failed before graceful closure. */
    FAILED
}
