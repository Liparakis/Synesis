package org.synesis.link.transport;

/**
 * Operational facts emitted by the Link-owned terminal onboarding workflow.
 *
 * @since 1.0
 */
public enum OnboardingEventType {
    /** The local identity was created. */
    IDENTITY_CREATED,
    /** The local identity was loaded. */
    IDENTITY_LOADED,
    /** A session identity and capability were allocated. */
    SESSION_CREATED,
    /** The host listener is accepting connections. */
    LISTENER_READY,
    /** Candidate gathering completed; the value is the bounded count. */
    CANDIDATES_GATHERED,
    /** A signed candidate descriptor was created. */
    DESCRIPTOR_CREATED,
    /** A signed invitation was created. */
    INVITE_CREATED,
    /** An invitation was parsed. */
    INVITE_PARSED,
    /** An invitation passed version, signature, and expiry checks. */
    INVITE_VERIFIED,
    /** The invitation's host identity was pinned. */
    HOST_IDENTITY_PINNED,
    /** The local candidate descriptor is ready. */
    LOCAL_DESCRIPTOR_CREATED,
    /** The selected candidate pair identifier. */
    PATH_SELECTED,
    /** The exact invitation share link. */
    SHARE_LINK,
    /** An authenticated peer session was connected. */
    PEER_CONNECTED,
    /** The authenticated remote node identifier. */
    PEER_IDENTITY_VERIFIED,
    /** Whether the control path is ready. */
    CONTROL_READY,
    /** The current liveness state. */
    LIVENESS,
    /** The bounded demo work result status. */
    WORK_RESULT,
    /** The local node identifier. */
    NODE_ID,
    /** The session closed after bounded cleanup. */
    SESSION_CLOSED
}
