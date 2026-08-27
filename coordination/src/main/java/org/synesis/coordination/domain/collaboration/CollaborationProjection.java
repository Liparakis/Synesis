package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;

/** Deterministic projection of active work intents and resource claims. */
public final class CollaborationProjection {
    private final Map<UUID, WorkIntent> intents = new LinkedHashMap<>();
    private final Map<UUID, CoordinationRequest> requests = new LinkedHashMap<>();
    private final Map<String, Participant> participantHistory = new LinkedHashMap<>();
    private final Set<UUID> acknowledgedInboxItems = new HashSet<>();
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
            case CLAIM_HANDOFF_ACCEPTED -> handoff(CollaborationCodec.decodeHandoff(event.payload()));
            case REPAIR_LANE_CREATED -> repair(RepairLanePayload.decode(event.payload()));
            case COMPLETION_UNWOUND -> unwind(org.synesis.coordination.domain.task.CompletionUnwoundPayload.decode(event.payload()));
            case PARTICIPANT_ABANDONED, PARTICIPANT_SUSPENDED -> suspended(CollaborationCodec.decodeHeartbeat(event.payload()));
            case RECOVERY_SNAPSHOT_HELD -> recoveryHeld(CollaborationCodec.decodeRecovery(event.payload()));
            case PARTICIPANT_REVOKED -> revoked(CollaborationCodec.decodeHeartbeat(event.payload()));
            case INBOX_ITEM_ACKNOWLEDGED -> acknowledge(CollaborationCodec.decodeUuidText(event.payload()));
            case PARTICIPANT_CANCELLED -> cancelled(CollaborationCodec.decodeHeartbeat(event.payload()));
            case LANE_CONTINUATION_ACCEPTED -> continued(CollaborationCodec.decodeContinuation(event.payload()));
            case PARTICIPANT_DETACHED -> detached(CollaborationCodec.decodeHeartbeat(event.payload()));
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
        candidate.acknowledgedInboxItems.addAll(acknowledgedInboxItems);
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

    /** Returns active participant projections without connection or worktree details.
     * @return participant projections
     */
    public synchronized List<Participant> participants() {
        return List.copyOf(participantHistory.values());
    }

    /** Returns whether this project has durable collaboration enforcement enabled.
     * @return true after the first intent event
     */
    public synchronized boolean activated() {
        return activated;
    }

    /** Returns all durable coordination requests.
     * @return requests
     */
    public synchronized List<CoordinationRequest> requests() {
        return List.copyOf(requests.values());
    }

    /** Returns whether an inbox item has been acknowledged.
     * @param itemId server-issued item identifier
     * @return true when acknowledged
     */
    public synchronized boolean inboxAcknowledged(UUID itemId) {
        return acknowledgedInboxItems.contains(Objects.requireNonNull(itemId, "item ID"));
    }

