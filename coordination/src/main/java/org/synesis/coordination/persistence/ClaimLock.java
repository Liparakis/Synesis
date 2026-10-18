package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The claim lock between connected computers, as an agent's process sees it (ADR-0098 step 4, "Claims are locks
 * wherever they can be checked"). On one computer a claim is a lock already: the append that writes it refuses an
 * overlap. In a shared project the process then asks its own computer's coordination server, over loopback, which
 * asks every connected computer before the agent is answered.
 *
 * <p>A project on one computer asks nobody. A server that cannot be reached means no computer is connected, so the
 * claim stands: the lock is checked where it can be, and two claims made apart meet at {@code main}.</p>
 */
public final class ClaimLock {

    private ClaimLock() {
    }

    /**
     * Tells this computer's server that a claim is about to be appended, so that a connected computer asking about
     * an overlapping claim meanwhile finds it being checked. Nothing is asked of anyone; a server that cannot be
     * reached is not told, and the claim is then an ordinary lane there.
     *
     * @param root      the coordination folder
     * @param projectId the project
     * @param intent    the lane about to be claimed, as {@code WORK_INTENT_ANNOUNCED} encodes it
     */
    public static void announce(Path root, UUID projectId, byte[] intent) {
        try {
            if (ReplicationSettings.read(CoordinationNullException.requireNonNull(root, "root")).isEmpty()) {
                return;
            }
            new LoopbackExchange(root).exchange(ReplicationCodec.encode(new ReplicationMessage.ClaimIntent(
                    CoordinationNullException.requireNonNull(projectId, "project"), intent)));
        } catch (IOException notTold) {
            // No server, or no answer: nothing is registered, and the check after the append asks again.
        }
    }

    /**
     * Asks the connected computers whether a claim this computer just appended may stand.
     *
     * @param root      the coordination folder
     * @param projectId the project
     * @param claim     the {@code WORK_INTENT_ANNOUNCED} entry as placed
     * @return empty when the claim stands; {@code OVERLAPPING_CLAIM} when it must give way
     */
    public static String confirm(Path root, UUID projectId, PredictionEvent claim) {
        CoordinationNullException.requireNonNull(claim, "claim");
        try {
            if (ReplicationSettings.read(CoordinationNullException.requireNonNull(root, "root")).isEmpty()) {
                return "";
            }
        } catch (CoordinationException unreadable) {
            // The append itself refuses with an unreadable role; nothing was claimed.
            return "";
        }
        byte[] reply;
        try {
            reply = new LoopbackExchange(root).exchange(ReplicationCodec.encode(
                    new ReplicationMessage.ClaimSeen(CoordinationNullException.requireNonNull(projectId, "project"),
                            claim.encoded())));
        } catch (IOException notConnected) {
            return "";
        }
        try {
            return ReplicationCodec.decode(reply) instanceof ReplicationMessage.ClaimAnswer answer ? answer.code() : "";
        } catch (CoordinationException malformed) {
            return "";
        }
    }
}
