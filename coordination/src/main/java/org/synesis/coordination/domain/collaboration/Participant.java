package org.synesis.coordination.domain.collaboration;

import java.util.List;
import java.util.Optional;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Public-safe participant projection; connection and filesystem identities are intentionally absent.
 *
 * @param id                        opaque participant ID
 * @param provider                  provider ID
 * @param goal                      announced goal
 * @param state                     lifecycle state
 * @param lastVerifiedActivity      last verified activity timestamp
 * @param claims                    active claims
 * @param recoverySnapshotReference opaque internal recovery snapshot reference
 */
public record Participant(String id, String provider, String goal, State state, long lastVerifiedActivity,
                          List<ResourceSelector> claims, String recoverySnapshotReference) {

    /**
     * Creates a participant without a recovery snapshot reference.
     *
     * @param id                   opaque participant ID
     * @param provider             provider ID
     * @param goal                 announced goal
     * @param state                lifecycle state
     * @param lastVerifiedActivity last verified activity timestamp
     * @param claims               active claims
     */
    public Participant(String id,
            String provider,
            String goal,
            State state,
            long lastVerifiedActivity,
            List<ResourceSelector> claims) {
        this(id, provider, goal, state, lastVerifiedActivity, claims, null);
    }

    /**
     * Validates the opaque participant projection.
     */
    public Participant {
        CoordinationNullException.requireNonNull(id, "participant ID");
        CoordinationNullException.requireNonNull(provider, "provider");
        CoordinationNullException.requireNonNull(goal, "goal");
        CoordinationNullException.requireNonNull(state, "state");
        claims = List.copyOf(CoordinationNullException.requireNonNull(claims, "claims"));
        if (recoverySnapshotReference != null && recoverySnapshotReference.isBlank()) {
            throw new CoordinationArgumentException("recovery snapshot reference must be nonblank");
        }
        if (!id.startsWith("agt_")) {
            throw new CoordinationArgumentException("participant ID must be opaque");
        }
    }

    /**
     * Returns the opaque participant incarnation identity.
     *
     * @return the opaque participant incarnation identity.
     */
    public ParticipantId identity() {
        return new ParticipantId(id);
    }

    /**
     * Returns the typed recovery snapshot reference when this participant is recovery-held.
     *
     * @return recovery snapshot reference, or empty when no snapshot is recorded
     */
    public Optional<SnapshotReference> recoverySnapshotIdentity() {
        return Optional.ofNullable(recoverySnapshotReference).map(SnapshotReference::new);
    }

    /**
     * Participant lifecycle states visible to collaborators.
     */
    public enum State {
        /**
         * Active and authorized to mutate within the participant's claims.
         */
        ACTIVE,
        /**
         * The old binding is fenced while recovery evidence is being prepared.
         */
        SUSPENDED,
        /**
         * A verified immutable recovery snapshot exists and scope remains reserved.
         */
        RECOVERY_HELD,
        /**
         * The lane was explicitly revoked and cannot resume.
         */
        REVOKED,
        /**
         * The lane completed normally.
         */
        COMPLETED,
        /**
         * The lane was explicitly cancelled and cannot resume.
         */
        CANCELLED,
        /**
         * The connection ended cleanly before a terminal lane decision.
         */
        DETACHED
    }
}
