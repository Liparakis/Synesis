package org.synesis.link.overlay;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable retry and concurrency limits for overlay adjacency reconciliation.
 *
 * <p>Delays use local monotonic scheduling. They are implementation policy,
 * not wire values, and this record is immutable and thread-safe.
 *
 * @param initialRetryDelay       delay after the first failed attempt
 * @param maximumRetryDelay       upper bound for exponential retry delay
 * @param maxConcurrentConnects   maximum simultaneous missing-edge attempts
 * @since 1.0
 */
public record OverlayReconciliationPolicy(Duration initialRetryDelay,
                                          Duration maximumRetryDelay,
                                          int maxConcurrentConnects) {

  /**
   * Default conservative retry policy for an owning runtime.
   */
  public static final OverlayReconciliationPolicy DEFAULT = new OverlayReconciliationPolicy(
      Duration.ofSeconds(1), Duration.ofSeconds(30), 2);

  /**
   * Validates retry delays and concurrency bounds.
   */
  public OverlayReconciliationPolicy {
    Objects.requireNonNull(initialRetryDelay, "initial retry delay");
    Objects.requireNonNull(maximumRetryDelay, "maximum retry delay");
    if (initialRetryDelay.isZero() || initialRetryDelay.isNegative()
        || maximumRetryDelay.isZero() || maximumRetryDelay.isNegative()
        || maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
      throw new IllegalArgumentException("retry delays must be positive and ordered");
    }
    if (maxConcurrentConnects < 1) {
      throw new IllegalArgumentException("concurrent connect bound must be positive");
    }
    try {
      initialRetryDelay.toNanos();
      maximumRetryDelay.toNanos();
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("retry delay exceeds supported range", failure);
    }
  }

  /**
   * Computes a bounded exponential delay for one consecutive failure count.
   *
   * @param consecutiveFailures positive failure count
   * @return bounded retry delay
   */
  Duration delayFor(int consecutiveFailures) {
    if (consecutiveFailures < 1) {
      throw new IllegalArgumentException("failure count must be positive");
    }
    Duration delay = initialRetryDelay;
    for (int attempt = 1; attempt < consecutiveFailures
        && delay.compareTo(maximumRetryDelay) < 0; attempt++) {
      Duration doubled;
      try {
        doubled = delay.multipliedBy(2);
      } catch (ArithmeticException overflow) {
        doubled = maximumRetryDelay;
      }
      delay = doubled.compareTo(maximumRetryDelay) > 0 ? maximumRetryDelay : doubled;
    }
    return delay;
  }
}
