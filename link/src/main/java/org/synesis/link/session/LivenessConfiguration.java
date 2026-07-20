package org.synesis.link.session;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable application-liveness timing policy.
 *
 * <p>Durations use a monotonic clock. The heartbeat interval must be positive,
 * suspicion must be strictly later than heartbeat, and expiry must be strictly
 * later than suspicion. The defaults are implementation defaults, not wire
 * constants. This value is immutable and thread-safe.</p>
 *
 * @param heartbeatInterval interval between at most one scheduled heartbeat
 * @param suspicionTimeout elapsed peer silence at which state becomes {@code SUSPECT}
 * @param expiryTimeout elapsed peer silence at which state becomes {@code EXPIRED}
 * @param refreshOnControlActivity whether other valid non-terminal control messages may refresh liveness
 * @since 1.0
 */
public record LivenessConfiguration(
        Duration heartbeatInterval,
        Duration suspicionTimeout,
        Duration expiryTimeout,
        boolean refreshOnControlActivity) {

    /** The conservative local default: one-second heartbeats and five-second expiry. */
    public static final LivenessConfiguration DEFAULT = new LivenessConfiguration(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5), true);

    /**
     * Creates a validated timing policy.
     *
     * @throws NullPointerException if a duration is null
     * @throws IllegalArgumentException if durations are non-positive or out of order
     */
    public LivenessConfiguration {
        Objects.requireNonNull(heartbeatInterval, "heartbeat interval");
        Objects.requireNonNull(suspicionTimeout, "suspicion timeout");
        Objects.requireNonNull(expiryTimeout, "expiry timeout");
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        Duration suspicionGap = suspicionTimeout.minus(heartbeatInterval);
        if (suspicionGap.isZero() || suspicionGap.isNegative()) {
            throw new IllegalArgumentException("suspicion timeout must exceed heartbeat interval");
        }
        Duration expiryGap = expiryTimeout.minus(suspicionTimeout);
        if (expiryGap.isZero() || expiryGap.isNegative()) {
            throw new IllegalArgumentException("expiry timeout must exceed suspicion timeout");
        }
        try {
            heartbeatInterval.toNanos();
            suspicionTimeout.toNanos();
            expiryTimeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("liveness durations exceed supported monotonic range", exception);
        }
    }
}
