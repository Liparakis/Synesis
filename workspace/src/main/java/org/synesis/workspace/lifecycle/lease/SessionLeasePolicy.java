package org.synesis.workspace.lifecycle.lease;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Configurable policy thresholds and clock reference for provider-session leases.
 *
 * @since 1.0
 */
@SuppressWarnings("ClassCanBeRecord")
public final class SessionLeasePolicy {

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(2);
    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofMinutes(5);

    private final Clock clock;
    private final Duration heartbeatInterval;
    private final Duration suspectedStaleThreshold;
    private final Duration abandonmentGracePeriod;

    /**
     * Creates a lease policy with system UTC clock and default duration thresholds.
     */
    public SessionLeasePolicy() {
        this(Clock.systemUTC(), DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_STALE_THRESHOLD, DEFAULT_GRACE_PERIOD);
    }

    /**
     * Creates a lease policy with explicit clock and thresholds.
     *
     * @param clock                   clock source
     * @param heartbeatInterval       recommended heartbeat renewal interval
     * @param suspectedStaleThreshold elapsed time after heartbeat when session becomes suspected stale
     * @param abandonmentGracePeriod  elapsed time after heartbeat when session becomes abandonment eligible
     */
    public SessionLeasePolicy(
            Clock clock,
            Duration heartbeatInterval,
            Duration suspectedStaleThreshold,
            Duration abandonmentGracePeriod
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.suspectedStaleThreshold = Objects.requireNonNull(suspectedStaleThreshold, "suspectedStaleThreshold");
        this.abandonmentGracePeriod = Objects.requireNonNull(abandonmentGracePeriod, "abandonmentGracePeriod");
    }

    /**
     * Returns the clock reference.
     *
     * @return clock
     */
    @SuppressWarnings("unused")
    public Clock clock() {
        return clock;
    }

    /**
     * Returns current instant from clock.
     *
     * @return current instant
     */
    public long nowMillis() {
        return clock.millis();
    }

    /**
     * Returns heartbeat renewal interval.
     *
     * @return duration
     */
    @SuppressWarnings("unused")
    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Returns suspected stale threshold.
     *
     * @return duration
     */
    public Duration suspectedStaleThreshold() {
        return suspectedStaleThreshold;
    }

    /**
     * Returns abandonment grace period.
     *
     * @return duration
     */
    public Duration abandonmentGracePeriod() {
        return abandonmentGracePeriod;
    }
}
