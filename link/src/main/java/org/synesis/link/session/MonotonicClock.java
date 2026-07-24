package org.synesis.link.session;

/**
 * Monotonic time seam; implementations must not use wall-clock time.
 */
interface MonotonicClock {

    long nanoTime();
}
