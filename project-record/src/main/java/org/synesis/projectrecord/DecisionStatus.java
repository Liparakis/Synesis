package org.synesis.projectrecord;

/**
 * The closed v1 lifecycle states for a signed decision.
 */
public enum DecisionStatus {
    /**
     * The decision is still being proposed.
     */
    PROPOSED,
    /**
     * The decision has been accepted by its signer.
     */
    ACCEPTED,
    /**
     * The decision has been rejected by its signer.
     */
    REJECTED,
    /**
     * The decision has been replaced by a later decision.
     */
    SUPERSEDED
}