    /** Resolves the internal projection key for an opaque participant handle.
     * @param handle participant handle
     * @return internal key, when present
     */
    public synchronized Optional<String> participantKey(String handle) {
        return participantHistory.entrySet().stream()
                .filter(entry -> entry.getKey().equals(handle) || entry.getValue().id().equals(handle))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Returns the internal recovery reference for a participant.
     * @param handle participant handle
     * @return opaque snapshot reference when held
     */
    public synchronized Optional<String> recoverySnapshotReference(String handle) {
        return participantKey(handle).map(participantHistory::get)
                .map(Participant::recoverySnapshotReference)
                .filter(Objects::nonNull);
    }

    /** Returns a participant lifecycle state by opaque handle or projection key.
     * @param handle participant handle
     * @return lifecycle state when present
     */
    public synchronized Optional<Participant.State> participantState(String handle) {
        return participantKey(handle).map(participantHistory::get).map(Participant::state);
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
        Participant existing = participantHistory.get(intent.participant());
        if (existing != null && existing.state() == Participant.State.REVOKED) {
            throw new IOException("SESSION_EPOCH_FENCED");
        }
        if (intents.containsKey(intent.intentId()) || !conflicts(intent.selectors()).isEmpty()) {
            throw new IOException("OVERLAPPING_CLAIM");
        }
        intents.put(intent.intentId(), intent);
        String opaqueId = intent.participant().startsWith("agt_") ? intent.participant() : "agt_" + intent.participant();
        participantHistory.put(intent.participant(), new Participant(opaqueId, intent.provider(),
                intent.goal(), Participant.State.ACTIVE, 0L, intent.selectors(), null));
    }

    private void release(UUID id) throws IOException {
        WorkIntent released = intents.remove(id);
        if (released == null) {
            throw new IOException("INTENT_NOT_FOUND");
        }
        Participant previous = participantHistory.get(released.participant());
        if (previous != null) {
            participantHistory.put(released.participant(), new Participant(previous.id(), previous.provider(),
                    previous.goal(), Participant.State.COMPLETED, previous.lastVerifiedActivity(), List.of(),
                    previous.recoverySnapshotReference()));
        }
    }

    private void repair(RepairLanePayload payload) throws IOException {
        WorkIntent source = intents.get(payload.sourceIntentId());
        if (source == null) {
            throw new IOException("REPAIR_SOURCE_NOT_FOUND");
        }
        WorkIntent target = payload.targetIntent();
        if (target.status() != WorkIntent.Status.ANNOUNCED) {
            throw new IOException("REPAIR_TARGET_NOT_ANNOUNCED");
        }
        if (!source.projectId().equals(target.projectId())
                || !source.workGroupId().equals(target.workGroupId())) {
            throw new IOException("REPAIR_WORK_GROUP_MISMATCH");
        }
        if (!source.authorityLineageId().equals(target.authorityLineageId())) {
            throw new IOException("REPAIR_AUTHORITY_LINEAGE_MISMATCH");
        }
        if (source.intentId().equals(target.intentId())
                || source.participant().equals(target.participant())) {
            throw new IOException("REPAIR_TARGET_MUST_BE_DISTINCT");
        }
        if (!source.selectors().equals(target.selectors())) {
            throw new IOException("REPAIR_SCOPE_MISMATCH");
        }
        if (payload.snapshotId() != null && !payload.snapshotId().isBlank()) {
            if (payload.expectedControlHead().isBlank()
                    || payload.sourceClaimEpoch() != source.version()
                    || payload.targetClaimEpoch() != target.version()
                    || target.version() != source.version() + 1L) {
                throw new IOException("REPAIR_EPOCH_MISMATCH");
            }
        }
        if (intents.values().stream().anyMatch(intent ->
                !intent.intentId().equals(source.intentId())
                        && intent.participant().equals(target.participant()))) {
            throw new IOException("REPAIR_TARGET_ALREADY_ACTIVE");
        }

        // The event is validated against this projection before it is written.
        // Removing the source and announcing the target in this one projection
        // transition guarantees that no replayed state exposes an unowned gap.
        release(payload.sourceIntentId());
        announce(target);
    }

    private void unwind(org.synesis.coordination.domain.task.CompletionUnwoundPayload payload) throws IOException {
        WorkIntent replacement = payload.replacementIntent();
        WorkIntent current = intents.get(replacement.intentId());
        if (current == null || current.status() != WorkIntent.Status.ANNOUNCED) {
            throw new IOException("UNWIND_SOURCE_NOT_ACTIVE");
        }
        if (!current.participant().equals(replacement.participant())
                || replacement.version() <= current.version()
                || !current.selectors().equals(replacement.selectors())) {
            throw new IOException("UNWIND_EPOCH_OR_SCOPE_MISMATCH");
        }
        intents.put(replacement.intentId(), replacement);
        Participant previous = participantHistory.get(replacement.participant());
        if (previous != null) {
            participantHistory.put(replacement.participant(), new Participant(previous.id(), previous.provider(),
                    replacement.goal(), Participant.State.ACTIVE, previous.lastVerifiedActivity(),
                    replacement.selectors(), previous.recoverySnapshotReference()));
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
        if (current.state() == Participant.State.REVOKED) {
            throw new IOException("SESSION_EPOCH_FENCED");
        }
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.ACTIVE, timestamp, current.claims()));
    }

