package org.synesis.coordination.replication;

import org.synesis.link.session.PeerSession;

/**
 * What the replication protocol needs to know about a Link session that {@link PeerSession#isUsable()} does not
 * say: readiness stays true after the session ended, so a request on an ended session would fail only after it
 * was started.
 */
final class Sessions {

    private Sessions() {
    }

    /**
     * Tells whether a request sent now is expected to leave: the session is control-ready and has not ended.
     *
     * @param session a session
     * @return true when the session is live
     */
    static boolean live(PeerSession session) {
        return session != null && session.isUsable() && !session.terminalCompletion().toCompletableFuture().isDone();
    }
}
