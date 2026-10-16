package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionUnwoundPayload;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic projection of active work intents and resource claims.
 */
public final class CollaborationProjection {

    private final Map<WorkIntentId, WorkIntent> intents = new LinkedHashMap<>();
    /** Each lane that has ended, by lineage, as it was then. Kept only to say who a past lane was. */
    private final Map<UUID, WorkIntent> endedLanes = new LinkedHashMap<>();
    private final CoordinationRequests requests;
    private final Map<WorkIntentId, NoChangeCompletion> noChangeCompletions = new LinkedHashMap<>();
    private final ProviderSessionFences sessionFences;
    private final Map<ParticipantId, Participant> participantHistory = new LinkedHashMap<>();
    /** The node that signed the entry that first put each participant on the log; see {@link #introducingNode}. */
    private final Map<ParticipantId, String> introducedBy = new LinkedHashMap<>();
    private final Map<WorkIntentId, WorkIntentId> successorIntents = new LinkedHashMap<>();
    private final Set<WorkGroupId> groupsWithLanes = new LinkedHashSet<>();
    private boolean activated;
    /**
     * Sequence of the newest applied event other than a heartbeat. A completion envelope stays
     * fresh across heartbeats, which record liveness only, and goes stale on anything else.
     */
    private long lastStateChangeSequence;

    /**
     * Creates an empty collaboration projection.
     */
    public CollaborationProjection() {
        requests = new CoordinationRequests();
        sessionFences = new ProviderSessionFences();
    }

