package org.synesis.coordination.domain.collaboration;

import java.util.List;
import java.util.Objects;

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
public record Participant(String id, String provider, String goal, State state,
                          long lastVerifiedActivity, List<ResourceSelector> claims,
                          String recoverySnapshotReference) {

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
    public Participant(String id, String provider, String goal, State state,
            long lastVerifiedActivity, List<ResourceSelector> claims) {
        this(id, provider, goal, state, lastVerifiedActivity, claims, null);
    }

    /**
     * Validates the opaque participant projection.
     */
    public Participant {
        Objects.requireNonNull(id, "participant ID");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(state, "state");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        if (recoverySnapshotReference != null && recoverySnapshotReference.isBlank()) {
            throw new IllegalArgumentException("recovery snapshot reference must be nonblank");
        }
        if (!id.startsWith("agt_")) {
            throw new IllegalArgumentException("participant ID must be opaque");
        }
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
         * Activity has not been verified recently; authority has not transferred.
         */
        SUSPECTED_STALE,
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
