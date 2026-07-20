package org.synesis.link.transport;

/** Bounded event-dispatch seam; returning false records a dropped event. */
interface LivenessEventDispatcher {
    boolean dispatch(Runnable action);
}
