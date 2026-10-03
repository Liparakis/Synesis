package org.synesis.coordination.domain.collaboration;

import java.util.UUID;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Describes one existing claim that conflicts with a requested selector.
 *
 * @param participant conflicting participant
 * @param intentId    conflicting intent identifier
 * @param selector    conflicting selector
 */
public record ClaimConflict(String participant, String intentId, ResourceSelector selector) {

    /**
     * Creates a conflict using typed participant and intent identities.
     *
     * @param participant conflicting participant
     * @param intentId    conflicting intent
     * @param selector    conflicting selector
     */
    public ClaimConflict(ParticipantId participant, WorkIntentId intentId, ResourceSelector selector) {
        this(CoordinationNullException.requireNonNull(participant, "participant").value(),
                CoordinationNullException.requireNonNull(intentId, "intent ID").value().toString(),
                CoordinationNullException.requireNonNull(selector, "selector"));
    }

    /**
     * Returns the conflicting participant with its domain type.
     *
     * @return conflicting participant identity
     */
    public ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Parses and returns the conflicting work-intent identity.
     *
     * @return conflicting work-intent identity
     * @throws CoordinationArgumentException when the compatibility text is not a UUID
     */
    public WorkIntentId intentIdentity() {
        try {
            return new WorkIntentId(UUID.fromString(intentId));
        } catch (IllegalArgumentException invalidId) {
            throw new CoordinationArgumentException("invalid conflicting intent ID", invalidId);
        }
    }
}
