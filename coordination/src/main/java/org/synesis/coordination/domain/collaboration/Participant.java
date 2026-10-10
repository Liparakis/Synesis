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
    Optional<SnapshotReference> recoverySnapshotIdentity() {
        return Optional.ofNullable(recoverySnapshotReference).map(SnapshotReference::new);
    }

    /**
     * Returns a copy in the given lifecycle state.
     *
     * @param newState lifecycle state of the copy
     * @return the copy
     */
    Participant withState(State newState) {
        return new Participant(id, provider, goal, newState, lastVerifiedActivity, claims, recoverySnapshotReference);
    }

    /**
     * Returns a copy holding the given claims.
     *
     * @param newClaims active claims of the copy
     * @return the copy
     */
    Participant withClaims(List<ResourceSelector> newClaims) {
        return new Participant(id, provider, goal, state, lastVerifiedActivity, newClaims, recoverySnapshotReference);
    }

    /**
     * Returns a copy announcing the given goal.
     *
     * @param newGoal announced goal of the copy
     * @return the copy
     */
    Participant withGoal(String newGoal) {
        return new Participant(id, provider, newGoal, state, lastVerifiedActivity, claims, recoverySnapshotReference);
    }

    /**
     * Returns a copy with the given last verified activity timestamp.
     *
     * @param timestamp last verified activity timestamp of the copy
     * @return the copy
     */
    Participant withLastVerifiedActivity(long timestamp) {
        return new Participant(id, provider, goal, state, timestamp, claims, recoverySnapshotReference);
    }

    /**
     * Returns a copy holding the given recovery snapshot reference.
     *
     * @param reference opaque internal recovery snapshot reference of the copy
     * @return the copy
     */
    Participant withRecoverySnapshotReference(String reference) {
        return new Participant(id, provider, goal, state, lastVerifiedActivity, claims, reference);
    }

    /**
     * Returns a copy that no longer carries a recovery snapshot reference.
     *
     * @return the copy
     */
    Participant withoutRecoveryReference() {
        return withRecoverySnapshotReference(null);
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
