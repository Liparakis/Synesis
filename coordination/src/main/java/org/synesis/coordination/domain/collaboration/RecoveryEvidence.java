package org.synesis.coordination.domain.collaboration;

import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotReference;

/**
 * Participant identity and immutable snapshot reference used during recovery.
 *
 * @param participantId participant whose recovery is held
 * @param snapshot      immutable snapshot reference associated with the participant
 */
public record RecoveryEvidence(ParticipantId participantId, SnapshotReference snapshot) {

}
