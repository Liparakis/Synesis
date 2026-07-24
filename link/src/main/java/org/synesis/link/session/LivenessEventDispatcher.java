package org.synesis.link.session;

/**
 * Bounded event-dispatch seam; returning false records a dropped event.
 */
public interface LivenessEventDispatcher {

    /**
     * Dispatches the given action.
     *
     * @param action runnable action to dispatch
     * @return true if accepted, false if dropped
     */
    boolean dispatch(Runnable action);
}
