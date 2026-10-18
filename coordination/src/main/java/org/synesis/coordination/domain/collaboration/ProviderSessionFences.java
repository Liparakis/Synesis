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
     * Fences a provider session using its exact optimistic revision and terminal evidence. The revision and the
     * conflicts are judged on this computer's own append only: off it, a session is terminal once any entry says so,
     * and a participant once any of its sessions is.
     *
     * @param payload       the fence
     * @param eventSequence local revision of the entry
     * @param checking      whether this computer's own new entry is being judged
     * @throws IOException when the revision is stale or the evidence conflicts with an earlier fence
     */
    void terminalize(ProviderSessionTerminalPayload payload, long eventSequence, boolean checking) throws IOException {
        if (checking && payload.validatedRevision() != eventSequence - 1L) {
            throw new CoordinationException("TERMINAL_REVISION_STALE");
        }
        ProviderSessionTerminalPayload previous = terminalSessions.get(payload.sessionIdentity());
        if (previous != null) {
            if (checking && !previous.equals(payload)) {
                throw new CoordinationException("TERMINAL_SESSION_CONFLICT");
            }
            return;
        }
        if (checking && isParticipantTerminal(payload.participantIdentity())) {
            throw new CoordinationException("TERMINAL_PARTICIPANT_CONFLICT");
        }
        terminalSessions.put(payload.sessionIdentity(), payload);
    }
}
