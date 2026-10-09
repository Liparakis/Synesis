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
 * @param ownerAuthorityLineageId exact owner lineage to route a capability request
 */
public record ClaimConflict(String participant, String intentId, ResourceSelector selector, UUID ownerAuthorityLineageId) {

    /**
     * Creates a conflict carrying the exact lineage of its current owner intent.
     *
     * @param ownerIntent active owner intent for the conflicting claim
     * @param selector requested selector that conflicts
     */
    public ClaimConflict(WorkIntent ownerIntent, ResourceSelector selector) {
        this(CoordinationNullException.requireNonNull(ownerIntent, "owner intent").participantIdentity(),
                ownerIntent.identity(),
                selector,
                ownerIntent.authorityLineageId());
    }

    /**
     * Creates a conflict using typed identities and its exact owner lineage.
     *
     * @param participant conflicting participant identity
     * @param intentId conflicting work-intent identity
     * @param selector requested selector that conflicts
     * @param ownerAuthorityLineageId exact lineage that owns the conflicting selector
     */
    public ClaimConflict(ParticipantId participant, WorkIntentId intentId, ResourceSelector selector, UUID ownerAuthorityLineageId) {
        this(CoordinationNullException.requireNonNull(participant, "participant").value(),
                CoordinationNullException.requireNonNull(intentId, "intent ID").value().toString(),
                CoordinationNullException.requireNonNull(selector, "selector"),
                ownerAuthorityLineageId);
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
