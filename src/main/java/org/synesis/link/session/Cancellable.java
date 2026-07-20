package org.synesis.link.transport;

/** Idempotent cancellation handle for one scheduled liveness callback. */
interface Cancellable {
    void cancel();
}
