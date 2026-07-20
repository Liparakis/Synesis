package org.synesis.link.session;

/**
 * Receives ordered liveness transitions.
 *
 * <p>Listeners must be short and non-blocking. Transport integration dispatches
 * them through a bounded, session-owned daemon worker; a throwing listener is
 * isolated from protocol state.</p>
 *
 * @since 1.0
 */
@FunctionalInterface
public interface LivenessListener {

    /**
     * Observes one transition; the supplied value is immutable.
     *
     * @param transition transition in state-machine order
     */
    void onTransition(LivenessTransition transition);
}