    private CollaborationProjection(CollaborationProjection source) {
        intents.putAll(source.intents);
        endedLanes.putAll(source.endedLanes);
        requests = new CoordinationRequests(source.requests);
        noChangeCompletions.putAll(source.noChangeCompletions);
        sessionFences = new ProviderSessionFences(source.sessionFences);
        participantHistory.putAll(source.participantHistory);
        introducedBy.putAll(source.introducedBy);
        successorIntents.putAll(source.successorIntents);
        groupsWithLanes.addAll(source.groupsWithLanes);
        activated = source.activated;
        lastStateChangeSequence = source.lastStateChangeSequence;
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
            case WORK_INTENT_SCOPE_EXTENDED -> extendScope(WorkIntentScopeExtension.decode(event.payload()));
            case WORK_INTENT_RELEASED -> {
                if (CollaborationCodec.isNoChangeCompletion(event.payload())) {
                    complete(CollaborationCodec.decodeNoChangeCompletion(event.payload()), event.sequence());
                } else {
                    release(new WorkIntentId(CollaborationCodec.decodeRelease(event.payload())));
                }
            }
            case COORDINATION_REQUESTED -> request(CollaborationCodec.decodeRequest(event.payload()));
            case COORDINATION_RESPONDED -> requests.respond(CollaborationCodec.decodeResponse(event.payload()));
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
                    requests.acknowledge(new RequestId(CollaborationCodec.decodeUuidText(event.payload())));
            case PARTICIPANT_CANCELLED ->
                    cancelled(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())));
            case LANE_CONTINUATION_ACCEPTED -> continued(CollaborationCodec.decodeContinuation(event.payload()));
            case PARTICIPANT_DETACHED ->
                    detached(new ParticipantId(CollaborationCodec.decodeHeartbeat(event.payload())));
            case REVIEW_VALIDATION_RECORDED -> review(ReviewValidationPayload.decode(event.payload()));
            case INTEGRATION_VALIDATION_REFUSED -> validationRefused(ValidationRefusalPayload.decode(event.payload()));
            case PROVIDER_SESSION_TERMINALIZED ->
                    sessionFences.terminalize(ProviderSessionTerminalPayload.decode(event.payload()),
                            event.sequence());
            default -> {
            }
        }
        recordIntroducer(event);
        if (!event.type().recordsNoStateChange()) {
            lastStateChangeSequence = event.sequence();
        }
    }

    /**
     * Remembers which node signed the entry that first put a participant on the log: an announcement, a repair
     * lane or a continuation. Later entries acting for that participant must come from the same node
     * ({@link AppendAdmission}).
     */
    private void recordIntroducer(PredictionEvent event) throws IOException {
        WorkIntent introduced = switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> CollaborationCodec.decodeIntent(event.payload());
            case REPAIR_LANE_CREATED -> RepairLanePayload.decode(event.payload()).targetIntent();
            case LANE_CONTINUATION_ACCEPTED -> CollaborationCodec.decodeContinuation(event.payload()).targetIntent();
            default -> null;
        };
        if (introduced != null) {
            introducedBy.putIfAbsent(introduced.participantIdentity(), event.actorNodeId());
        }
    }

    /**
     * Returns the node that signed the entry that introduced a participant.
     *
     * @param handle participant handle as an entry names it
     * @return the introducing node; empty when the participant was never introduced on this log
     */
    public synchronized Optional<String> introducingNode(ParticipantId handle) {
        return Optional.ofNullable(introducedBy.get(handle))
                .or(() -> participantKey(handle).map(introducedBy::get));
    }

    /**
     * Validates one collaboration event without mutation.
     *
     * @param event event
     * @throws IOException invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        new CollaborationProjection(this).apply(event);
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
     * Returns whether any lane was ever announced into a work group. A group can be created ahead
     * of its lanes, and one that never receives a lane holds no work.
     *
     * @param group work-group identity
     * @return whether a lane was announced into the group
     */
    public synchronized boolean groupHasHadLane(WorkGroupId group) {
        return groupsWithLanes.contains(CoordinationNullException.requireNonNull(group, "work group ID"));
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
     * Returns a lane that has ended, as it was when it ended. It says who a past lane was, for
     * whoever reads what that lane said or did; nothing a lane may do is derived from it.
     *
     * @param lineageId authority lineage of the lane
     * @return its last intent, or empty when no lane of that lineage has ended
     */
    public synchronized Optional<WorkIntent> endedLane(UUID lineageId) {
        return Optional.ofNullable(endedLanes.get(lineageId));
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
        return requests.all();
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
        return sessionFences.isSessionTerminal(sessionId);
    }

    /**
     * Returns whether a typed participant incarnation is already fenced by a terminal session.
     *
     * @param participant participant identity
     * @return true when fenced
     */
    public synchronized boolean isParticipantTerminal(ParticipantId participant) {
        return sessionFences.isParticipantTerminal(participant);
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
        return requests.isAcknowledged(CoordinationNullException.requireNonNull(itemId, "item ID"));
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
        return conflicts(selectors, null);
    }

    /**
     * Finds conflicts while excluding only the exact intent being amended.
     *
     * @param selectors requested selectors
     * @param excludedIntent exact active intent whose claims are being extended, or {@code null}
     * @return conflicts owned by other intents
     */
    public synchronized List<ClaimConflict> conflicts(List<ResourceSelector> selectors, WorkIntentId excludedIntent) {
        List<ClaimConflict> result = new ArrayList<>();
        for (WorkIntent intent : intents.values()) {
            if (intent.identity().equals(excludedIntent)) {
                continue;
            }
            for (ResourceSelector existing : intent.selectors()) {
                for (ResourceSelector requested : selectors) {
                    if (existing.overlaps(requested)) {
                        result.add(new ClaimConflict(intent, existing));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Applies a fenced scope amendment without changing lane identity or authority lineage.
     *
     * @param extension exact amended intent and source epoch
     * @throws IOException when the source is stale or the amendment would weaken ownership
     */
    private void extendScope(WorkIntentScopeExtension extension) throws IOException {
        WorkIntent amended = extension.intent();
        WorkIntent current = intents.get(amended.identity());
        if (current == null) {
            throw new CoordinationException("INTENT_NOT_FOUND");
        }
        if (current.version() != extension.expectedClaimEpoch()) {
            throw new CoordinationException("CLAIM_EPOCH_STALE");
        }
        Participant owner = participantHistory.get(current.participantIdentity());
        if (owner == null || owner.state() != Participant.State.ACTIVE) {
            throw new CoordinationException("INTENT_NOT_ACTIVE");
        }
        if (!sameLaneIdentity(current, amended) || amended.version() != current.version() + 1
                || !amended.selectors().containsAll(current.selectors())
                || !amended.knownDependencies().containsAll(current.knownDependencies())) {
            throw new CoordinationException("SCOPE_EXTENSION_INVALID");
        }
        if (amended.selectors().equals(current.selectors())
                && amended.knownDependencies().equals(current.knownDependencies())
                && amended.goal().equals(current.goal()) && amended.acceptance().equals(current.acceptance())) {
            throw new CoordinationException("SCOPE_EXTENSION_NO_CHANGE");
        }
        if (!conflicts(amended.selectors(), current.identity()).isEmpty()) {
            throw new CoordinationException("OVERLAPPING_CLAIM");
        }
        intents.put(amended.identity(), amended);
        refreshParticipant(amended.participantIdentity(), Participant.State.ACTIVE, amended);
    }

    private static boolean sameLaneIdentity(WorkIntent current, WorkIntent amended) {
        return amended.project().equals(current.project()) && amended.task().equals(current.task())
                && amended.participantIdentity().equals(current.participantIdentity())
                && amended.providerIdentity().equals(current.providerIdentity())
                && amended.group().equals(current.group()) && amended.lineage().equals(current.lineage())
                && amended.baseCommitIdentity().equals(current.baseCommitIdentity())
                && amended.status() == current.status() && amended.role() == current.role()
                && amended.reviewTargetSelectors().equals(current.reviewTargetSelectors());
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
        groupsWithLanes.add(intent.group());
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
        endedLanes.put(released.authorityLineageId(), released);
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
                participantHistory.put(participant, previous.withState(emptyState).withClaims(List.of()));
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
                    previous.withGoal(replacement.goal()).withState(Participant.State.ACTIVE)
                            .withClaims(replacement.selectors()));
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
        requests.requireAbsent(request);
        if (!intents.containsKey(request.conflictingIntent())) {
            throw new CoordinationException("INTENT_NOT_FOUND");
        }
        requests.add(request);
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
        requireLaneAsRefused(current, validation.workGroupId(), validation.sourceParticipant(), validation.taskId(),
                validation.claimEpoch(), "REVIEW_TARGET_STALE");
        reopenForCorrection(current, false);
    }

    /**
     * Reopens the lane named by a refusal of the project's validation command at integration.
     *
     * <p>Unlike a historical review record, a refusal is never taken for a lane that is gone: it is
     * recorded under the project lock for the lane as it stands, so a missing or changed lane means
     * the refusal is stale and the append fails. The lane that published the refused snapshot is
     * advanced to the next version, as a review rejection advances it. A lane that continued it
     * already stands at a later version with no snapshot of its own, so it is left as it is.</p>
     *
     * <p>The command refuses whenever an attempt runs, which may be while the lane's holder is
     * suspended or held for recovery. Such a holder stays fenced: a refusal is no proof that its
     * session is alive, and a continuation is admitted only for a holder still held for recovery.</p>
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void validationRefused(ValidationRefusalPayload refusal) throws IOException {
        WorkIntent current = intents.get(new WorkIntentId(refusal.targetIntentId()));
        if (current == null) {
            throw new CoordinationException("VALIDATION_REFUSAL_TARGET_STALE");
        }
        requireLaneAsRefused(current, refusal.workGroupId(), refusal.participant(), refusal.taskId(),
                refusal.targetIntentVersion(), "VALIDATION_REFUSAL_TARGET_STALE");
        if (refusal.targetIntentVersion() == refusal.claimEpoch()) {
            reopenForCorrection(current, true);
        }
    }

    /**
     * Rejects a refusal decided against a lane that has changed since: another group, holder, task
     * or version, or a lane no longer announced.
     *
     * @throws CoordinationException with {@code staleCode} when the lane is not the one the refusal names
     */
    private static void requireLaneAsRefused(WorkIntent current, UUID workGroupId, String holder, UUID taskId,
            long version, String staleCode) throws CoordinationException {
        if (!current.workGroupId().equals(workGroupId) || !current.participant().equals(holder)
                || !current.taskId().equals(taskId) || current.version() != version
                || current.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException(staleCode);
        }
    }

    /**
     * Gives a lane whose snapshot was refused its next version, so that it may change its files
     * and publish again. This is the one way a refused snapshot is answered, whoever refused it.
     *
     * @param keepFence whether a holder that is suspended or held for recovery stays so; otherwise
     *                  the holder becomes active, as a review rejection has always left it
     * @throws IOException when payload validation or durable I/O fails
     */
    private void reopenForCorrection(WorkIntent current, boolean keepFence) throws IOException {
        WorkIntent correction = current.nextVersion();
        intents.put(correction.identity(), correction);
        Participant participant = participantHistory.get(current.participantIdentity());
        if (participant == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        boolean fenced = participant.state() == Participant.State.SUSPENDED
                || participant.state() == Participant.State.RECOVERY_HELD;
        participantHistory.put(current.participantIdentity(),
                participant.withGoal(correction.goal())
                        .withState(keepFence && fenced ? participant.state() : Participant.State.ACTIVE)
                        .withClaims(correction.selectors()));
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
                current.withState(Participant.State.ACTIVE).withLastVerifiedActivity(timestamp)
                        .withoutRecoveryReference());
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
        WorkIntent transferred = current.nextVersion().withParticipant(handoff.target());
        intents.put(current.identity(), transferred);
        Participant previous = participantHistory.get(current.participantIdentity());
        if (previous != null) {
            participantHistory.put(current.participantIdentity(),
                    previous.withState(Participant.State.COMPLETED).withClaims(List.of())
                            .withoutRecoveryReference());
        }
        participantHistory.put(handoff.targetParticipant(),
                target.withState(Participant.State.ACTIVE).withClaims(transferred.selectors())
                        .withoutRecoveryReference());
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
        participantHistory.put(participant, current.withState(Participant.State.SUSPENDED));
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
                current.withState(Participant.State.RECOVERY_HELD)
                        .withRecoverySnapshotReference(recovery.snapshot().value()));
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
        requests.retargetReviews(source, target, continuation);
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
     * Applies the revoked lifecycle transition after validating its authority.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void revoked(ParticipantId participant) throws IOException {
        Participant current = participantHistory.get(participant);
        if (current == null) {
            throw new CoordinationException("PARTICIPANT_NOT_FOUND");
        }
        participantHistory.put(participant, current.withState(Participant.State.REVOKED).withClaims(List.of()));
        dropIntentsOf(participant);
    }

    /**
     * Removes every intent the participant owns.
     */
    private void dropIntentsOf(ParticipantId participant) {
        intents.values().stream().filter(intent -> intent.participantIdentity().equals(participant)).forEach(intent -> endedLanes.put(intent.authorityLineageId(), intent));
        intents.entrySet().removeIf(entry -> entry.getValue().participantIdentity().equals(participant));
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
        participantHistory.put(participant, current.withState(Participant.State.CANCELLED).withClaims(List.of()));
        dropIntentsOf(participant);
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
        participantHistory.put(participant, current.withState(Participant.State.DETACHED).withClaims(List.of()));
        dropIntentsOf(participant);
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
        // Logs written before heartbeats were tolerated always satisfy this with equality at
        // eventSequence - 1, so they replay unchanged.
        if (completion.expectedRevision() >= eventSequence || completion.expectedRevision() < lastStateChangeSequence) {
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
        endedLanes.put(current.authorityLineageId(), current);
        noChangeCompletions.put(completion.intentIdentity(), completion);
        refreshParticipant(current.participantIdentity(), Participant.State.COMPLETED, null);
    }
}
