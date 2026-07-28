package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;

/** Deterministic projection of active work intents and resource claims. */
public final class CollaborationProjection {
    private final Map<UUID, WorkIntent> intents = new LinkedHashMap<>();
    private final Map<UUID, CoordinationRequest> requests = new LinkedHashMap<>();
    private final Map<String, Participant> participantHistory = new LinkedHashMap<>();
    private boolean activated;

    /** Creates an empty collaboration projection. */
    public CollaborationProjection() {
    }

    /**
     * Applies one collaboration event.
     * @param event event
     * @throws IOException malformed transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> {
                activated = true;
                announce(CollaborationCodec.decodeIntent(event.payload()));
            }
            case WORK_INTENT_RELEASED -> release(CollaborationCodec.decodeRelease(event.payload()));
            case COORDINATION_REQUESTED -> request(CollaborationCodec.decodeRequest(event.payload()));
            case COORDINATION_RESPONDED -> respond(CollaborationCodec.decodeResponse(event.payload()));
            case PARTICIPANT_HEARTBEAT -> heartbeat(CollaborationCodec.decodeHeartbeat(event.payload()), event.createdAtEpochMillis());
            default -> {
            }
        }
    }

    /**
     * Validates one collaboration event without mutation.
     * @param event event
     * @throws IOException invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        CollaborationProjection candidate = new CollaborationProjection();
        candidate.intents.putAll(intents);
        candidate.requests.putAll(requests);
        candidate.participantHistory.putAll(participantHistory);
        candidate.activated = activated;
        candidate.apply(event);
    }

    /**
     * Returns an intent by identifier.
     * @param id intent ID
     * @return intent
     */
    public synchronized Optional<WorkIntent> intent(UUID id) {
        return Optional.ofNullable(intents.get(id));
    }

    /**
     * Returns all active intents.
     * @return immutable intents
     */
    public synchronized List<WorkIntent> activeIntents() {
        return List.copyOf(intents.values());
    }

    /** Returns active participant projections without connection or worktree details. */
    public synchronized List<Participant> participants() {
        return List.copyOf(participantHistory.values());
    }

    /** Returns whether this project has durable collaboration enforcement enabled. */
    public synchronized boolean activated() {
        return activated;
    }

    /** Returns all durable coordination requests. */
    public synchronized List<CoordinationRequest> requests() {
        return List.copyOf(requests.values());
    }

    /**
     * Finds claims overlapping any requested selector.
     * @param selectors selectors
     * @return conflicts
     */
    public synchronized List<ClaimConflict> conflicts(List<ResourceSelector> selectors) {
        List<ClaimConflict> result = new ArrayList<>();
        for (WorkIntent intent : intents.values()) {
            for (ResourceSelector existing : intent.selectors()) {
                for (ResourceSelector requested : selectors) {
                    if (existing.overlaps(requested)) {
                        result.add(new ClaimConflict(intent.participant(), intent.intentId().toString(), existing));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private void announce(WorkIntent intent) throws IOException {
        if (intents.containsKey(intent.intentId()) || !conflicts(intent.selectors()).isEmpty()) {
            throw new IOException("OVERLAPPING_CLAIM");
        }
        intents.put(intent.intentId(), intent);
        String opaqueId = intent.participant().startsWith("agt_") ? intent.participant() : "agt_" + intent.participant();
        participantHistory.put(intent.participant(), new Participant(opaqueId, intent.provider(),
                intent.goal(), Participant.State.ACTIVE, 0L, intent.selectors()));
    }

    private void release(UUID id) throws IOException {
        WorkIntent released = intents.remove(id);
        if (released == null) {
            throw new IOException("INTENT_NOT_FOUND");
        }
        Participant previous = participantHistory.get(released.participant());
        if (previous != null) {
            participantHistory.put(released.participant(), new Participant(previous.id(), previous.provider(),
                    previous.goal(), Participant.State.COMPLETED, previous.lastVerifiedActivity(), List.of()));
        }
    }

    private void request(CoordinationRequest request) throws IOException {
        if (requests.containsKey(request.requestId())) throw new IOException("REQUEST_EXISTS");
        if (!intents.containsKey(request.conflictingIntentId())) throw new IOException("INTENT_NOT_FOUND");
        requests.put(request.requestId(), request);
    }

    private void respond(CollaborationCodec.Response response) throws IOException {
        CoordinationRequest current = requests.get(response.requestId());
        if (current == null) throw new IOException("REQUEST_NOT_FOUND");
        if (current.status() != CoordinationRequest.Status.PENDING) {
            if (current.status() == response.status()) return;
            throw new IOException("REQUEST_ALREADY_RESOLVED");
        }
        requests.put(current.requestId(), new CoordinationRequest(current.requestId(), current.projectId(),
                current.requester(), current.target(), current.conflictingIntentId(), current.kind(),
                response.proposal().isBlank() ? current.proposal() : response.proposal(), response.status()));
    }

    private void heartbeat(String participant, long timestamp) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) throw new IOException("PARTICIPANT_NOT_FOUND");
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.ACTIVE, timestamp, current.claims()));
    }
}
