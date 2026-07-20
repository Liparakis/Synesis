package org.synesis.link.candidate;

import java.util.concurrent.CompletionStage;

import org.synesis.link.session.PeerSession;

/** One caller-owned transport/authentication attempt for one candidate pair. */
public interface ConnectionAttempt {
    /**
     * Starts the attempt; completion is a winner candidate only after control readiness.
     *
     * @param cancellation cooperative race cancellation
     * @return eventual authenticated session or failure
     */
    CompletionStage<PeerSession> connect(CandidateCancellation cancellation);

    /** Cancels and closes all resources owned solely by this attempt. */
    void cancel();
}
