package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimConflict;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/**
 * Application service for authenticated work intent and resource claims.
 */
@SuppressWarnings("DuplicatedCode")
public final class WorkIntentService {

    private final PredictionEventStore store;
    private final NodeIdentity signer;

    /**
     * Creates a service bound to one project event store and signing identity.
     *
     * @param store  project event store
     * @param signer authenticated signing identity
     */
    public WorkIntentService(PredictionEventStore store, NodeIdentity signer) {
        this.store = Objects.requireNonNull(store, "store");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    private static boolean hasNoChangeObligation(PredictionEventStore current, WorkIntent intent) {
        boolean snapshotPending = current.taskCompletionProjection()
                .allSnapshots()
                .stream()
                .anyMatch(snapshot -> snapshot.provenance()
                        .laneId()
                        .equals(intent.intentId())
                        && snapshot.provenance()
                        .claimEpoch() == intent.version());
        boolean preparedPending = current.taskCompletionProjection()
                .allPrepared()
                .stream()
                .anyMatch(prepared -> prepared.laneId()
                        .equals(intent.intentId())
                        && prepared.claimEpoch() == intent.version());
        if (snapshotPending || preparedPending) {
            return true;
        }
        boolean grantPending = current.workGroupProjection()
                .grants()
                .stream()
                .filter(grant -> grant.workGroupId()
                        .equals(intent.workGroupId()))
                .filter(grant -> (grant.targetIntentId()
                        .equals(intent.intentId())
                        && grant.claimEpoch() == intent.version())
                        || grant.targetParticipant()
                        .equals(intent.participant()))
                .anyMatch(grant -> current.workGroupProjection()
                        .grantAvailable(grant.grantId())
                        || (current.workGroupProjection()
                        .grantConsumed(grant.grantId())
                        && current.workGroupProjection()
                        .reviewValidationForGrant(grant.grantId())
                        .isEmpty()));
        if (grantPending) {
            return true;
        }
        String participant = intent.participant();
        return current.collaborationProjection()
                .requests()
                .stream()
                .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING
                        && (request.conflictingIntentId()
                        .equals(intent.intentId())
                        || request.requester()
                        .equals(participant)
                        || request.target()
                        .equals(participant)));
    }

    /**
     * Validates the semantic direction of an explicit review request.
     */
    private static void validateReviewDirection(PredictionEventStore current, String requester,
            WorkIntent target) throws IOException {
        if (target.role() != WorkIntent.Role.PRODUCER) {
            throw new IOException("REVIEW_TARGET_NOT_PRODUCER");
        }
        if (requester.equals(target.participant())) {
            throw new IOException("REVIEW_SELF_NOT_ALLOWED");
        }
        List<WorkIntent> requesterIntents = current.collaborationProjection()
                .activeIntents()
                .stream()
                .filter(intent -> intent.participant()
                        .equals(requester))
                .toList();
        if (requesterIntents.isEmpty()) {
            // Existing review callers may be unannounced, but the request's
            // exact target still provides the required reviewed-intent identity.
            return;
        }
        List<WorkIntent> sameGroupReviewers = requesterIntents.stream()
                .filter(intent -> intent.workGroupId()
                        .equals(target.workGroupId()))
                .filter(intent -> intent.role() == WorkIntent.Role.REVIEWER)
                .toList();
        if (sameGroupReviewers.isEmpty()) {
            if (requesterIntents.stream()
                    .noneMatch(intent -> intent.workGroupId()
                            .equals(target.workGroupId()))) {
                throw new IOException("REVIEW_WORK_GROUP_MISMATCH");
            }
            // An explicitly requested reciprocal review may be issued by an
            // active producer lane. Automatic review admission remains
            // restricted to explicit reviewer intents in the workspace
            // projection, so this does not restore peer-order inference.
            return;
        }
        if (sameGroupReviewers.stream()
                .noneMatch(reviewer -> reviewTargetsMatch(reviewer, target))) {
            throw new IOException("REVIEW_TARGET_MISMATCH");
        }
    }

    private static boolean reviewTargetsMatch(WorkIntent reviewer, WorkIntent producer) {
        return reviewer.reviewTargetSelectors()
                .stream()
                .allMatch(targetSelector -> producer.selectors()
                        .stream()
                        .anyMatch(targetSelector::overlaps));
    }

