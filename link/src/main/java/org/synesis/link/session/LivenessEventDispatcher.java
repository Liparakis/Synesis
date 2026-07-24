package org.synesis.link.session;

/**
 * Bounded event-dispatch seam; returning false records a dropped event.
 */
interface LivenessEventDispatcher {

    boolean dispatch(Runnable action);
}
