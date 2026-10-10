package org.synesis.workspace.application.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the identities a lane takes from the provider session or connection that holds it.
 *
 * <p>The results are persisted in the coordination log and recomputed by later calls, so the seed
 * text, charset, and order of every formula here must never change.
 */
public final class LaneIdentities {

    private LaneIdentities() {
    }

    /**
     * Derives the intent identity of the single lane one provider session owns.
     *
     * @param provider  provider ID
     * @param sessionId provider session ID
     * @return name-based UUID of {@code provider:sessionId}
     */
    public static UUID sessionIntentId(String provider, String sessionId) {
        return nameUuid(provider + ":" + sessionId);
    }

    /** The UUID a connection instance maps to; the participant handle embeds it and the lane task takes it as is. */
    static UUID connectionUuid(String connectionInstanceId) {
        return nameUuid(connectionInstanceId);
    }

    private static UUID nameUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
