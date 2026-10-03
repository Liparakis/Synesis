package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.task.CompletionUnwoundPayload;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic projection of active work intents and resource claims.
 */
@SuppressWarnings({"DuplicatedCode", "ExtractMethodRecommender"})
public final class CollaborationProjection {

    private final Map<WorkIntentId, WorkIntent> intents = new LinkedHashMap<>();
    private final Map<RequestId, CoordinationRequest> requests = new LinkedHashMap<>();
    private final Map<WorkIntentId, NoChangeCompletion> noChangeCompletions = new LinkedHashMap<>();
    private final Map<ProviderSessionId, ProviderSessionTerminalPayload> terminalSessions = new LinkedHashMap<>();
    private final Map<ParticipantId, Participant> participantHistory = new LinkedHashMap<>();
    private final Map<WorkIntentId, WorkIntentId> successorIntents = new LinkedHashMap<>();
    private final Set<RequestId> acknowledgedInboxItems = new HashSet<>();
    private boolean activated;

    /**
     * Creates an empty collaboration projection.
     */
    public CollaborationProjection() {
    }

    /**
     * Rejects mutations from a participant incarnation already fenced as revoked.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void rejectRevoked(Participant participant) throws IOException {
        if (participant.state() == Participant.State.REVOKED) {
            throw new CoordinationException("SESSION_EPOCH_FENCED");
        }
    }

    /**
     * Returns whether any selector pair overlaps.
     */
    private static boolean selectorsOverlap(List<ResourceSelector> left, List<ResourceSelector> right) {
        return left.stream().anyMatch(existing -> right.stream().anyMatch(existing::overlaps));
    }

    /**
     * Checks that the authorized successor preserves the source lane's immutable contract.
     *
     * @param source       recovery-held source lane
     * @param target       proposed successor lane
     * @param continuation source and target identity binding
     * @throws IOException when the successor does not preserve the lane contract
     */
    private static void validateContinuationTarget(WorkIntent source, WorkIntent target, LaneContinuation continuation)
            throws IOException {
        boolean sameLaneContract = target.participantIdentity().equals(continuation.targetOwner()) && target.project()
                .equals(source.project()) && target.task().equals(source.task()) && target.goal().equals(source.goal())
                && target.acceptance().equals(source.acceptance()) && target.group().equals(source.group())
                && target.lineage().equals(source.lineage())
                && target.intentVersion().value() == source.intentVersion().value() + 1L
                && target.role() == source.role() && target.reviewTargetSelectors()
                .equals(source.reviewTargetSelectors()) && target.knownDependencies().equals(source.knownDependencies())
                && target.selectors().equals(source.selectors());
        if (!sameLaneContract) {
            throw new CoordinationException("CONTINUATION_TARGET_INVALID");
        }
    }

    /**
     * Rebinds one pending review obligation when its producer or reviewer role continues.
     *
     * @param source       recovery-held source lane
     * @param target       authorized successor lane
     * @param continuation participant identities for the transition
     * @param request      current coordination request
     * @return updated request, or the original request when unrelated
     */
    private static CoordinationRequest retargetReviewRequest(WorkIntent source,
            WorkIntent target,
            LaneContinuation continuation,
            CoordinationRequest request) {
        if (source.role() == WorkIntent.Role.PRODUCER && request.status() == CoordinationRequest.Status.PENDING
                && request.kind() == CoordinationRequest.Kind.REVIEW && request.targetIdentity()
                .equals(continuation.sourceOwner()) && request.conflictingIntent().equals(source.identity())) {
            return new CoordinationRequest(request.requestId(),
                    request.projectId(),
                    request.requester(),
                    continuation.targetOwner().value(),
                    target.intentId(),
                    request.kind(),
                    request.proposal(),
                    request.status(),
                    request.snapshotId());
        }
        if (source.role() == WorkIntent.Role.REVIEWER && (request.status() == CoordinationRequest.Status.PENDING
                || request.status() == CoordinationRequest.Status.ACCEPTED)
                && request.kind() == CoordinationRequest.Kind.REVIEW && request.requesterIdentity()
                .equals(continuation.sourceOwner())) {
            return new CoordinationRequest(request.requestId(),
                    request.projectId(),
                    continuation.targetOwner().value(),
                    request.target(),
                    request.conflictingIntentId(),
                    request.kind(),
                    request.proposal(),
                    request.status(),
                    request.snapshotId());
        }
        return request;
    }

