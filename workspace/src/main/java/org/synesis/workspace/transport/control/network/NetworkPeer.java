package org.synesis.workspace.transport.control.network;

import java.util.Objects;

/**
 * Safe physical Link session summary.
 *
 * @param nodeId        remote node identity
 * @param sessionId     session identity
 * @param liveness      liveness state
 * @param usable        whether the session is usable
 * @param establishedAt establishment timestamp
 * @param authenticated whether the session identity was authenticated
 * @param health        safe peer health classification
 */
public record NetworkPeer(String nodeId, String sessionId, String liveness, boolean usable, String establishedAt, boolean authenticated, String health) {

    /**
     * Creates a peer summary using the usable state as a health hint.
     *
     * @param nodeId        remote node identity
     * @param sessionId     session identity
     * @param liveness      liveness state
     * @param usable        whether the session is usable
     * @param establishedAt establishment timestamp
     */
    public NetworkPeer(String nodeId, String sessionId, String liveness, boolean usable, String establishedAt) {
        this(nodeId, sessionId, liveness, usable, establishedAt, true, usable ? "HEALTHY" : "UNAVAILABLE");
    }

    /**
     * Validates the physical peer summary.
     */
    public NetworkPeer {
        Objects.requireNonNull(nodeId, "node ID");
        Objects.requireNonNull(sessionId, "session ID");
        Objects.requireNonNull(liveness, "liveness");
        Objects.requireNonNull(establishedAt, "established at");
        Objects.requireNonNull(health, "health");
    }
}
