package org.synesis.link.session;

import java.time.Duration;
import java.util.Objects;

/**
 * One ordered liveness state transition.
 *
 * <p>The elapsed value is monotonic silence observed locally; it is not a wall
 * clock timestamp and must not be interpreted as remote time.</p>
 *
 * @param from state before the transition
 * @param to state after the transition
 * @param elapsedPeerSilence monotonic peer-silence age observed at selection
 * @since 1.0
 */
public record LivenessTransition(LivenessState from, LivenessState to,
        Duration elapsedPeerSilence) {

    /** Validates an immutable transition value. */
    public LivenessTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(elapsedPeerSilence, "elapsed peer silence");
        if (from == to || elapsedPeerSilence.isNegative()) {
            throw new IllegalArgumentException("invalid liveness transition");
        }
    }
}
