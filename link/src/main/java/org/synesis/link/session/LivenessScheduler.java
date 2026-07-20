package org.synesis.link.transport;

import java.time.Duration;

/** Minimal scheduler seam owned by one liveness tracker. */
interface LivenessScheduler {
    Cancellable schedule(Runnable action, Duration delay);
}
