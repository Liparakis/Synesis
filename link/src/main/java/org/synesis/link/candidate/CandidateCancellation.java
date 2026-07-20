package org.synesis.link.candidate;

/** Cooperative cancellation signal supplied to provider and attempt code. */
@FunctionalInterface
public interface CandidateCancellation {
    /**
     * Returns whether the owning operation has been cancelled.
     *
     * @return true after cancellation wins
     */
    boolean isCancelled();
}
