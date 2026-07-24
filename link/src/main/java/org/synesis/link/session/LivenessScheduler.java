package org.synesis.link.session;

import java.time.Duration;

/**
 * Minimal scheduler seam owned by one liveness tracker.
 */
public interface LivenessScheduler {

    /**
     * Schedules a runnable action to execute after the specified delay.
     *
     * @param action runnable action to execute
     * @param delay  delay before execution
     * @return handle to cancel the scheduled execution
     */
    Cancellable schedule(Runnable action, Duration delay);
}
