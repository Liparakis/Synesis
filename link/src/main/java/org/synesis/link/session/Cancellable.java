package org.synesis.link.session;

/**
 * Idempotent cancellation handle for one scheduled liveness callback.
 */
public interface Cancellable {

    /**
     * Cancels the scheduled liveness callback.
     */
    void cancel();
}
