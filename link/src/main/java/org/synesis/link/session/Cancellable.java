package org.synesis.link.session;

/**
 * Idempotent cancellation handle for one scheduled liveness callback.
 */
interface Cancellable {

    void cancel();
}