    /**
     * Announces an intent and atomically acquires all requested selectors.
     *
     * @param intent intent declaration
     * @return acquisition result
     * @throws IOException              when persistence or validation fails
     * @throws GeneralSecurityException when event signing fails
     */
    public synchronized ClaimResult announce(WorkIntent intent) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(intent, "intent");
        if (!store.projectId()
                .equals(intent.projectId())) {
            throw new IllegalArgumentException("intent project mismatch");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            List<ClaimConflict> conflicts = current.collaborationProjection()
                    .conflicts(intent.selectors());
            if (!conflicts.isEmpty()) {
                appendAutomaticConflictInbox(current, intent, conflicts);
                return new ClaimResult(false, intent, conflicts);
            }
            WorkGroup existingGroup = current.workGroupProjection()
                    .group(intent.workGroupId())
                    .orElse(null);
            if (existingGroup != null && existingGroup.status() != WorkGroup.Status.ACTIVE) {
                throw new IOException("WORK_GROUP_NOT_ACTIVE");
            }
            if (existingGroup == null) {
                WorkGroup group = new WorkGroup(intent.workGroupId(), intent.projectId(), intent.goal(),
                        intent.acceptance(), 1, WorkGroup.Status.ACTIVE);
                current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_CREATED,
                        signer.nodeId(), CollaborationCodec.encodeWorkGroup(group), signer);
            }
            current.append(intent.intentId(), PredictionEventType.WORK_INTENT_ANNOUNCED,
                    signer.nodeId(), CollaborationCodec.encodeIntent(intent), signer);
            return new ClaimResult(true, intent, List.of());
        }
    }

    private void appendAutomaticConflictInbox(PredictionEventStore current, WorkIntent contender,
            List<ClaimConflict> conflicts) throws IOException, GeneralSecurityException {
        for (ClaimConflict conflict : conflicts) {
            if (contender.participant()
                    .equals(conflict.participant())) {
                continue;
            }
            UUID conflictingIntentId = UUID.fromString(conflict.intentId());
            String proposal = "Claim overlap detected for " + conflict.selector()
                    .kind()
                    .name() + " "
                    + conflict.selector()
                    .value() + "; negotiate contract or scope before mutation.";
            appendConflictRequest(current, contender.participant(), conflict.participant(), conflictingIntentId,
                    proposal, contender.intentId() + "->" + conflictingIntentId);
            appendConflictRequest(current, conflict.participant(), contender.participant(), conflictingIntentId,
                    proposal, conflictingIntentId + "->" + contender.intentId());
        }
    }

    private void appendConflictRequest(PredictionEventStore current, String requester, String target,
            UUID conflictingIntentId, String proposal, String direction)
            throws IOException, GeneralSecurityException {
        UUID requestId = UUID.nameUUIDFromBytes(("claim-conflict|" + direction)
                .getBytes(StandardCharsets.UTF_8));
        boolean exists = current.collaborationProjection()
                .requests()
                .stream()
                .anyMatch(existing -> existing.requestId()
                        .equals(requestId));
        if (exists) {
            return;
        }
        CoordinationRequest request = new CoordinationRequest(requestId, current.projectId(), requester, target,
                conflictingIntentId, CoordinationRequest.Kind.CONTRACT, proposal, CoordinationRequest.Status.PENDING);
        current.append(request.requestId(), PredictionEventType.COORDINATION_REQUESTED, signer.nodeId(),
                CollaborationCodec.encodeRequest(request), signer);
    }

    /**
     * Releases an intent owned by the signing participant.
     *
     * @param intentId    intent identifier
     * @param participant participant handle
     * @throws IOException              when the intent is missing or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public synchronized void release(UUID intentId, String participant)
            throws IOException, GeneralSecurityException {
        release(intentId, participant, null);
    }

    /**
     * Releases an intent after checking an optimistic mutation precondition.
     *
     * @param intentId     intent identifier
     * @param participant  participant handle
     * @param precondition expected immutable intent state, or {@code null}
     * @throws IOException              when the intent is missing, stale, or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public synchronized void release(UUID intentId, String participant,
            WorkIntentMutationPrecondition precondition)
            throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            WorkIntent intent = current.collaborationProjection()
                    .intent(intentId)
                    .orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            if (!intent.participant()
                    .equals(participant)) {
                throw new IOException("INTENT_OWNER_MISMATCH");
            }
            if (precondition != null) {
                precondition.requireMatches(intent);
            }
            current.append(intentId, PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(), CollaborationCodec.encodeRelease(intentId), signer);
        }
    }

    /**
     * Releases an exactly integrated lane while the caller holds the project append lock.
     *
     * <p>The snapshot provenance is the optimistic authority proof. A stale
     * revision, different participant, different lineage, or non-integrated
     * snapshot fails closed. The event ordering is deliberately integration
     * first, then intent release, so a rejected or pending snapshot cannot
     * terminalize its lane.</p>
     *
     * @param lock     already-held project append lock
     * @param snapshot exact snapshot whose integration succeeded
     * @return {@code true} when an active intent was released; {@code false}
     *         when the lane was already released
     * @throws IOException              stale or unauthorized snapshot/lane
     * @throws GeneralSecurityException signing failure
     */
    public synchronized boolean releaseAfterIntegration(ProjectAppendLock lock,
            TaskSnapshotRecord snapshot) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(lock, "project append lock");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!lock.isHeld()) {
            throw new IOException("event append lock unavailable");
        }
        PredictionEventStore current = freshStore();
        TaskCompletionState snapshotState = current.taskCompletionProjection()
                .snapshotState(snapshot.snapshotId())
                .orElse(TaskCompletionState.ACTIVE);
        if (snapshotState != TaskCompletionState.INTEGRATED) {
            throw new IOException("INTEGRATED_SNAPSHOT_REQUIRED");
        }
        WorkIntent intent = current.collaborationProjection()
                .intent(snapshot.provenance()
                        .laneId())
                .orElse(null);
        if (intent == null) {
            return false;
        }
        if (intent.status() != WorkIntent.Status.ANNOUNCED
                || !intent.taskId()
                .equals(snapshot.taskId())
                || !intent.participant()
                .equals(snapshot.provenance()
                        .participant())
                || !intent.workGroupId()
                .equals(snapshot.provenance()
                        .workGroupId())
                || !intent.authorityLineageId()
                .equals(snapshot.provenance()
                        .authorityLineageId())
                || intent.version() != snapshot.provenance()
                .claimEpoch()) {
            throw new IOException("INTEGRATED_LANE_BINDING_MISMATCH");
        }
        current.append(intent.intentId(), PredictionEventType.WORK_INTENT_RELEASED,
                signer.nodeId(), CollaborationCodec.encodeRelease(intent.intentId()), signer);
        reevaluateNoChangeWorkGroup(current, intent.workGroupId());
        return true;
    }

    /**
     * Completes one explicitly no-change-allowed intent and reevaluates its group.
     *
     * <p>This is the only coordination transition that may release an intent
     * without a snapshot. The caller supplies optimistic evidence, while this
     * service verifies the durable intent, group, epoch, and event revision
     * under the project append lock. A repeated identical completion is a
     * read-only replay and never appends a second terminal event.</p>
     *
     * @param completion exact no-change completion evidence
     * @return the durable completion evidence
     * @throws IOException              stale, unauthorized, or pending lifecycle state
     * @throws GeneralSecurityException signing failure
     */
    public synchronized NoChangeCompletion completeNoChange(NoChangeCompletion completion)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(completion, "completion");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            NoChangeCompletion previous = current.collaborationProjection()
                    .noChangeCompletion(completion.intentId())
                    .orElse(null);
            if (previous != null) {
                if (!previous.equals(completion)) {
                    throw new IOException("NO_CHANGE_COMPLETION_CONFLICT");
                }
                reevaluateNoChangeWorkGroup(current, completion.workGroupId());
                return previous;
            }
            if (completion.expectedRevision() != current.headSequence()) {
                throw new IOException("COMPLETION_REVISION_STALE");
            }
            WorkIntent intent = current.collaborationProjection()
                    .intent(completion.intentId())
                    .orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            if (!current.projectId()
                    .equals(intent.projectId())) {
                throw new IOException("NO_CHANGE_PROJECT_MISMATCH");
            }
            if (intent.status() != WorkIntent.Status.ANNOUNCED) {
                throw new IOException("INTENT_NOT_ACTIVE");
            }
            if (!intent.workGroupId()
                    .equals(completion.workGroupId())
                    || !intent.participant()
                    .equals(completion.participant())
                    || !intent.provider()
                    .equals(completion.provider())
                    || !intent.authorityLineageId()
                    .equals(completion.authorityLineageId())
                    || intent.version() != completion.claimEpoch()
                    || !intent.baseCommit()
                    .equals(completion.workspaceCommit())) {
                throw new IOException("NO_CHANGE_COMPLETION_BINDING_MISMATCH");
            }
            if (intent.completionMode() != WorkIntent.CompletionMode.NO_CHANGE_ALLOWED) {
                throw new IOException("NO_CHANGE_NOT_AUTHORIZED");
            }
            WorkGroup group = current.workGroupProjection()
                    .group(intent.workGroupId())
                    .orElseThrow(() -> new IOException("WORK_GROUP_NOT_FOUND"));
            if (group.status() != WorkGroup.Status.ACTIVE) {
                throw new IOException("WORK_GROUP_NOT_ACTIVE");
            }
            if (group.version() != completion.workGroupVersion()) {
                throw new IOException("WORK_GROUP_VERSION_STALE");
            }
            if (hasNoChangeObligation(current, intent)) {
                throw new IOException("NO_CHANGE_REVIEW_OBLIGATION");
            }
            current.append(intent.intentId(), PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(), CollaborationCodec.encodeNoChangeCompletion(completion), signer);
            reevaluateNoChangeWorkGroup(current, intent.workGroupId());
            return completion;
        }
    }

    private void reevaluateNoChangeWorkGroup(PredictionEventStore current, UUID workGroupId)
            throws IOException, GeneralSecurityException {
        WorkGroup group = current.workGroupProjection()
                .group(workGroupId)
                .orElse(null);
        if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
            return;
        }
        if (current.collaborationProjection()
                .activeIntents()
                .stream()
                .anyMatch(intent -> intent.workGroupId()
                        .equals(workGroupId))) {
            return;
        }
        if (current.workGroupProjection()
                .grants()
                .stream()
                .filter(grant -> grant.workGroupId()
                        .equals(workGroupId))
                .anyMatch(grant -> current.workGroupProjection()
                        .grantAvailable(grant.grantId())
                        || (current.workGroupProjection()
                        .grantConsumed(grant.grantId())
                        && current.workGroupProjection()
                        .reviewValidationForGrant(grant.grantId())
                        .isEmpty()))) {
            return;
        }
        if (current.collaborationProjection()
                .requests()
                .stream()
                .filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .anyMatch(request -> current.collaborationProjection()
                        .intent(request.conflictingIntentId())
                        .map(intent -> intent.workGroupId()
                                .equals(workGroupId))
                        .orElseGet(() ->
                                current.collaborationProjection()
                                        .noChangeCompletion(request.conflictingIntentId())
                                        .map(completion -> completion.workGroupId()
                                                .equals(workGroupId))
                                        .orElse(false)))) {
            return;
        }
        for (var snapshot : current.taskCompletionProjection()
                .allSnapshots()) {
            if (!snapshot.provenance()
                    .workGroupId()
                    .equals(workGroupId)) {
                continue;
            }
            TaskCompletionState state = current.taskCompletionProjection()
                    .snapshotState(snapshot.snapshotId())
                    .orElse(TaskCompletionState.ACTIVE);
            if (state == TaskCompletionState.REVIEW_REJECTED) {
                boolean corrected = current.taskCompletionProjection()
                        .allSnapshots()
                        .stream()
                        .anyMatch(candidate -> candidate.provenance()
                                .laneId()
                                .equals(snapshot.provenance()
                                        .laneId())
                                && candidate.provenance()
                                .authorityLineageId()
                                .equals(snapshot.provenance()
                                        .authorityLineageId())
                                && candidate.provenance()
                                .claimEpoch() > snapshot.provenance()
                                .claimEpoch()
                                && current.taskCompletionProjection()
                                .snapshotState(candidate.snapshotId())
                                .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.INTEGRATED);
                if (!corrected) {
                    return;
                }
                continue;
            }
            if (state != TaskCompletionState.INTEGRATED) {
                return;
            }
        }
        if (current.taskCompletionProjection()
                .allPrepared()
                .stream()
                .anyMatch(prepared ->
                        current.collaborationProjection()
                                .intent(prepared.laneId())
                                .map(intent -> intent.workGroupId()
                                        .equals(workGroupId)
                                        && current.taskCompletionProjection()
                                        .findSnapshotForTaskRevision(intent.taskId(), intent.intentId(),
                                                prepared.claimEpoch())
                                        .map(snapshot -> current.taskCompletionProjection()
                                                .snapshotState(snapshot.snapshotId())
                                                .orElse(TaskCompletionState.ACTIVE)
                                                != TaskCompletionState.INTEGRATED)
                                        .orElse(true))
                                .orElseGet(() -> current.collaborationProjection()
                                        .noChangeCompletion(prepared.laneId())
                                        .map(completion -> completion.workGroupId()
                                                .equals(workGroupId))
                                        .orElse(false)))) {
            return;
        }
        WorkGroup completed = new WorkGroup(group.workGroupId(), group.projectId(), group.goal(),
                group.acceptance(), group.version() + 1L, WorkGroup.Status.COMPLETED);
        current.append(group.workGroupId(), PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                signer.nodeId(), CollaborationCodec.encodeWorkGroup(completed), signer);
    }

    /**
     * Atomically transfers reserved selectors into a new repair lane.
     *
     * <p>The event is the sole ownership transition: the source remains the
     * owner until the signed event is durably appended, and projection replay
     * removes the source and announces the target as one transition.  Repeating
     * the same operation after a lost response is idempotent.</p>
     *
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot identifier
     * @param expectedControlHead control HEAD used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid source, stale epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public synchronized void createRepairLane(UUID sourceIntentId, String snapshotId,
            String expectedControlHead, WorkIntent targetIntent)
            throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            createRepairLaneLocked(sourceIntentId, snapshotId, expectedControlHead, targetIntent);
        }
    }

    /**
     * Atomically transfers repair scope while the caller holds the project
     * append lock used to serialize Git integration and event publication.
     *
     * @param lock                already-held project append lock
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot identifier
     * @param expectedControlHead control HEAD used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid lock, source, epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public synchronized void createRepairLane(ProjectAppendLock lock, UUID sourceIntentId,
            String snapshotId, String expectedControlHead, WorkIntent targetIntent)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(lock, "project append lock");
        if (!lock.isHeld()) {
            throw new IOException("event append lock unavailable");
        }
        createRepairLaneLocked(sourceIntentId, snapshotId, expectedControlHead, targetIntent);
    }

    private void createRepairLaneLocked(UUID sourceIntentId,
            String snapshotId, String expectedControlHead, WorkIntent targetIntent)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(sourceIntentId, "source intent");
        Objects.requireNonNull(snapshotId, "snapshot ID");
        Objects.requireNonNull(expectedControlHead, "expected control HEAD");
        Objects.requireNonNull(targetIntent, "target intent");
        if (snapshotId.isBlank() || expectedControlHead.isBlank()) {
            throw new IOException("REPAIR_METADATA_REQUIRED");
        }
        PredictionEventStore current = freshStore();
        var existingTarget = current.collaborationProjection()
                .intent(targetIntent.intentId());
        if (existingTarget.isPresent()) {
            if (!existingTarget.get()
                    .equals(targetIntent)) {
                throw new IOException("REPAIR_TARGET_MISMATCH");
            }
            return;
        }
        WorkIntent source = current.collaborationProjection()
                .intent(sourceIntentId)
                .orElseThrow(() -> new IOException("REPAIR_SOURCE_NOT_FOUND"));
        if (source.status() != WorkIntent.Status.ANNOUNCED) {
            throw new IOException("REPAIR_SOURCE_NOT_ACTIVE");
        }
        if (!source.projectId()
                .equals(targetIntent.projectId())
                || !source.workGroupId()
                .equals(targetIntent.workGroupId())) {
            throw new IOException("REPAIR_WORK_GROUP_MISMATCH");
        }
        if (!source.authorityLineageId()
                .equals(targetIntent.authorityLineageId())) {
            throw new IOException("REPAIR_AUTHORITY_LINEAGE_MISMATCH");
        }
        if (source.intentId()
                .equals(targetIntent.intentId())
                || source.participant()
                .equals(targetIntent.participant())) {
            throw new IOException("REPAIR_TARGET_MUST_BE_DISTINCT");
        }
        if (!source.selectors()
                .equals(targetIntent.selectors())) {
            throw new IOException("REPAIR_SCOPE_MISMATCH");
        }
        if (targetIntent.version() != source.version() + 1L) {
            throw new IOException("REPAIR_EPOCH_MISMATCH");
        }
        if (current.collaborationProjection()
                .activeIntents()
                .stream()
                .anyMatch(intent -> intent.participant()
                        .equals(targetIntent.participant()))) {
            throw new IOException("REPAIR_TARGET_ALREADY_ACTIVE");
        }
        var payload = new org.synesis.coordination.domain.collaboration.RepairLanePayload(
                sourceIntentId, targetIntent, snapshotId, expectedControlHead,
                source.version(), targetIntent.version());
        UUID eventId = UUID.nameUUIDFromBytes(("repair-lane:" + sourceIntentId + ":"
                + targetIntent.intentId() + ":" + snapshotId)
                .getBytes(StandardCharsets.UTF_8));
        current.append(eventId, PredictionEventType.REPAIR_LANE_CREATED,
                signer.nodeId(), payload.encode(), signer);
    }

    /**
     * Returns whether a participant owns a compatible selector.
     *
     * @param participant participant handle
     * @param selector    target selector
     * @return true when the participant owns an overlapping active claim
     */
    public boolean owns(String participant, ResourceSelector selector) {
        return freshIntents().stream()
                .filter(intent -> intent.participant()
                        .equals(participant))
                .anyMatch(intent -> intent.selectors()
                        .stream()
                        .anyMatch(selector::overlaps));
    }

    /**
     * Returns the active intents for discovery.
     *
     * @return active intent snapshot
     */
    public List<WorkIntent> activeIntents() {
        return freshIntents();
    }

    /**
     * Opens a negotiation request against the owner of a conflicting intent.
     *
     * @param requester           requester ID
     * @param conflictingIntentId conflicting intent
     * @param kind                request kind
     * @param proposal            proposal
     * @return request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(String requester, UUID conflictingIntentId,
            CoordinationRequest.Kind kind, String proposal) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            if (kind == CoordinationRequest.Kind.REVIEW) {
                CoordinationRequest existing = current.collaborationProjection()
                        .requests()
                        .stream()
                        .filter(candidate -> candidate.requester()
                                .equals(requester))
                        .filter(candidate -> candidate.conflictingIntentId()
                                .equals(conflictingIntentId))
                        .filter(candidate -> candidate.kind() == kind)
                        .findFirst()
                        .orElse(null);
                WorkIntent target = current.collaborationProjection()
                        .intent(conflictingIntentId)
                        .orElse(null);
                if (target == null) {
                    if (existing != null && (existing.status() == CoordinationRequest.Status.PENDING
                            || existing.status() == CoordinationRequest.Status.ACCEPTED)) {
                        return existing;
                    }
                    throw new IOException("INTENT_NOT_FOUND");
                }
                validateReviewDirection(current, requester, target);
                // A review admission is one authority negotiation for one
                // exact lane revision. Pending requests and grants for the
                // current epoch are idempotent; an accepted request from a
                // rejected older epoch is history and must not suppress a
                // fresh request for the correction.
                if (existing != null) {
                    if (existing.status() == CoordinationRequest.Status.PENDING) {
                        return existing;
                    }
                    UUID grantId = UUID.nameUUIDFromBytes(("synesis-review-grant:" + existing.requestId())
                            .getBytes(StandardCharsets.UTF_8));
                    boolean currentEpochGrant = current.workGroupProjection()
                            .grants()
                            .stream()
                            .anyMatch(grant -> grant.grantId()
                                    .equals(grantId)
                                    && grant.targetIntentId()
                                    .equals(target.intentId())
                                    && grant.claimEpoch() == target.version());
                    if (currentEpochGrant) {
                        return existing;
                    }
                }
            }
            WorkIntent conflict = current.collaborationProjection()
                    .intent(conflictingIntentId)
                    .orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(), current.projectId(), requester,
                    conflict.participant(), conflictingIntentId, kind, proposal, CoordinationRequest.Status.PENDING);
            current.append(request.requestId(), PredictionEventType.COORDINATION_REQUESTED, signer.nodeId(),
                    CollaborationCodec.encodeRequest(request), signer);
            return request;
        }
    }

    /**
     * Offers an atomic handoff to another active participant.
     *
     * @param owner    current owner
     * @param intentId intent ID
     * @param target   target participant
     * @param proposal handoff proposal
     * @return pending request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest offerHandoff(String owner, UUID intentId, String target, String proposal)
            throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            WorkIntent intent = current.collaborationProjection()
                    .intent(intentId)
                    .orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            if (!intent.participant()
                    .equals(owner)) {
                throw new IOException("INTENT_OWNER_MISMATCH");
            }
            if (current.collaborationProjection()
                    .isParticipantTerminal(owner)
                    || current.collaborationProjection()
                    .isParticipantTerminal(target)) {
                throw new IOException("SESSION_TERMINAL");
            }
            boolean activeTarget = current.collaborationProjection()
                    .participants()
                    .stream()
                    .anyMatch(participant -> participant.id()
                            .equals(target)
                            && participant.state()
                            == org.synesis.coordination.domain.collaboration.Participant.State.ACTIVE)
                    || current.collaborationProjection()
                    .activeIntents()
                    .stream()
                    .anyMatch(candidate -> candidate.participant()
                            .equals(target));
            if (!activeTarget) {
                throw new IOException("HANDOFF_TARGET_NOT_ACTIVE");
            }
            CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(), current.projectId(), owner, target,
                    intentId, CoordinationRequest.Kind.HANDOFF, proposal, CoordinationRequest.Status.PENDING);
            current.append(request.requestId(), PredictionEventType.COORDINATION_REQUESTED, signer.nodeId(),
                    CollaborationCodec.encodeRequest(request), signer);
            return request;
        }
    }

    /**
     * Responds idempotently to a request addressed to the participant.
     *
     * @param participant target participant
     * @param requestId   request ID
     * @param status      response status
     * @param proposal    revised proposal
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public void respond(String participant, UUID requestId, CoordinationRequest.Status status, String proposal)
            throws IOException, GeneralSecurityException {
        if (status == CoordinationRequest.Status.PENDING) {
            throw new IllegalArgumentException("pending is not a response");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            CoordinationRequest request = current.collaborationProjection()
                    .requests()
                    .stream()
                    .filter(candidate -> candidate.requestId()
                            .equals(requestId))
                    .findFirst()
                    .orElseThrow(() -> new IOException("REQUEST_NOT_FOUND"));
            if (!request.target()
                    .equals(participant)) {
                throw new IOException("REQUEST_TARGET_MISMATCH");
            }
            if (current.collaborationProjection()
                    .isParticipantTerminal(participant)) {
                throw new IOException("SESSION_TERMINAL");
            }
            if (request.status() != CoordinationRequest.Status.PENDING) {
                if (request.status() != status) {
                    throw new IOException("REQUEST_ALREADY_RESOLVED");
                }
                if (status == CoordinationRequest.Status.ACCEPTED
                        && request.kind() == CoordinationRequest.Kind.REVIEW) {
                    issueReviewGrantIfAbsent(current, request);
                }
                return;
            }
            current.append(requestId, PredictionEventType.COORDINATION_RESPONDED, signer.nodeId(),
                    CollaborationCodec.encodeResponse(requestId, status, proposal), signer);
            if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.HANDOFF) {
                current.append(request.conflictingIntentId(),
                        PredictionEventType.CLAIM_HANDOFF_ACCEPTED,
                        signer.nodeId(),
                        CollaborationCodec.encodeHandoff(request.conflictingIntentId(), request.target(),
                                current.collaborationProjection()
                                        .intent(request.conflictingIntentId())
                                        .orElseThrow()
                                        .version()),
                        signer);
            }
            if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.REVIEW) {
                issueReviewGrantIfAbsent(current, request);
            }
        }
    }

    private void issueReviewGrantIfAbsent(PredictionEventStore current, CoordinationRequest request)
            throws IOException, GeneralSecurityException {
        WorkIntent intent = current.collaborationProjection()
                .intent(request.conflictingIntentId())
                .orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
        validateReviewDirection(current, request.requester(), intent);
        if (!request.target()
                .equals(intent.participant())) {
            throw new IOException("REVIEW_TARGET_PARTICIPANT_MISMATCH");
        }
        UUID grantId = UUID.nameUUIDFromBytes(("synesis-review-grant:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        boolean exists = current.workGroupProjection()
                .grants()
                .stream()
                .anyMatch(grant -> grant.grantId()
                        .equals(grantId));
        if (!exists) {
            LaneGrant grant = new LaneGrant(grantId, intent.workGroupId(), intent.intentId(),
                    request.requester(), intent.version(), true);
            current.append(grantId, PredictionEventType.LANE_GRANT_ISSUED,
                    signer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), signer);
        }
    }

    /**
     * Acknowledges a server-issued inbox item for the exact participant, idempotently.
     *
     * @param participant exact participant handle
     * @param itemId      server-issued request/inbox item ID
     * @throws IOException              persistence or authorization failure
     * @throws GeneralSecurityException signing failure
     */
    public void acknowledgeInbox(String participant, UUID itemId) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            CoordinationRequest request = current.collaborationProjection()
                    .requests()
                    .stream()
                    .filter(candidate -> candidate.requestId()
                            .equals(itemId))
                    .findFirst()
                    .orElseThrow(() -> new IOException("INBOX_ITEM_NOT_FOUND"));
            if (!request.target()
                    .equals(participant)) {
                throw new IOException("INBOX_ITEM_CALLER_MISMATCH");
            }
            if (!current.collaborationProjection()
                    .inboxAcknowledged(itemId)) {
                current.append(itemId, PredictionEventType.INBOX_ITEM_ACKNOWLEDGED, signer.nodeId(),
                        CollaborationCodec.encodeUuidText(itemId), signer);
            }
        }
    }

    /**
     * Resolves and acknowledges a coordination inbox item atomically.
     *
     * @param participant exact participant handle
     * @param itemId      server-issued inbox item ID
     * @param status      terminal response status
     * @param proposal    response or resolution proposal
     * @throws IOException              authorization or transition failure
     * @throws GeneralSecurityException signing failure
     */
    public void resolveInbox(String participant, UUID itemId, CoordinationRequest.Status status, String proposal)
            throws IOException, GeneralSecurityException {
        if (status == null || status == CoordinationRequest.Status.PENDING) {
            throw new IllegalArgumentException("inbox resolution requires terminal status");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            CoordinationRequest request = current.collaborationProjection()
                    .requests()
                    .stream()
                    .filter(candidate -> candidate.requestId()
                            .equals(itemId))
                    .findFirst()
                    .orElseThrow(() -> new IOException("INBOX_ITEM_NOT_FOUND"));
            if (!request.target()
                    .equals(participant)) {
                throw new IOException("INBOX_ITEM_CALLER_MISMATCH");
            }
            if (request.status() == CoordinationRequest.Status.PENDING) {
                current.append(itemId, PredictionEventType.COORDINATION_RESPONDED, signer.nodeId(),
                        CollaborationCodec.encodeResponse(itemId, status, proposal), signer);
            } else if (request.status() != status) {
                throw new IOException("REQUEST_ALREADY_RESOLVED");
            }
            if (!current.collaborationProjection()
                    .inboxAcknowledged(itemId)) {
                current.append(itemId, PredictionEventType.INBOX_ITEM_ACKNOWLEDGED, signer.nodeId(),
                        CollaborationCodec.encodeUuidText(itemId), signer);
            }
        }
    }

    /**
     * Returns durable coordination requests for discovery.
     *
     * @return requests
     */
    public List<CoordinationRequest> requests() {
        try {
            return freshStore().collaborationProjection()
                    .requests();
        } catch (Exception failure) {
            throw new IllegalStateException("COLLABORATION_STATE_UNAVAILABLE", failure);
        }
    }

    /**
     * Appends a signed verified-activity heartbeat for an active participant.
     *
     * @param participant participant ID
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public void heartbeat(String participant) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            boolean known = current.collaborationProjection()
                    .participantKey(participant)
                    .isPresent();
            if (!known) {
                throw new IOException("PARTICIPANT_NOT_FOUND");
            }
            String durableParticipant = current.collaborationProjection()
                    .participantKey(participant)
                    .orElseThrow();
            current.append(UUID.nameUUIDFromBytes(durableParticipant.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    PredictionEventType.PARTICIPANT_HEARTBEAT, signer.nodeId(),
                    CollaborationCodec.encodeHeartbeat(participant), signer);
        }
    }

    /**
     * Fences a participant after verified process loss without releasing its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void suspend(String participant) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            boolean known = current.collaborationProjection()
                    .participantKey(participant)
                    .isPresent();
            if (!known) {
                return;
            }
            String durableParticipant = current.collaborationProjection()
                    .participantKey(participant)
                    .orElseThrow();
            current.append(UUID.nameUUIDFromBytes(durableParticipant.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    PredictionEventType.PARTICIPANT_SUSPENDED, signer.nodeId(),
                    CollaborationCodec.encodeHeartbeat(participant), signer);
        }
    }

    /**
     * Records verified recovery evidence with an immutable snapshot reference.
     *
     * @param participant       participant handle
     * @param snapshotReference immutable snapshot reference
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void holdRecovery(String participant, String snapshotReference)
            throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            String durableParticipant = current.collaborationProjection()
                    .participantKey(participant)
                    .orElseThrow(() -> new IOException("PARTICIPANT_NOT_FOUND"));
            if (snapshotReference == null || snapshotReference.isBlank()) {
                throw new IOException("RECOVERY_SNAPSHOT_REQUIRED");
            }
            current.append(UUID.nameUUIDFromBytes(durableParticipant.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    PredictionEventType.RECOVERY_SNAPSHOT_HELD, signer.nodeId(),
                    CollaborationCodec.encodeRecovery(durableParticipant, snapshotReference), signer);
        }
    }

    /**
     * Explicitly revokes a participant lane and releases its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void revoke(String participant) throws IOException, GeneralSecurityException {
        appendParticipantEvent(participant, PredictionEventType.PARTICIPANT_REVOKED);
    }

    /**
     * Explicitly cancels a participant lane and releases its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void cancel(String participant) throws IOException, GeneralSecurityException {
        appendParticipantEvent(participant, PredictionEventType.PARTICIPANT_CANCELLED);
    }

    /**
     * Records a clean connection shutdown and releases the lane without completing its task.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void detach(String participant) throws IOException, GeneralSecurityException {
        appendParticipantEvent(participant, PredictionEventType.PARTICIPANT_DETACHED);
    }

    /**
     * Atomically transfers a held recovery lane to a newly authenticated participant.
     *
     * @param continuation continuation payload
     * @throws IOException              stale grant, epoch, participant, or projection state
     * @throws GeneralSecurityException signing failure
     */
    public void continueFromRecovery(CollaborationCodec.Continuation continuation)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(continuation, "continuation");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            if (!current.workGroupProjection()
                    .grantAvailable(continuation.grantId())) {
                throw new IOException("LANE_GRANT_REPLAYED");
            }
            WorkIntent source = current.collaborationProjection()
                    .intent(continuation.sourceIntentId())
                    .orElseThrow(() -> new IOException("CONTINUATION_SOURCE_NOT_FOUND"));
            if (!source.participant()
                    .equals(continuation.sourceParticipant())) {
                throw new IOException("CONTINUATION_SOURCE_MISMATCH");
            }
            if (source.version() != continuation.expectedEpoch()) {
                throw new IOException("CLAIM_EPOCH_STALE");
            }
            ParticipantState.requireHeld(current, continuation.sourceParticipant());
            if (current.collaborationProjection()
                    .recoverySnapshotReference(continuation.sourceParticipant())
                    .filter(continuation.snapshotReference()::equals)
                    .isEmpty()) {
                throw new IOException("RECOVERY_SNAPSHOT_MISMATCH");
            }
            LaneGrant grant = current.workGroupProjection()
                    .grants()
                    .stream()
                    .filter(candidate -> candidate.grantId()
                            .equals(continuation.grantId()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
            if (!grant.targetParticipant()
                    .equals(continuation.targetParticipant())
                    || !grant.targetIntentId()
                    .equals(continuation.targetIntent()
                            .intentId())
                    || !grant.workGroupId()
                    .equals(source.workGroupId())) {
                throw new IOException("LANE_GRANT_TARGET_MISMATCH");
            }
            current.append(continuation.targetIntent()
                            .intentId(), PredictionEventType.LANE_CONTINUATION_ACCEPTED,
                    signer.nodeId(), CollaborationCodec.encodeContinuation(continuation), signer);
        }
    }

    private void appendParticipantEvent(String participant, PredictionEventType type)
            throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            String durableParticipant = current.collaborationProjection()
                    .participantKey(participant)
                    .orElseThrow(() -> new IOException("PARTICIPANT_NOT_FOUND"));
            current.append(UUID.nameUUIDFromBytes(durableParticipant.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    type, signer.nodeId(), CollaborationCodec.encodeHeartbeat(durableParticipant), signer);
        }
    }

    private List<WorkIntent> freshIntents() {
        try {
            return freshStore().collaborationProjection()
                    .activeIntents();
        } catch (Exception failure) {
            throw new IllegalStateException("COLLABORATION_STATE_UNAVAILABLE", failure);
        }
    }

    private PredictionEventStore freshStore() throws IOException, GeneralSecurityException {
        return new PredictionEventStore(store.rootDirectory(), store.projectId());
    }

    private static final class ParticipantState {

        private static void requireHeld(PredictionEventStore store, String participant) throws IOException {
            boolean held = store.collaborationProjection()
                    .participantState(participant)
                    .filter(state -> state
                            == org.synesis.coordination.domain.collaboration.Participant.State.RECOVERY_HELD)
                    .isPresent();
            if (!held) {
                throw new IOException("RECOVERY_NOT_HELD");
            }
        }
    }
}
