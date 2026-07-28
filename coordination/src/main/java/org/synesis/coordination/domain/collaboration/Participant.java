package org.synesis.coordination.domain.collaboration;

import java.util.List;
import java.util.Objects;

/** Public-safe participant projection; connection and filesystem identities are intentionally absent.
 * @param id opaque participant ID
 * @param provider provider ID
 * @param goal announced goal
 * @param state lifecycle state
 * @param lastVerifiedActivity last verified activity timestamp
 * @param claims active claims
 */
public record Participant(String id, String provider, String goal, State state,
        long lastVerifiedActivity, List<ResourceSelector> claims) {
    /** Participant lifecycle states visible to collaborators. */
    public enum State { /** Active. */ ACTIVE, /** Suspected stale. */ SUSPECTED_STALE, /** Abandoned. */ ABANDONED, /** Completed. */ COMPLETED, /** Cancelled. */ CANCELLED }

    /** Validates the opaque participant projection. */
    public Participant {
        Objects.requireNonNull(id, "participant ID");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(state, "state");
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        if (!id.startsWith("agt_")) throw new IllegalArgumentException("participant ID must be opaque");
    }
}
