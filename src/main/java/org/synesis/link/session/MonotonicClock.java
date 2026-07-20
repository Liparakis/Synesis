package org.synesis.link.transport;

/** Monotonic time seam; implementations must not use wall-clock time. */
interface MonotonicClock {
    long nanoTime();
}