    private void handoff(CollaborationCodec.Handoff handoff) throws IOException {
        WorkIntent current = intents.get(handoff.intentId());
        if (current == null) throw new IOException("INTENT_NOT_FOUND");
        if (current.version() != handoff.expectedVersion()) throw new IOException("CLAIM_EPOCH_STALE");
        Participant target = participantHistory.get(handoff.target());
        if (target == null || target.state() != Participant.State.ACTIVE) throw new IOException("HANDOFF_TARGET_NOT_ACTIVE");
        WorkIntent transferred = new WorkIntent(current.intentId(), current.projectId(), handoff.target(), current.provider(),
                current.taskId(), current.goal(), current.acceptance(), current.baseCommit(), current.selectors(),
                current.version() + 1, current.workGroupId(), current.authorityLineageId(), current.status());
        intents.put(current.intentId(), transferred);
        Participant previous = participantHistory.get(current.participant());
        if (previous != null) {
            participantHistory.put(current.participant(), new Participant(previous.id(), previous.provider(),
                    previous.goal(), Participant.State.COMPLETED, previous.lastVerifiedActivity(), List.of()));
        }
        participantHistory.put(handoff.target(), new Participant(target.id(), target.provider(), target.goal(),
                Participant.State.ACTIVE, target.lastVerifiedActivity(), transferred.selectors(), null));
    }

    private void suspended(String participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) throw new IOException("PARTICIPANT_NOT_FOUND");
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.SUSPENDED, current.lastVerifiedActivity(), current.claims(),
                current.recoverySnapshotReference()));
    }

    private void recoveryHeld(CollaborationCodec.Recovery recovery) throws IOException {
        String participant = recovery.participant();
        Participant current = participantHistory.get(participant);
        if (current == null) throw new IOException("PARTICIPANT_NOT_FOUND");
        if (current.state() != Participant.State.SUSPENDED && current.state() != Participant.State.RECOVERY_HELD) {
            throw new IOException("RECOVERY_NOT_SUSPENDED");
        }
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.RECOVERY_HELD, current.lastVerifiedActivity(), current.claims(),
                recovery.snapshotReference()));
    }

    private void continued(CollaborationCodec.Continuation continuation) throws IOException {
        WorkIntent source = intents.get(continuation.sourceIntentId());
        if (source == null || !source.participant().equals(continuation.sourceParticipant())) {
            throw new IOException("CONTINUATION_SOURCE_NOT_FOUND");
        }
        if (source.version() != continuation.expectedEpoch()) throw new IOException("CLAIM_EPOCH_STALE");
        Participant sourceParticipant = participantHistory.get(continuation.sourceParticipant());
        if (sourceParticipant == null || sourceParticipant.state() != Participant.State.RECOVERY_HELD) {
            throw new IOException("RECOVERY_NOT_HELD");
        }
        WorkIntent target = continuation.targetIntent();
        if (!target.participant().equals(continuation.targetParticipant())
                || !target.workGroupId().equals(source.workGroupId())
                || !target.selectors().equals(source.selectors())) {
            throw new IOException("CONTINUATION_TARGET_INVALID");
        }
        if (intents.containsKey(target.intentId())) throw new IOException("CONTINUATION_TARGET_EXISTS");
        intents.remove(source.intentId());
        intents.put(target.intentId(), target);
        participantHistory.put(continuation.sourceParticipant(), new Participant(sourceParticipant.id(),
                sourceParticipant.provider(), sourceParticipant.goal(), Participant.State.DETACHED,
                sourceParticipant.lastVerifiedActivity(), List.of(), sourceParticipant.recoverySnapshotReference()));
        String targetId = continuation.targetParticipant().startsWith("agt_")
                ? continuation.targetParticipant() : "agt_" + continuation.targetParticipant();
        participantHistory.put(continuation.targetParticipant(), new Participant(targetId, target.provider(),
                target.goal(), Participant.State.ACTIVE, System.currentTimeMillis(), target.selectors(), null));
    }

    private void revoked(String participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) throw new IOException("PARTICIPANT_NOT_FOUND");
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.REVOKED, current.lastVerifiedActivity(), List.of(), current.recoverySnapshotReference()));
        intents.entrySet().removeIf(entry -> entry.getValue().participant().equals(participant));
    }

    private void acknowledge(UUID itemId) {
        acknowledgedInboxItems.add(itemId);
    }

    private void cancelled(String participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) throw new IOException("PARTICIPANT_NOT_FOUND");
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.CANCELLED, current.lastVerifiedActivity(), List.of(), current.recoverySnapshotReference()));
        intents.entrySet().removeIf(entry -> entry.getValue().participant().equals(participant));
    }

    private void detached(String participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) throw new IOException("PARTICIPANT_NOT_FOUND");
        participantHistory.put(participant, new Participant(current.id(), current.provider(), current.goal(),
                Participant.State.DETACHED, current.lastVerifiedActivity(), List.of(),
                current.recoverySnapshotReference()));
        intents.entrySet().removeIf(entry -> entry.getValue().participant().equals(participant));
    }
}
