package org.synesis.coordination.application;

/**
 * Terminal outcome of a recovery-continuation attempt.
 */
enum ContinuationStatus {
    /**
     * The caller committed or owns the resulting successor.
     */
    COMMITTED,
    /**
     * A competing request already committed a different successor.
     */
    ALREADY_COMMITTED
}
