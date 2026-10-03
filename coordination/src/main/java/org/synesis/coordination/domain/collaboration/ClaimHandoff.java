package org.synesis.coordination.domain.collaboration;

import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;

/**
 * Accepted transfer of a claim to its named participant.
 *
 * @param intent            intent being transferred
 * @param targetParticipant participant receiving the claim
 * @param expectedEpoch     claim epoch the handoff must match
 */
public record ClaimHandoff(WorkIntentId intent, ParticipantId targetParticipant, ClaimEpoch expectedEpoch) {

    /**
     * Compatibility accessor for the existing codec.
     *
     * @return raw handle of {@link #targetParticipant()}
     */
    public String target() {
        return targetParticipant.value();
    }

    /**
     * Compatibility accessor for the existing codec.
     *
     * @return numeric value of {@link #expectedEpoch()}
     */
    public long expectedVersion() {
        return expectedEpoch.value();
    }
}