    /**
     * Applies one collaboration event.
     *
     * @param event event
     * @throws IOException malformed transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> {
                activated = true;
                announce(CollaborationCodec.decodeIntent(event.payload()));
            }
            case WORK_INTENT_RELEASED -> {
                if (CollaborationCodec.isNoChangeCompletion(event.payload())) {
                    complete(CollaborationCodec.decodeNoChangeCompletion(event.payload()), event.sequence());
                } else {
                    release(new WorkIntentId(CollaborationCodec.decodeRelease(event.payload())));
                }
            }
            case COORDINATION_REQUESTED -> request(CollaborationCodec.decodeRequest(event.payload()));
            case COORDINATION_RESPONDED -> respond(CollaborationCodec.decodeResponse(event.payload()));
            case PARTICIPANT_HEARTBEAT ->
                    heartbeat(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())),
                            event.createdAtEpochMillis());
            case CLAIM_HANDOFF_ACCEPTED -> handoff(CollaborationCodec.decodeHandoff(event.payload()));
            case REPAIR_LANE_CREATED -> repair(RepairLanePayload.decode(event.payload()));
            case COMPLETION_UNWOUND -> unwind(CompletionUnwoundPayload.decode(event.payload()));
            case PARTICIPANT_ABANDONED, PARTICIPANT_SUSPENDED ->
                    suspended(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())));
            case RECOVERY_SNAPSHOT_HELD -> recoveryHeld(CollaborationCodec.decodeRecovery(event.payload()));
            case PARTICIPANT_REVOKED -> revoked(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())));
            case INBOX_ITEM_ACKNOWLEDGED ->
                    acknowledge(new RequestId(CollaborationCodec.decodeUuidText(event.payload())));
            case PARTICIPANT_CANCELLED ->
                    cancelled(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())));
            case LANE_CONTINUATION_ACCEPTED -> continued(CollaborationCodec.decodeContinuation(event.payload()));
            case PARTICIPANT_DETACHED ->
                    detached(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())));
            case REVIEW_VALIDATION_RECORDED -> review(ReviewValidationPayload.decode(event.payload()));
            case PROVIDER_SESSION_TERMINALIZED ->
                    terminalize(ProviderSessionTerminalPayload.decode(event.payload()), event.sequence());
            default -> {
            }
        }
    }

    /**
     * Validates one collaboration event without mutation.
     *
     * @param event event
     * @throws IOException invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        CollaborationProjection candidate = new CollaborationProjection();
        candidate.intents.putAll(intents);
        candidate.requests.putAll(requests);
        candidate.noChangeCompletions.putAll(noChangeCompletions);
        candidate.terminalSessions.putAll(terminalSessions);
        candidate.participantHistory.putAll(participantHistory);
        candidate.successorIntents.putAll(successorIntents);
        candidate.acknowledgedInboxItems.addAll(acknowledgedInboxItems);
        candidate.activated = activated;
        candidate.apply(event);
    }

    /**
     * Returns an intent by identifier.
     *
     * @param id intent ID
     * @return intent
     */
    public synchronized Optional<WorkIntent> intent(UUID id) {
        return intent(new WorkIntentId(CoordinationNullException.requireNonNull(id, "intent ID")));
    }

    /**
     * Returns an intent by its typed work-intent identity.
     *
     * @param identity intent identity
     * @return intent when known
     */
    public synchronized Optional<WorkIntent> intent(WorkIntentId identity) {
        return Optional.ofNullable(intents.get(CoordinationNullException.requireNonNull(identity, "intent ID")));
    }

    /**
     * Resolves the immediate successor of a historical intent, when one exists.
     *
     * @param sourceIntentId predecessor intent
     * @return successor intent ID
     */
    public synchronized Optional<UUID> successorIntent(UUID sourceIntentId) {
        return successorIntent(new WorkIntentId(CoordinationNullException.requireNonNull(sourceIntentId,
                "source intent ID"))).map(WorkIntentId::value);
    }

    /**
     * Returns the durable successor using the predecessor's typed identity.
     *
     * @param sourceIntentId predecessor intent identity
     * @return successor identity when present
     */
    public synchronized Optional<WorkIntentId> successorIntent(WorkIntentId sourceIntentId) {
        return Optional.ofNullable(successorIntents.get(CoordinationNullException.requireNonNull(sourceIntentId,
                "source intent ID")));
    }

