package org.synesis.link.candidate;

/** Safe final and per-attempt race outcome categories. */
public enum ConnectionFailureCategory {
    /** No compatible pair was generated. */
    NO_COMPATIBLE_CANDIDATE,
    /** Candidate pairs existed but none connected. */
    DIRECT_CONNECTIVITY_UNAVAILABLE,
    /** Attempt exceeded its bound. */
    CONNECTION_TIMEOUT,
    /** Endpoint rejected the transport. */
    CONNECTION_REFUSED,
    /** Other transport failure. */
    TRANSPORT_FAILURE,
    /** Version negotiation failed. */
    UNSUPPORTED_PROTOCOL_VERSION,
    /** Expected identity was not authenticated. */
    IDENTITY_MISMATCH,
    /** Race or candidate resource bound was exceeded. */
    RESOURCE_LIMIT_EXCEEDED,
    /** Caller cancelled the race. */
    CANCELLED,
    /** Candidate attempt reached transport but was not control-ready. */
    NOT_CONTROL_READY
}
