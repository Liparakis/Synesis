package org.synesis.coordination.domain.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit consumer binding to one exact contract revision.
 *
 * @param intentId    consumer intent
 * @param participant consumer handle
 * @param contractId  contract identifier
 * @param revision    exact revision
 * @param state       dependency state
 */
public record ContractDependency(UUID intentId, String participant, UUID contractId, long revision, State state) {

    /**
     * Validates a dependency.
     *
     * @param intentId    intent identifier
     * @param participant participant handle
     * @param contractId  contract identifier
     * @param revision    revision
     * @param state       state
     */
    public ContractDependency {
        Objects.requireNonNull(intentId, "intent ID");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(contractId, "contract ID");
        Objects.requireNonNull(state, "state");
        if (revision < 1 || participant.isBlank()) {
            throw new IllegalArgumentException("invalid dependency");
        }
    }

    /**
     * Dependency lifecycle.
     */
    public enum State {
        /**
         * Consumer is bound to the revision.
         */
        ACCEPTED,
        /**
         * Consumer must revise its plan before proceeding.
         */
        REPLAN_REQUIRED
    }
}