    /**
     * Resolves the immediate predecessor of a historical successor intent, when one exists.
     *
     * @param successorIntentId successor intent
     * @return predecessor intent ID
     */
    public synchronized Optional<UUID> predecessorIntent(UUID successorIntentId) {
        return predecessorIntent(new WorkIntentId(CoordinationNullException.requireNonNull(successorIntentId,
                "successor intent ID"))).map(WorkIntentId::value);
    }

    /**
     * Resolves the immediate predecessor using a typed successor identity.
     *
     * @param successorIntentId successor intent identity
     * @return predecessor identity when present
     */
    public synchronized Optional<WorkIntentId> predecessorIntent(WorkIntentId successorIntentId) {
        WorkIntentId successor = CoordinationNullException.requireNonNull(successorIntentId, "successor intent ID");
        return successorIntents.entrySet().stream().filter(entry -> entry.getValue().equals(successor))
                .map(Map.Entry::getKey).findFirst();
    }

    /**
     * Returns all active intents.
     *
     * @return immutable intents
     */
    public synchronized List<WorkIntent> activeIntents() {
        return List.copyOf(intents.values());
    }

    /**
     * Returns active participant projections without connection or worktree details.
     *
     * @return participant projections
     */
    public synchronized List<Participant> participants() {
        return List.copyOf(participantHistory.values());
    }

    /**
     * Returns whether this project has durable collaboration enforcement enabled.
     *
     * @return true after the first intent event
     */
    public synchronized boolean activated() {
        return activated;
    }

    /**
     * Returns all durable coordination requests.
     *
     * @return requests
     */
    public synchronized List<CoordinationRequest> requests() {
        return List.copyOf(requests.values());
    }

    /**
     * Returns durable no-change completion evidence for an intent, when present.
     *
     * @param intentId intent identifier
     * @return completion evidence, if the intent was explicitly completed
     */
    public synchronized Optional<NoChangeCompletion> noChangeCompletion(UUID intentId) {
        return noChangeCompletion(new WorkIntentId(CoordinationNullException.requireNonNull(intentId, "intent ID")));
    }

    /**
     * Returns durable no-change evidence for its typed work-intent identity.
     *
     * @param intentId intent identity
     * @return completion evidence when present
     */
    public synchronized Optional<NoChangeCompletion> noChangeCompletion(WorkIntentId intentId) {
        return Optional.ofNullable(noChangeCompletions.get(CoordinationNullException.requireNonNull(intentId,
                "intent ID")));
    }

    /**
     * Returns all durable no-change completion evidence.
     *
     * @return immutable completion evidence
     */
    public synchronized List<NoChangeCompletion> noChangeCompletions() {
        return List.copyOf(noChangeCompletions.values());
    }

    /**
     * Returns whether an exact provider session has an irreversible terminal fence.
     *
     * @param sessionId exact provider session identity
     * @return true when terminalized
     */
    public synchronized boolean isSessionTerminal(String sessionId) {
        return isSessionTerminal(new ProviderSessionId(CoordinationNullException.requireNonNull(sessionId,
                "session ID")));
    }

    /**
     * Returns whether an exact typed provider session has an irreversible terminal fence.
     *
     * @param sessionId provider session identity
     * @return true when terminalized
     */
    public synchronized boolean isSessionTerminal(ProviderSessionId sessionId) {
        return terminalSessions.containsKey(CoordinationNullException.requireNonNull(sessionId, "session ID"));
    }

    /**
     * Returns whether a typed participant incarnation is already fenced by a terminal session.
     *
     * @param participant participant identity
     * @return true when fenced
     */
    public synchronized boolean isParticipantTerminal(ParticipantId participant) {
        return terminalSessions.values().stream().anyMatch(payload -> payload.participantIdentity()
                .equals(CoordinationNullException.requireNonNull(participant, "participant")));
    }

    /**
     * Returns whether an inbox item has been acknowledged.
     *
     * @param itemId server-issued item identifier
     * @return true when acknowledged
     */
    public synchronized boolean inboxAcknowledged(UUID itemId) {
        return inboxAcknowledged(new RequestId(CoordinationNullException.requireNonNull(itemId, "item ID")));
    }

