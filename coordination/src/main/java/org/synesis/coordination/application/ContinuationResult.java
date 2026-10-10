package org.synesis.coordination.application;

import java.util.UUID;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable result of a recovery-continuation request.
 *
 * @param outcome             whether this call created or observed the successor
 * @param successor           typed identity of the durable successor lane
 * @param successorOwner      participant that owns the successor
 * @param callerOwnsSuccessor whether the caller may act on that successor
 */
public record ContinuationResult(ContinuationStatus outcome, WorkIntentId successor, ParticipantId successorOwner,
                                 boolean callerOwnsSuccessor) {

    /**
     * Reports an already-durable successor: committed when the caller owns it, already committed otherwise.
     *
     * @param successor           durable successor lane
     * @param callerOwnsSuccessor whether the caller matches that successor
     * @return result describing the observed successor
     */
    public static ContinuationResult observed(WorkIntent successor, boolean callerOwnsSuccessor) {
        return new ContinuationResult(
                callerOwnsSuccessor ? ContinuationStatus.COMMITTED : ContinuationStatus.ALREADY_COMMITTED,
                successor.identity(),
                successor.participantIdentity(),
                callerOwnsSuccessor);
    }

    /**
     * Validates the durable successor identity and outcome.
     */
    public ContinuationResult {
        CoordinationNullException.requireNonNull(outcome, "outcome");
        CoordinationNullException.requireNonNull(successor, "successor intent ID");
        CoordinationNullException.requireNonNull(successorOwner, "successor participant");
    }

    /**
     * Returns the existing string form used by current callers.
     *
     * @return stable status token
     */
    public String status() {
        return outcome.name();
    }

    /**
     * Returns the UUID form used by existing callers.
     *
     * @return successor UUID
     */
    public UUID successorIntentId() {
        return successor.value();
    }

    /**
     * Returns the participant handle used by existing callers.
     *
     * @return successor participant handle
     */
    public String successorParticipant() {
        return successorOwner.value();
    }
}
