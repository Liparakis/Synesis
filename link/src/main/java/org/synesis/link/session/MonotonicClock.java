package org.synesis.link.session;

/**
 * Monotonic time seam; implementations must not use wall-clock time.
 */
public interface MonotonicClock {

    /**
     * Returns the current value of the monotonic time source, in nanoseconds.
     *
     * @return current monotonic time in nanoseconds
     */
    long nanoTime();
}