    /**
     * Returns whether a typed server-issued inbox item has been acknowledged.
     *
     * @param itemId inbox item identity
     * @return true when acknowledged
     */
    public synchronized boolean inboxAcknowledged(RequestId itemId) {
        return acknowledgedInboxItems.contains(CoordinationNullException.requireNonNull(itemId, "item ID"));
    }

    /**
     * Resolves the internal key for an opaque typed participant handle.
     *
     * @param handle participant identity
     * @return internal participant key, when present
     */
    public synchronized Optional<ParticipantId> participantKey(ParticipantId handle) {
        ParticipantId identity = CoordinationNullException.requireNonNull(handle, "participant handle");
        return participantHistory.entrySet().stream()
                .filter(entry -> entry.getKey().equals(identity) || entry.getValue().identity().equals(identity))
                .map(Map.Entry::getKey).findFirst();
    }

    /**
     * Returns the internal recovery reference for a participant.
     *
     * @param handle participant handle
     * @return opaque snapshot reference when held
     */
    public synchronized Optional<String> recoverySnapshotReference(String handle) {
        return handle == null ? Optional.empty()
                : recoverySnapshotReference(new ParticipantId(handle)).map(SnapshotReference::value);
    }

    /**
     * Returns a typed recovery snapshot reference for a participant.
     *
     * @param handle participant identity
     * @return snapshot reference when held
     */
    public synchronized Optional<SnapshotReference> recoverySnapshotReference(ParticipantId handle) {
        return participantKey(CoordinationNullException.requireNonNull(handle, "participant handle")).map(
                participantHistory::get).flatMap(Participant::recoverySnapshotIdentity);
    }

    /**
     * Returns a participant lifecycle state by opaque handle or projection key.
     *
     * @param handle participant handle
     * @return lifecycle state when present
     */
    public synchronized Optional<Participant.State> participantState(String handle) {
        return handle == null ? Optional.empty() : participantState(new ParticipantId(handle));
    }

    /**
     * Returns a lifecycle state by typed participant identity.
     *
     * @param handle participant identity
     * @return lifecycle state when present
     */
    public synchronized Optional<Participant.State> participantState(ParticipantId handle) {
        return participantKey(CoordinationNullException.requireNonNull(handle, "participant handle")).map(
                participantHistory::get).map(Participant::state);
    }

