package org.synesis.coordination.domain.contract;

import java.util.UUID;
import org.synesis.coordination.domain.identity.ContractId;
import org.synesis.coordination.domain.identity.ContractRevision;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

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
        CoordinationNullException.requireNonNull(intentId, "intent ID");
        CoordinationNullException.requireNonNull(participant, "participant");
        CoordinationNullException.requireNonNull(contractId, "contract ID");
        CoordinationNullException.requireNonNull(state, "state");
        if (revision < 1 || participant.isBlank()) {
            throw new CoordinationArgumentException("invalid dependency");
        }
    }

    /**
     * Returns the typed consumer intent identity.
     *
     * @return consumer intent identity
     */
    public WorkIntentId intentIdentity() {
        return new WorkIntentId(intentId);
    }

    /**
     * Returns the typed contract identity.
     *
     * @return contract identity
     */
    public ContractId contractIdentity() {
        return new ContractId(contractId);
    }

    /**
     * Returns the exact typed contract revision.
     *
     * @return contract revision
     */
    public ContractRevision contractRevision() {
        return new ContractRevision(revision);
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
