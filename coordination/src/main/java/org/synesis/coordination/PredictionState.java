package org.synesis.coordination;

/** Lifecycle states for one speculative capability prediction. */
public enum PredictionState {
    /** Prediction has been created locally but not routed. */
    PROPOSED,
    /** Prediction has been routed to the authoritative owner. */
    ROUTED,
    /** Owner supervisor has received the request. */
    RECEIVED,
    /** Owner accepted the requested contract exactly. */
    ACCEPTED_EXACT,
    /** Owner accepted an equivalent contract. */
    ACCEPTED_EQUIVALENT,
    /** Owner supplied a revised contract. */
    REVISED,
    /** Owner has started implementation. */
    IMPLEMENTING,
    /** Owner has published an implementation reference. */
    PATCH_READY,
    /** Capability is available to the requester. */
    AVAILABLE,
    /** Requester is validating against the real implementation. */
    VALIDATING,
    /** Speculation has been successfully retired. */
    RETIRED,
    /** Speculation was invalidated and must be flushed. */
    INVALIDATED,
    /** Owner rejected the request. */
    REJECTED,
    /** Prediction expired before resolution. */
    EXPIRED
}