    /**
     * Finds claims overlapping any requested selector.
     *
     * @param selectors selectors
     * @return conflicts
     */
    public synchronized List<ClaimConflict> conflicts(List<ResourceSelector> selectors) {
        List<ClaimConflict> result = new ArrayList<>();
        for (WorkIntent intent : intents.values()) {
            for (ResourceSelector existing : intent.selectors()) {
                for (ResourceSelector requested : selectors) {
                    if (existing.overlaps(requested)) {
                        result.add(new ClaimConflict(intent.participantIdentity(), intent.identity(), existing));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Adds an intent after checking participant lifecycle, duplicate identity, and claim conflicts.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void announce(WorkIntent intent) throws IOException {
        if (isParticipantTerminal(intent.participantIdentity())) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        Participant existing = participantHistory.get(intent.participantIdentity());
        if (existing != null && existing.state() == Participant.State.REVOKED) {
            throw new CoordinationException("SESSION_EPOCH_FENCED");
        }
        if (intents.containsKey(intent.identity()) || !conflicts(intent.selectors()).isEmpty()) {
            throw new CoordinationException("OVERLAPPING_CLAIM");
        }
        intents.put(intent.identity(), intent);
        refreshParticipant(intent.participantIdentity(), Participant.State.ACTIVE, intent);
    }

    /**
     * Applies the release lifecycle transition after validating its authority.
     *
     * @param id exact work-intent identity released by the event
     * @throws IOException when payload validation or durable I/O fails
     */
    private void release(WorkIntentId id) throws IOException {
        WorkIntent released = intents.remove(CoordinationNullException.requireNonNull(id, "intent ID"));
        if (released == null) {
            throw new CoordinationException("INTENT_NOT_FOUND");
        }
        refreshParticipant(released.participantIdentity(), Participant.State.COMPLETED, null);
    }

    /**
     * Recomputes active claims while retaining prior activity and recovery evidence.
     */
    private void refreshParticipant(ParticipantId participant, Participant.State emptyState, WorkIntent preferred) {
        Participant previous = participantHistory.get(participant);
        List<WorkIntent> owned = intents.values().stream()
                .filter(intent -> intent.participantIdentity().equals(participant)).toList();
        if (owned.isEmpty()) {
            if (previous != null) {
                participantHistory.put(participant,
                        new Participant(previous.id(),
                                previous.provider(),
                                previous.goal(),
                                emptyState,
                                previous.lastVerifiedActivity(),
                                List.of(),
                                previous.recoverySnapshotReference()));
            }
            return;
        }
        WorkIntent primary =
                preferred != null && preferred.participantIdentity().equals(participant) ? preferred : owned.getLast();
        List<ResourceSelector> claims = owned.stream().flatMap(intent -> intent.selectors().stream()).distinct()
                .toList();
        String opaqueId = previous == null ? participant.value().startsWith("agt_") ? participant.value()
                : "agt_" + participant.value() : previous.id();
        participantHistory.put(participant,
                new Participant(opaqueId,
                        primary.provider(),
                        primary.goal(),
                        Participant.State.ACTIVE,
                        previous == null ? 0L : previous.lastVerifiedActivity(),
                        claims,
                        previous == null ? null : previous.recoverySnapshotReference()));
    }

    /**
     * Replaces an active source intent with its validated repair successor.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void repair(RepairLanePayload payload) throws IOException {
        WorkIntentId sourceIdentity = payload.sourceIntentIdentity();
        WorkIntent source = intents.get(sourceIdentity);
        if (source == null) {
            throw new CoordinationException("REPAIR_SOURCE_NOT_FOUND");
        }
        WorkIntent target = payload.targetIntent();
        if (target.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("REPAIR_TARGET_NOT_ANNOUNCED");
        }
        if (!source.project().equals(target.project()) || !source.group().equals(target.group())) {
            throw new CoordinationException("REPAIR_WORK_GROUP_MISMATCH");
        }
        if (!source.lineage().equals(target.lineage())) {
            throw new CoordinationException("REPAIR_AUTHORITY_LINEAGE_MISMATCH");
        }
        if (source.identity().equals(target.identity()) || source.participantIdentity()
                .equals(target.participantIdentity())) {
            throw new CoordinationException("REPAIR_TARGET_MUST_BE_DISTINCT");
        }
        if (!source.selectors().equals(target.selectors())) {
            throw new CoordinationException("REPAIR_SCOPE_MISMATCH");
        }
        if (payload.snapshotIdentity().isPresent()) {
            if (payload.expectedControlHeadIdentity().isEmpty() || payload.sourceClaimEpochIdentity()
                    .filter(source.claimEpoch()::equals).isEmpty() || payload.targetClaimEpochIdentity()
                    .filter(target.claimEpoch()::equals).isEmpty()
                    || target.intentVersion().value() != source.intentVersion().value() + 1L) {
                throw new CoordinationException("REPAIR_EPOCH_MISMATCH");
            }
        }
        if (intents.values().stream()
                .anyMatch(intent -> !intent.identity().equals(source.identity()) && intent.participantIdentity()
                        .equals(target.participantIdentity()))) {
            throw new CoordinationException("REPAIR_TARGET_ALREADY_ACTIVE");
        }

        // The event is validated against this projection before it is written.
        // Removing the source and announcing the target in this one projection
        // transition guarantees that no replayed state exposes an unowned gap.
        release(sourceIdentity);
        announce(target);
    }

    /**
     * Restores the prepared replacement intent after completion is unwound.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void unwind(CompletionUnwoundPayload payload) throws IOException {
        WorkIntent replacement = payload.replacementIntent();
        WorkIntent current = intents.get(replacement.identity());
        if (current == null || current.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("UNWIND_SOURCE_NOT_ACTIVE");
        }
        if (!current.participant().equals(replacement.participant()) || replacement.version() <= current.version()
                || !current.selectors().equals(replacement.selectors())) {
            throw new CoordinationException("UNWIND_EPOCH_OR_SCOPE_MISMATCH");
        }
        intents.put(replacement.identity(), replacement);
        Participant previous = participantHistory.get(replacement.participantIdentity());
        if (previous != null) {
            participantHistory.put(replacement.participantIdentity(),
                    new Participant(previous.id(),
                            previous.provider(),
                            replacement.goal(),
                            Participant.State.ACTIVE,
                            previous.lastVerifiedActivity(),
                            replacement.selectors(),
                            previous.recoverySnapshotReference()));
        }
    }

    /**
     * Records a unique coordination request whose referenced intent exists.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void request(CoordinationRequest request) throws IOException {
        if (isParticipantTerminal(request.requesterIdentity()) || isParticipantTerminal(request.targetIdentity())) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        if (requests.containsKey(request.identity())) {
            throw new CoordinationException("REQUEST_EXISTS");
        }
        if (!intents.containsKey(request.conflictingIntent())) {
            throw new CoordinationException("INTENT_NOT_FOUND");
        }
        requests.put(request.identity(), request);
    }

    /**
     * Applies an authorized response to the matching pending coordination request.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void respond(CoordinationResponsePayload response) throws IOException {
        CoordinationRequest current = requests.get(response.request());
        if (current == null) {
            throw new CoordinationException("REQUEST_NOT_FOUND");
        }
        if (current.status() != CoordinationRequest.Status.PENDING) {
            if (current.status() == response.status()) {
                return;
            }
            throw new CoordinationException("REQUEST_ALREADY_RESOLVED");
        }
        requests.put(current.identity(),
                new CoordinationRequest(current.requestId(),
                        current.projectId(),
                        current.requester(),
                        current.target(),
                        current.conflictingIntentId(),
                        current.kind(),
                        response.proposal().isBlank() ? current.proposal() : response.proposal(),
                        response.status(),
                        current.snapshotId()));
    }

    /**
     * Records review validation for the grant and immutable snapshot named by the payload.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void review(ReviewValidationPayload validation) throws IOException {
        if (!"REJECTED".equals(validation.result())) {
            return;
        }
        WorkIntent current = intents.get(new WorkIntentId(validation.targetIntentId()));
        // Keep historical review records replayable when an older event log
        // already terminalized its lane before recording the decision. New
        // reviewed completion rejects this condition at the service boundary;
        // an active exact lane is the only state that creates a continuation.
        if (current == null) {
            return;
        }
        if (!current.workGroupId().equals(validation.workGroupId()) || !current.participant()
                .equals(validation.sourceParticipant()) || !current.taskId().equals(validation.taskId())
                || current.version() != validation.claimEpoch() || current.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("REVIEW_TARGET_STALE");
        }
        WorkIntent correction = new WorkIntent(current.intentId(),
                current.projectId(),
                current.participant(),
                current.provider(),
                current.taskId(),
                current.goal(),
                current.acceptance(),
                current.baseCommit(),
                current.selectors(),
                current.version() + 1L,
                current.workGroupId(),
                current.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                current.role(),
                current.reviewTargetSelectors(),
                current.knownDependencies());
        intents.put(correction.identity(), correction);
        Participant participant = participantHistory.get(current.participantIdentity());
        if (participant == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        participantHistory.put(current.participantIdentity(),
                new Participant(participant.id(),
                        participant.provider(),
                        correction.goal(),
                        Participant.State.ACTIVE,
                        participant.lastVerifiedActivity(),
                        correction.selectors(),
                        participant.recoverySnapshotReference()));
    }

    /**
     * Refreshes verified participant activity without changing ownership or claims.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void heartbeat(ParticipantId participant, long timestamp) throws IOException {
        if (isParticipantTerminal(participant)) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        Participant current = participantHistory.get(participant);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        if (current.state() == Participant.State.REVOKED || current.state() == Participant.State.COMPLETED
                || current.state() == Participant.State.CANCELLED || current.state() == Participant.State.DETACHED) {
            throw new CoordinationException("SESSION_EPOCH_FENCED");
        }
        participantHistory.put(participant,
                new Participant(current.id(),
                        current.provider(),
                        current.goal(),
                        Participant.State.ACTIVE,
                        timestamp,
                        current.claims()));
    }

    /**
     * Transfers an intent to the participant named by the accepted handoff payload.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void handoff(ClaimHandoff handoff) throws IOException {
        WorkIntent current = intents.get(handoff.intent());
        if (current == null) {
            throw new CoordinationException("INTENT_NOT_FOUND");
        }
        if (current.version() != handoff.expectedVersion()) {
            throw new CoordinationException("CLAIM_EPOCH_STALE");
        }
        Participant target = participantHistory.get(handoff.targetParticipant());
        if (target == null || target.state() != Participant.State.ACTIVE) {
            throw new CoordinationException("HANDOFF_TARGET_NOT_ACTIVE");
        }
        WorkIntent transferred = new WorkIntent(current.intentId(),
                current.projectId(),
                handoff.target(),
                current.provider(),
                current.taskId(),
                current.goal(),
                current.acceptance(),
                current.baseCommit(),
                current.selectors(),
                current.version() + 1,
                current.workGroupId(),
                current.authorityLineageId(),
                current.status(),
                current.role(),
                current.reviewTargetSelectors(),
                current.knownDependencies());
        intents.put(current.identity(), transferred);
        Participant previous = participantHistory.get(current.participantIdentity());
        if (previous != null) {
            participantHistory.put(current.participantIdentity(),
                    new Participant(previous.id(),
                            previous.provider(),
                            previous.goal(),
                            Participant.State.COMPLETED,
                            previous.lastVerifiedActivity(),
                            List.of()));
        }
        participantHistory.put(handoff.targetParticipant(),
                new Participant(target.id(),
                        target.provider(),
                        target.goal(),
                        Participant.State.ACTIVE,
                        target.lastVerifiedActivity(),
                        transferred.selectors(),
                        null));
    }

    /**
     * Fences the suspended participant while retaining its durable ownership history.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void suspended(ParticipantId participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        rejectRevoked(current);
        participantHistory.put(participant,
                new Participant(current.id(),
                        current.provider(),
                        current.goal(),
                        Participant.State.SUSPENDED,
                        current.lastVerifiedActivity(),
                        current.claims(),
                        current.recoverySnapshotReference()));
    }

    /**
     * Attaches verified recovery evidence to the participant's existing durable state.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void recoveryHeld(RecoveryEvidence recovery) throws IOException {
        ParticipantId participantId = recovery.participantId();
        Participant current = participantHistory.get(participantId);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        if (current.state() != Participant.State.SUSPENDED && current.state() != Participant.State.RECOVERY_HELD) {
            throw new CoordinationException("RECOVERY_NOT_SUSPENDED");
        }
        participantHistory.put(participantId,
                new Participant(current.id(),
                        current.provider(),
                        current.goal(),
                        Participant.State.RECOVERY_HELD,
                        current.lastVerifiedActivity(),
                        current.claims(),
                        recovery.snapshot().value()));
    }

    /**
     * Applies the source-to-successor transition and updates its dependent coordination state.
     *
     * @param continuation proposed successor continuation
     * @throws IOException when payload validation or durable I/O fails
     */
    private void continued(LaneContinuation continuation) throws IOException {
        if (isParticipantTerminal(continuation.targetOwner())) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        WorkIntent source = requireRecoverySource(continuation);
        WorkIntent target = continuation.targetIntent();
        validateContinuationTarget(source, target, continuation);
        validateContinuationClaimOverlap(source, target);
        if (intents.containsKey(target.identity())) {
            throw new CoordinationException("CONTINUATION_TARGET_EXISTS");
        }
        intents.remove(source.identity());
        intents.put(target.identity(), target);
        successorIntents.put(source.identity(), target.identity());
        retargetReviewRequests(source, target, continuation);
        refreshParticipant(continuation.sourceOwner(), Participant.State.DETACHED, null);
        refreshParticipant(continuation.targetOwner(), Participant.State.ACTIVE, target);
    }

    /**
     * Resolves and validates the exact recovery-held source lane for a continuation.
     *
     * @param continuation proposed successor transition
     * @return authenticated source intent
     * @throws IOException when the source is absent, stale, or not recovery-held
     */
    private WorkIntent requireRecoverySource(LaneContinuation continuation) throws IOException {
        WorkIntent source = intents.get(continuation.sourceIntent());
        if (source == null || !source.participantIdentity().equals(continuation.sourceOwner())) {
            throw new CoordinationException("CONTINUATION_SOURCE_NOT_FOUND");
        }
        if (!source.claimEpoch().equals(continuation.claimEpoch())) {
            throw new CoordinationException("CLAIM_EPOCH_STALE");
        }
        Participant sourceParticipant = participantHistory.get(continuation.sourceOwner());
        if (sourceParticipant == null || sourceParticipant.state() != Participant.State.RECOVERY_HELD) {
            throw new CoordinationException("RECOVERY_NOT_HELD");
        }
        return source;
    }

    /**
     * Rejects a successor whose selectors overlap another active claim by the same participant.
     *
     * @param source recovery-held source lane
     * @param target proposed successor lane
     * @throws IOException when another active claim overlaps the successor scope
     */
    private void validateContinuationClaimOverlap(WorkIntent source, WorkIntent target) throws IOException {
        boolean overlaps = intents.values().stream().filter(intent -> !intent.identity().equals(source.identity()))
                .filter(intent -> intent.participantIdentity().equals(target.participantIdentity()))
                .anyMatch(intent -> selectorsOverlap(intent.selectors(), target.selectors()));
        if (overlaps) {
            throw new CoordinationException("OVERLAPPING_CLAIM");
        }
    }

    /**
     * Moves only review requests whose producer or reviewer identity is being continued.
     *
     * @param source       recovery-held source lane
     * @param target       authorized successor lane
     * @param continuation participant identities for the transition
     */
    private void retargetReviewRequests(WorkIntent source, WorkIntent target, LaneContinuation continuation) {
        requests.replaceAll((requestId, request) -> retargetReviewRequest(source, target, continuation, request));
    }

    /**
     * Applies the revoked lifecycle transition after validating its authority.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void revoked(ParticipantId participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        participantHistory.put(participant,
                new Participant(current.id(),
                        current.provider(),
                        current.goal(),
                        Participant.State.REVOKED,
                        current.lastVerifiedActivity(),
                        List.of(),
                        current.recoverySnapshotReference()));
        intents.entrySet().removeIf(entry -> entry.getValue().participantIdentity().equals(participant));
    }

    /**
     * Marks the identified inbox item as acknowledged.
     */
    private void acknowledge(RequestId itemId) {
        acknowledgedInboxItems.add(itemId);
    }

    /**
     * Marks the participant cancelled and removes its active intents.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void cancelled(ParticipantId participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        rejectRevoked(current);
        participantHistory.put(participant,
                new Participant(current.id(),
                        current.provider(),
                        current.goal(),
                        Participant.State.CANCELLED,
                        current.lastVerifiedActivity(),
                        List.of(),
                        current.recoverySnapshotReference()));
        intents.entrySet().removeIf(entry -> entry.getValue().participantIdentity().equals(participant));
    }

    /**
     * Records clean detachment and removes the participant's active intents.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void detached(ParticipantId participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        rejectRevoked(current);
        participantHistory.put(participant,
                new Participant(current.id(),
                        current.provider(),
                        current.goal(),
                        Participant.State.DETACHED,
                        current.lastVerifiedActivity(),
                        List.of(),
                        current.recoverySnapshotReference()));
        intents.entrySet().removeIf(entry -> entry.getValue().participantIdentity().equals(participant));
    }

    /**
     * Fences a provider session using its exact optimistic revision and terminal evidence.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void terminalize(ProviderSessionTerminalPayload payload, long eventSequence) throws IOException {

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

        if (terminalSessions.values().stream()
                .anyMatch(existing -> existing.participantIdentity().equals(payload.participantIdentity()))) {
            throw new CoordinationException("TERMINAL_PARTICIPANT_CONFLICT");
        }
        terminalSessions.put(payload.sessionIdentity(), payload);
    }

    /**
     * Applies the complete lifecycle transition after validating its authority.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void complete(NoChangeCompletion completion, long eventSequence) throws IOException {
        NoChangeCompletion previous = noChangeCompletions.get(completion.intentIdentity());
        if (previous != null) {
            if (!previous.equals(completion)) {
                throw new CoordinationException("NO_CHANGE_COMPLETION_CONFLICT");
            }
            return;
        }
        if (completion.expectedRevision() != eventSequence - 1L) {
            throw new CoordinationException("COMPLETION_REVISION_STALE");
        }
        WorkIntent current = intents.get(completion.intentIdentity());
        if (current == null) {
            throw new CoordinationException("INTENT_NOT_FOUND");
        }
        if (!current.workGroupId().equals(completion.workGroupId()) || !current.participant()
                .equals(completion.participant()) || !current.provider().equals(completion.provider())
                || !current.authorityLineageId().equals(completion.authorityLineageId())
                || current.version() != completion.claimEpoch() || !current.baseCommit()
                .equals(completion.workspaceCommit())) {
            throw new CoordinationException("NO_CHANGE_COMPLETION_BINDING_MISMATCH");
        }
        intents.remove(completion.intentIdentity());
        noChangeCompletions.put(completion.intentIdentity(), completion);
        refreshParticipant(current.participantIdentity(), Participant.State.COMPLETED, null);
    }
}
