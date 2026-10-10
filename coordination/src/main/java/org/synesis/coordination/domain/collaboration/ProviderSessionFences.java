package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Irreversible terminal fences of provider sessions, owned by a {@link CollaborationProjection}. Nothing else in
 * the projection reads or writes them.
 */
final class ProviderSessionFences {

    private final Map<ProviderSessionId, ProviderSessionTerminalPayload> terminalSessions = new LinkedHashMap<>();

    ProviderSessionFences() {
    }

    ProviderSessionFences(ProviderSessionFences source) {
        terminalSessions.putAll(source.terminalSessions);
    }

    boolean isSessionTerminal(ProviderSessionId sessionId) {
        return terminalSessions.containsKey(CoordinationNullException.requireNonNull(sessionId, "session ID"));
    }

    boolean isParticipantTerminal(ParticipantId participant) {
        return terminalSessions.values().stream().anyMatch(payload -> payload.participantIdentity()
                .equals(CoordinationNullException.requireNonNull(participant, "participant")));
    }

    /**
     * Fences a provider session using its exact optimistic revision and terminal evidence.
     *
     * @throws IOException when the revision is stale or the evidence conflicts with an earlier fence
     */
    void terminalize(ProviderSessionTerminalPayload payload, long eventSequence) throws IOException {
        if (payload.validatedRevision() != eventSequence - 1L) {
            throw new CoordinationException("TERMINAL_REVISION_STALE");
        }
        ProviderSessionTerminalPayload previous = terminalSessions.get(payload.sessionIdentity());
        if (previous != null) {
            if (!previous.equals(payload)) {
                throw new CoordinationException("TERMINAL_SESSION_CONFLICT");
            }
            return;
        }
        if (isParticipantTerminal(payload.participantIdentity())) {
            throw new CoordinationException("TERMINAL_PARTICIPANT_CONFLICT");
        }
        terminalSessions.put(payload.sessionIdentity(), payload);
    }
}
