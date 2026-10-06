package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimConflict;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.RepairLanePayload;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.WorkIntentScopeExtension;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationMissingElementException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

/**
 * Application facade for work-intent claims, coordination requests, and participant recovery.
 *
 * <p>Append-log transitions acquire {@link ProjectAppendLock} around the complete
 * read/validate/append sequence. Claims therefore represent durable, authenticated ownership decisions rather than an
 * in-memory
 * reservation; conflicts fail closed and do not grant authority to either contender.</p>
 */
@SuppressWarnings("DuplicatedCode")
public final class WorkIntentService {

    /**
     * Event store whose projections are the source of current claim state.
     */
    private final PredictionEventStore store;
    /**
     * Identity used to authenticate every event emitted by this service.
     */
    private final NodeIdentity signer;
    /**
     * Owns coordination-request admission, response, and review-grant transitions.
     */
    private final CoordinationRequestService coordinationRequests;
    /**
     * Owns participant lifecycle changes and recovery continuation.
     */
    private final ParticipantRecoveryService participantRecovery;

    /**
     * Creates a service bound to one project event store and signing identity.
     *
     * @param store  project event store
     * @param signer authenticated signing identity
     */
    public WorkIntentService(PredictionEventStore store, NodeIdentity signer) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
        this.coordinationRequests = new CoordinationRequestService(this.store, this.signer);
        this.participantRecovery = new ParticipantRecoveryService(this.store, this.signer);
    }

    /**
     * Checks whether unresolved output, grants, or requests prevent lane release.
     *
     * @param current fresh authoritative project projection
     */
    private static boolean hasNoChangeObligation(PredictionEventStore current, WorkIntent intent) {
        if (hasPendingSnapshotOrPreparation(current, intent)) {
            return true;
        }
        if (hasUnresolvedGrant(current, intent)) {
            return true;
        }
        return hasPendingRequest(current, intent);
    }

    /**
     * Checks for unpublished or unintegrated output from the exact claim epoch.
     */
    private static boolean hasPendingSnapshotOrPreparation(PredictionEventStore current, WorkIntent intent) {
        boolean snapshotPending = current.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.provenance().laneIdentity().equals(intent.identity())
                        && snapshot.provenance().claimEpochIdentity().equals(intent.claimEpoch()));
        boolean preparedPending = current.taskCompletionProjection().allPrepared().stream()
                .anyMatch(prepared -> prepared.laneIdentity().equals(intent.identity()) && prepared.claimEpochIdentity()
                        .equals(intent.claimEpoch()));
        return snapshotPending || preparedPending;
    }

    /**
     * Checks whether a grant or review response still reserves this intent.
     */
    private static boolean hasUnresolvedGrant(PredictionEventStore current, WorkIntent intent) {
        return current.workGroupProjection().grants().stream().filter(grant -> grant.group().equals(intent.group()))
                .filter(grant -> !current.workGroupProjection().continuationGrant(grant.identity()))
                .filter(grant -> current.collaborationProjection().successorIntent(grant.targetIntent()).isEmpty())
                .filter(grant -> !current.workGroupProjection().grantSuperseded(grant.identity())).filter(grant ->
                        (grant.targetIntent().equals(intent.identity()) && grant.epoch().equals(intent.claimEpoch()))
                                || grant.targetParticipantIdentity().equals(intent.participantIdentity()))
                .anyMatch(grant -> current.workGroupProjection().grantAvailable(grant.identity()) || (
                        current.workGroupProjection().grantConsumed(grant.identity()) && current.workGroupProjection()
                                .reviewValidationForGrant(grant.identity()).isEmpty()));
    }

    /**
     * Checks whether a pending coordination request still refers to this intent or participant.
     */
    private static boolean hasPendingRequest(PredictionEventStore current, WorkIntent intent) {
        return current.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING && (
                        request.conflictingIntent().equals(intent.identity()) || request.requesterIdentity()
                                .equals(intent.participantIdentity()) || request.targetIdentity()
                                .equals(intent.participantIdentity())));
    }

    /**
     * Selects the lowest-version active successor matching snapshot task and authority lineage.
     */
    private static WorkIntent successorForSnapshot(PredictionEventStore current, TaskSnapshotRecord snapshot) {
        return current.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.task().equals(snapshot.taskIdentity()))
                .filter(intent -> intent.group().equals(snapshot.provenance().workGroupIdentity()))
                .filter(intent -> intent.lineage().equals(snapshot.provenance().lineageIdentity()))
                .filter(intent -> intent.intentVersion().value() > snapshot.provenance().claimEpochIdentity().value())
                .min(Comparator.comparingLong(WorkIntent::version)).orElse(null);
    }

    /**
     * Resolves the current intent and verifies the caller's exact completion binding.
     */
    private static WorkIntent validateNoChangeIntent(PredictionEventStore current, NoChangeCompletion completion)
            throws IOException {
        WorkIntent intent = current.collaborationProjection().intent(completion.intentIdentity())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        if (!current.projectIdentity().equals(intent.project())) {
            throw new CoordinationException("NO_CHANGE_PROJECT_MISMATCH");
        }
        if (intent.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("INTENT_NOT_ACTIVE");
        }
        if (!intent.group().equals(completion.workGroupIdentity()) || !intent.participantIdentity()
                .equals(completion.participantIdentity()) || !intent.providerIdentity()
                .equals(completion.providerIdentity()) || !intent.lineage().equals(completion.lineageIdentity())
                || !intent.claimEpoch().equals(completion.claimEpochIdentity()) || !intent.baseCommitIdentity()
                .equals(completion.workspaceCommitIdentity())) {
            throw new CoordinationException("NO_CHANGE_COMPLETION_BINDING_MISMATCH");
        }
        return intent;
    }

    /**
     * Verifies that this no-change completion can close its active work group.
     */
    private static void validateNoChangeGroup(PredictionEventStore current,
            WorkIntent intent,
            NoChangeCompletion completion) throws IOException {
        WorkGroup group = current.workGroupProjection().group(intent.group())
                .orElseThrow(() -> new CoordinationException("WORK_GROUP_NOT_FOUND"));
        if (group.status() != WorkGroup.Status.ACTIVE) {
            throw new CoordinationException("WORK_GROUP_NOT_ACTIVE");
        }
        if (!group.groupVersion().equals(completion.workGroupVersionIdentity())) {
            throw new CoordinationException("WORK_GROUP_VERSION_STALE");
        }
        if (hasNoChangeObligation(current, intent)) {
            throw new CoordinationException("NO_CHANGE_REVIEW_OBLIGATION");
        }
    }

    /**
     * Returns whether any active intent still belongs to the group.
     */
    private static boolean hasActiveGroupIntents(PredictionEventStore current, WorkGroupId workGroupId) {
        return current.collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> intent.group().equals(workGroupId));
    }

    /**
     * Returns whether a live or unreviewed grant can still add work to the group.
     */
    private static boolean hasUnresolvedGroupGrants(PredictionEventStore current, WorkGroupId workGroupId) {
        return current.workGroupProjection().grants().stream().filter(grant -> grant.group().equals(workGroupId))
                .filter(grant -> !current.workGroupProjection().continuationGrant(grant.identity()))
                .filter(grant -> current.collaborationProjection().successorIntent(grant.targetIntent()).isEmpty())
                .filter(grant -> !current.workGroupProjection().grantSuperseded(grant.identity()))
                .anyMatch(grant -> current.workGroupProjection().grantAvailable(grant.identity()) || (
                        current.workGroupProjection().grantConsumed(grant.identity()) && current.workGroupProjection()
                                .reviewValidationForGrant(grant.identity()).isEmpty()));
    }

    /**
     * Returns whether an outstanding request still refers to this group.
     */
    private static boolean hasPendingGroupRequests(PredictionEventStore current, WorkGroupId workGroupId) {
        return current.collaborationProjection().requests().stream()
                .filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .anyMatch(request -> current.collaborationProjection().intent(request.conflictingIntent())
                        .map(intent -> intent.group().equals(workGroupId))
                        .orElseGet(() -> current.collaborationProjection()
                                .noChangeCompletion(request.conflictingIntent())
                                .map(completion -> completion.workGroupIdentity().equals(workGroupId)).orElse(false)));
    }

    /**
     * Requires every group snapshot to be integrated or superseded by an integrated correction.
     */
    private static boolean allGroupSnapshotsIntegrated(PredictionEventStore current, WorkGroupId workGroupId) {
        for (var snapshot : current.taskCompletionProjection().allSnapshots()) {
            if (!snapshot.provenance().workGroupIdentity().equals(workGroupId)) {
                continue;
            }
            TaskCompletionState state = current.taskCompletionProjection().snapshotState(snapshot.snapshotIdentity())
                    .orElse(TaskCompletionState.ACTIVE);
            if (state == TaskCompletionState.REVIEW_REJECTED) {
                boolean corrected = current.taskCompletionProjection().allSnapshots().stream()
                        .anyMatch(candidate -> candidate.taskIdentity().equals(snapshot.taskIdentity())
                                && candidate.provenance().lineageIdentity()
                                .equals(snapshot.provenance().lineageIdentity())
                                && candidate.provenance().claimEpochIdentity().value() > snapshot.provenance()
                                .claimEpochIdentity().value() &&
                                current.taskCompletionProjection().snapshotState(candidate.snapshotIdentity())
                                        .orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.INTEGRATED);
                if (!corrected) {
                    return false;
                }
            } else if (state != TaskCompletionState.INTEGRATED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether prepared lane completions still lack integrated snapshots.
     */
    private static boolean hasUnintegratedPreparedCompletion(PredictionEventStore current, WorkGroupId workGroupId) {
        return current.taskCompletionProjection().allPrepared().stream()
                .anyMatch(prepared -> current.collaborationProjection().intent(prepared.laneIdentity())
                        .map(intent -> intent.group().equals(workGroupId) && current.taskCompletionProjection()
                                .findSnapshotForTaskRevision(intent.task(),
                                        intent.identity(),
                                        prepared.claimEpochIdentity()).map(snapshot ->
                                        current.taskCompletionProjection().snapshotState(snapshot.snapshotIdentity())
                                                .orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.INTEGRATED)
                                .orElse(true))
                        .orElseGet(() -> current.collaborationProjection().noChangeCompletion(prepared.laneIdentity())
                                .map(completion -> completion.workGroupIdentity().equals(workGroupId)).orElse(false)));
    }

    /**
     * Validates repair authority and builds the sole durable ownership-transfer payload.
     */
    private static RepairLanePayload validateRepairLane(PredictionEventStore current,
            WorkIntent source,
            WorkIntent targetIntent,
            SnapshotId snapshotId,
            CommitId expectedControlHead) throws IOException {
        if (source.status() != WorkIntent.Status.ANNOUNCED) {
            throw new CoordinationException("REPAIR_SOURCE_NOT_ACTIVE");
        }
        if (!source.project().equals(targetIntent.project()) || !source.group().equals(targetIntent.group())) {
            throw new CoordinationException("REPAIR_WORK_GROUP_MISMATCH");
        }
        if (!source.lineage().equals(targetIntent.lineage())) {
            throw new CoordinationException("REPAIR_AUTHORITY_LINEAGE_MISMATCH");
        }
        if (source.identity().equals(targetIntent.identity()) || source.participantIdentity()
                .equals(targetIntent.participantIdentity())) {
            throw new CoordinationException("REPAIR_TARGET_MUST_BE_DISTINCT");
        }
        if (!source.selectors().equals(targetIntent.selectors())) {
            throw new CoordinationException("REPAIR_SCOPE_MISMATCH");
        }
        if (targetIntent.intentVersion().value() != source.intentVersion().value() + 1L) {
            throw new CoordinationException("REPAIR_EPOCH_MISMATCH");
        }
        if (current.collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> intent.participantIdentity().equals(targetIntent.participantIdentity()))) {
            throw new CoordinationException("REPAIR_TARGET_ALREADY_ACTIVE");
        }
        return new RepairLanePayload(source.identity().value(),
                targetIntent,
                snapshotId.value(),
                expectedControlHead.value(),
                source.version(),
                targetIntent.version());
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
        CoordinationNullException.requireNonNull(intent, "intent");
        if (!store.projectId().equals(intent.projectId())) {
            throw new CoordinationArgumentException("intent project mismatch");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            List<ClaimConflict> conflicts = current.collaborationProjection().conflicts(intent.selectors());
            if (!conflicts.isEmpty()) {
                return new ClaimResult(false, intent, conflicts);
            }
            WorkGroup existingGroup = current.workGroupProjection().group(intent.group()).orElse(null);
            if (existingGroup != null && existingGroup.status() != WorkGroup.Status.ACTIVE) {
                throw new CoordinationException("WORK_GROUP_NOT_ACTIVE");
            }
            if (existingGroup == null) {
                WorkGroup group = new WorkGroup(intent.group().value(),
                        intent.project().value(),
                        intent.goal(),
                        intent.acceptance(),
                        1,
                        WorkGroup.Status.ACTIVE);
                current.append(group.identity().value(),
                        PredictionEventType.WORK_GROUP_CREATED,
                        signer.nodeId(),
                        CollaborationCodec.encodeWorkGroup(group),
                        signer);
            }
            current.append(intent.identity().value(),
                    PredictionEventType.WORK_INTENT_ANNOUNCED,
                    signer.nodeId(),
                    CollaborationCodec.encodeIntent(intent),
                    signer);
            return new ClaimResult(true, intent, List.of());
        }
    }

    /**
     * Extends an active lane under its exact intent and claim-epoch preconditions.
     * Existing claims and dependencies are retained; task text changes only when supplied.
     *
     * @param intentId exact active intent
     * @param participant exact authenticated participant incarnation
     * @param expectedEpoch current claim epoch observed by the caller
     * @param requestedSelectors selectors requested by this declaration
     * @param goal replacement goal, or {@code null} to retain the current value
     * @param acceptance replacement acceptance, or {@code null} to retain the current value
     * @param requestedDependencies explicitly requested capability dependencies
     * @return acquisition result; conflicts reject the complete extension
     * @throws IOException persistence or stale-authority failure
     * @throws GeneralSecurityException event signing failure
     */
    public synchronized ClaimResult extendScope(WorkIntentId intentId,
            ParticipantId participant,
            ClaimEpoch expectedEpoch,
            List<ResourceSelector> requestedSelectors,
            String goal,
            String acceptance,
            List<String> requestedDependencies) throws IOException, GeneralSecurityException {
        WorkIntentId exactIntent = CoordinationNullException.requireNonNull(intentId, "intent ID");
        ParticipantId exactParticipant = CoordinationNullException.requireNonNull(participant, "participant");
        ClaimEpoch exactEpoch = CoordinationNullException.requireNonNull(expectedEpoch, "claim epoch");
        List<ResourceSelector> selectors =
                List.copyOf(CoordinationNullException.requireNonNull(requestedSelectors, "selectors"));
        List<String> dependencies = List.copyOf(CoordinationNullException.requireNonNull(requestedDependencies,
                "dependencies"));
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore currentStore = freshStore();
            WorkIntent current = currentStore.collaborationProjection().intent(exactIntent)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            if (!current.participantIdentity().equals(exactParticipant)) {
                throw new CoordinationException("INTENT_OWNER_MISMATCH");
            }
            if (currentStore.collaborationProjection().participantState(exactParticipant)
                    .filter(state -> state == Participant.State.ACTIVE)
                    .isEmpty()) {
                throw new CoordinationException("INTENT_NOT_ACTIVE");
            }
            if (current.claimEpoch().value() != exactEpoch.value()) {
                throw new CoordinationException("CLAIM_EPOCH_STALE");
            }
            List<ResourceSelector> mergedSelectors = new ArrayList<>(current.selectors());
            selectors.stream().filter(selector -> !mergedSelectors.contains(selector)).forEach(mergedSelectors::add);
            List<String> mergedDependencies = new ArrayList<>(current.knownDependencies());
            dependencies.stream().filter(dependency -> !mergedDependencies.contains(dependency))
                    .forEach(mergedDependencies::add);
            if (mergedSelectors.size() > 128) {
                throw new CoordinationException("SCOPE_EXTENSION_CLAIM_LIMIT_EXCEEDED");
            }
            if (mergedDependencies.size() > 50) {
                throw new CoordinationException("SCOPE_EXTENSION_DEPENDENCY_LIMIT_EXCEEDED");
            }
            String updatedGoal = goal == null ? current.goal() : goal;
            String updatedAcceptance = acceptance == null ? current.acceptance() : acceptance;
            if (mergedSelectors.equals(current.selectors()) && mergedDependencies.equals(current.knownDependencies())
                    && updatedGoal.equals(current.goal()) && updatedAcceptance.equals(current.acceptance())) {
                return new ClaimResult(true, current, List.of());
            }
            WorkIntent amended = new WorkIntent(current.intentId(),
                    current.projectId(),
                    current.participant(),
                    current.provider(),
                    current.taskId(),
                    updatedGoal,
                    updatedAcceptance,
                    current.baseCommit(),
                    List.copyOf(mergedSelectors),
                    current.version() + 1,
                    current.workGroupId(),
                    current.authorityLineageId(),
                    current.status(),
                    current.role(),
                    current.reviewTargetSelectors(),
                    List.copyOf(mergedDependencies));
            List<ClaimConflict> conflicts = currentStore.collaborationProjection()
                    .conflicts(amended.selectors(), current.identity());
            if (!conflicts.isEmpty()) {
                return new ClaimResult(false, current, conflicts);
            }
            WorkIntentScopeExtension extension = new WorkIntentScopeExtension(amended, current.version());
            currentStore.append(current.identity().value(),
                    PredictionEventType.WORK_INTENT_SCOPE_EXTENDED,
                    signer.nodeId(),
                    extension.encode(),
                    signer);
            return new ClaimResult(true, amended, List.of());
        }
    }

    /**
     * Releases an intent owned by the signing participant.
     *
     * @param intentId    intent identifier
     * @param participant participant handle
     * @throws IOException              when the intent is missing or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public synchronized void release(UUID intentId, String participant) throws IOException, GeneralSecurityException {
        release(new WorkIntentId(intentId), new ParticipantId(participant));
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
    public synchronized void release(UUID intentId, String participant, WorkIntentMutationPrecondition precondition)
            throws IOException, GeneralSecurityException {
        release(new WorkIntentId(intentId), new ParticipantId(participant), precondition);
    }

    /**
     * Releases the intent identified by a typed identity when the typed participant owns it.
     *
     * @param intentId    exact intent identity
     * @param participant exact participant incarnation
     * @throws IOException              when the intent is missing, stale, or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public synchronized void release(WorkIntentId intentId, ParticipantId participant)
            throws IOException, GeneralSecurityException {
        release(intentId, participant, null);
    }

    /**
     * Releases an intent after checking its typed identity and optimistic mutation precondition.
     *
     * @param intentId     exact intent identity
     * @param participant  exact participant incarnation
     * @param precondition expected immutable intent state, or {@code null}
     * @throws IOException              when the intent is missing, stale, or persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public synchronized void release(WorkIntentId intentId,
            ParticipantId participant,
            WorkIntentMutationPrecondition precondition) throws IOException, GeneralSecurityException {
        WorkIntentId intentIdentity = CoordinationNullException.requireNonNull(intentId, "intent ID");
        UUID intentUuid = intentIdentity.value();
        ParticipantId participantIdentity = CoordinationNullException.requireNonNull(participant, "participant");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            WorkIntent intent = current.collaborationProjection().intent(intentIdentity)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            intent.requireOwnedBy(participantIdentity);
            if (precondition != null) {
                precondition.requireMatches(intent);
            }
            current.append(intentUuid,
                    PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(),
                    CollaborationCodec.encodeRelease(intentUuid),
                    signer);
        }
    }

    /**
     * Releases an exactly integrated lane while the caller holds the project append lock.
     *
     * <p>The snapshot provenance is the optimistic authority proof. A stale
     * revision, different participant, different lineage, or non-integrated snapshot fails closed. The event ordering
     * is deliberately
     * integration first, then intent release, so a rejected or pending snapshot cannot terminalize its lane.</p>
     *
     * @param lock     already-held project append lock
     * @param snapshot exact snapshot whose integration succeeded
     * @return {@code true} when an active intent was released; {@code false} when the lane was already released
     * @throws IOException              stale or unauthorized snapshot/lane
     * @throws GeneralSecurityException signing failure
     */
    public synchronized boolean releaseAfterIntegration(ProjectAppendLock lock, TaskSnapshotRecord snapshot)
            throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(lock, "project append lock");
        CoordinationNullException.requireNonNull(snapshot, "snapshot");
        if (!lock.isHeld()) {
            throw new CoordinationException("event append lock unavailable");
        }
        PredictionEventStore current = freshStore();
        TaskCompletionState snapshotState = current.taskCompletionProjection()
                .snapshotState(snapshot.snapshotIdentity()).orElse(TaskCompletionState.ACTIVE);
        if (snapshotState != TaskCompletionState.INTEGRATED) {
            throw new CoordinationException("INTEGRATED_SNAPSHOT_REQUIRED");
        }
        WorkIntent exactIntent = current.collaborationProjection().intent(snapshot.provenance().laneIdentity())
                .orElse(null);
        WorkIntent intent = exactIntent == null ? successorForSnapshot(current, snapshot) : exactIntent;
        if (intent == null) {
            return false;
        }
        boolean successor = exactIntent == null;
        if (intent.status() != WorkIntent.Status.ANNOUNCED || !intent.taskId().equals(snapshot.taskId())
                || !intent.workGroupId().equals(snapshot.provenance().workGroupId()) || !intent.authorityLineageId()
                .equals(snapshot.provenance().authorityLineageId()) || (successor ? intent.version()
                                                                                    <= snapshot.provenance()
                                                                                       .claimEpoch()
                : !intent.participant().equals(snapshot.provenance().participant())
                  || intent.version() != snapshot.provenance().claimEpoch())) {
            throw new CoordinationException("INTEGRATED_LANE_BINDING_MISMATCH");
        }
        current.append(intent.intentId(),
                PredictionEventType.WORK_INTENT_RELEASED,
                signer.nodeId(),
                CollaborationCodec.encodeRelease(intent.intentId()),
                signer);
        reevaluateNoChangeWorkGroup(current, intent.group());
        return true;
    }

    /**
     * Completes one explicitly requested no-change outcome and reevaluates its group.
     *
     * <p>This is the only coordination transition that may release an intent
     * without a snapshot. The caller supplies optimistic evidence, while this service verifies the durable intent,
     * group, epoch, and event
     * revision under the project append lock. A repeated identical completion is a read-only replay and never appends a
     * second terminal
     * event.</p>
     *
     * @param completion exact no-change completion evidence
     * @return the durable completion evidence
     * @throws IOException              stale, unauthorized, or pending lifecycle state
     * @throws GeneralSecurityException signing failure
     */
    public synchronized NoChangeCompletion completeNoChange(NoChangeCompletion completion)
            throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(completion, "completion");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            NoChangeCompletion previous = current.collaborationProjection()
                    .noChangeCompletion(completion.intentIdentity()).orElse(null);
            if (previous != null) {
                if (!previous.equals(completion)) {
                    throw new CoordinationException("NO_CHANGE_COMPLETION_CONFLICT");
                }
                reevaluateNoChangeWorkGroup(current, completion.workGroupIdentity());
                return previous;
            }
            if (!current.unchangedSince(completion.expectedRevision())) {
                throw new CoordinationException("COMPLETION_REVISION_STALE");
            }
            WorkIntent intent = validateNoChangeIntent(current, completion);
            validateNoChangeGroup(current, intent, completion);
            current.append(intent.intentId(),
                    PredictionEventType.WORK_INTENT_RELEASED,
                    signer.nodeId(),
                    CollaborationCodec.encodeNoChangeCompletion(completion),
                    signer);
            reevaluateNoChangeWorkGroup(current, intent.group());
            return completion;
        }
    }

    /**
     * Completes the group only after active intents and all lifecycle obligations are resolved.
     *
     * @param current fresh authoritative project projection
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void reevaluateNoChangeWorkGroup(PredictionEventStore current, WorkGroupId workGroupId)
            throws IOException, GeneralSecurityException {
        WorkGroup group = current.workGroupProjection().group(workGroupId).orElse(null);
        if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
            return;
        }
        if (hasActiveGroupIntents(current, workGroupId) || hasUnresolvedGroupGrants(current, workGroupId)
                || hasPendingGroupRequests(current, workGroupId) || !allGroupSnapshotsIntegrated(current, workGroupId)
                || hasUnintegratedPreparedCompletion(current, workGroupId)) {
            return;
        }
        WorkGroup completed = new WorkGroup(group.identity().value(),
                group.projectId(),
                group.goal(),
                group.acceptance(),
                group.version() + 1L,
                WorkGroup.Status.COMPLETED,
                group.reviewPolicy());
        current.append(group.identity().value(),
                PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                signer.nodeId(),
                CollaborationCodec.encodeWorkGroup(completed),
                signer);
    }

    /**
     * Atomically transfers reserved selectors into a new repair lane.
     *
     * <p>The event is the sole ownership transition: the source remains the
     * owner until the signed event is durably appended, and projection replay removes the source and announces the
     * target as one transition.
     * Repeating the same operation after a lost response is idempotent.</p>
     *
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot identifier
     * @param expectedControlHead control HEAD used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid source, stale epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public synchronized void createRepairLane(UUID sourceIntentId,
            String snapshotId,
            String expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        createRepairLane(new WorkIntentId(sourceIntentId),
                new SnapshotId(snapshotId),
                new CommitId(expectedControlHead),
                targetIntent);
    }

    /**
     * Transfers the lane using typed source, snapshot, and control-head identities.
     *
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot
     * @param expectedControlHead control commit used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid source, stale epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public synchronized void createRepairLane(WorkIntentId sourceIntentId,
            SnapshotId snapshotId,
            CommitId expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            createRepairLaneLocked(sourceIntentId, snapshotId, expectedControlHead, targetIntent);
        }
    }

    /**
     * Atomically transfers repair scope while the caller holds the project append lock used to serialize Git
     * integration and event
     * publication.
     *
     * @param lock                already-held project append lock
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot identifier
     * @param expectedControlHead control HEAD used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid lock, source, epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public synchronized void createRepairLane(ProjectAppendLock lock,
            UUID sourceIntentId,
            String snapshotId,
            String expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        createRepairLane(lock,
                new WorkIntentId(sourceIntentId),
                new SnapshotId(snapshotId),
                new CommitId(expectedControlHead),
                targetIntent);
    }

    /**
     * Transfers repair scope under the held append lock using typed identities.
     *
     * @param lock                already-held project append lock
     * @param sourceIntentId      published source lane
     * @param snapshotId          immutable conflicting snapshot
     * @param expectedControlHead control commit used for materialization
     * @param targetIntent        new repair lane intent
     * @throws IOException              invalid lock, source, epoch, lineage, scope, or target
     * @throws GeneralSecurityException signing failure
     */
    public synchronized void createRepairLane(ProjectAppendLock lock,
            WorkIntentId sourceIntentId,
            SnapshotId snapshotId,
            CommitId expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(lock, "project append lock");
        if (!lock.isHeld()) {
            throw new CoordinationException("event append lock unavailable");
        }
        createRepairLaneLocked(sourceIntentId, snapshotId, expectedControlHead, targetIntent);
    }

    /**
     * Applies the create repair lane locked transition to the current coordination projection.
     *
     * @param targetIntent proposed target intent
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void createRepairLaneLocked(WorkIntentId sourceIntentId,
            SnapshotId snapshotId,
            CommitId expectedControlHead,
            WorkIntent targetIntent) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(sourceIntentId, "source intent");
        CoordinationNullException.requireNonNull(snapshotId, "snapshot ID");
        CoordinationNullException.requireNonNull(expectedControlHead, "expected control HEAD");
        CoordinationNullException.requireNonNull(targetIntent, "target intent");
        if (snapshotId.value().isBlank() || expectedControlHead.value().isBlank()) {
            throw new CoordinationException("REPAIR_METADATA_REQUIRED");
        }
        PredictionEventStore current = freshStore();
        var existingTarget = current.collaborationProjection().intent(targetIntent.identity());
        if (existingTarget.isPresent()) {
            if (!existingTarget.orElseThrow(CoordinationMissingElementException::new).equals(targetIntent)) {
                throw new CoordinationException("REPAIR_TARGET_MISMATCH");
            }
            return;
        }
        WorkIntent source = current.collaborationProjection().intent(sourceIntentId)
                .orElseThrow(() -> new CoordinationException("REPAIR_SOURCE_NOT_FOUND"));
        RepairLanePayload payload = validateRepairLane(current, source, targetIntent, snapshotId, expectedControlHead);
        UUID eventId = UUID.nameUUIDFromBytes(("repair-lane:" + sourceIntentId.value() + ":" + targetIntent.identity()
                .value() + ":" + snapshotId.value()).getBytes(StandardCharsets.UTF_8));
        current.append(eventId, PredictionEventType.REPAIR_LANE_CREATED, signer.nodeId(), payload.encode(), signer);
    }

    /**
     * Returns whether a participant owns a compatible selector.
     *
     * @param participant participant handle
     * @param selector    target selector
     * @return true when the participant owns an overlapping active claim
     */
    public boolean owns(String participant, ResourceSelector selector) {
        return owns(new ParticipantId(participant), selector);
    }

    /**
     * Returns whether this typed participant owns a claim overlapping the selector.
     *
     * @param participant exact participant identity
     * @param selector    resource selector to compare with active claims
     * @return whether this typed participant owns a claim overlapping the selector.
     */
    public boolean owns(ParticipantId participant, ResourceSelector selector) {
        ParticipantId owner = CoordinationNullException.requireNonNull(participant, "participant");
        return freshIntents().stream().filter(intent -> intent.participantIdentity().equals(owner))
                .anyMatch(intent -> intent.selectors().stream().anyMatch(selector::overlaps));
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
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(String requester,
            UUID conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal) throws IOException, GeneralSecurityException {
        return request(new ParticipantId(requester), new WorkIntentId(conflictingIntentId), kind, proposal);
    }

    /**
     * Opens a request using the exact requester and conflicting-intent identities.
     *
     * @param requester           authenticated requesting participant
     * @param conflictingIntentId conflicting work-intent lane
     * @param kind                request kind
     * @param proposal            request proposal
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(ParticipantId requester,
            WorkIntentId conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal) throws IOException, GeneralSecurityException {
        return coordinationRequests.request(requester, conflictingIntentId, kind, proposal);
    }

    /**
     * Opens a negotiation request, optionally bound to one exact immutable review snapshot.
     *
     * @param requester           requester ID
     * @param conflictingIntentId conflicting intent
     * @param kind                request kind
     * @param proposal            proposal
     * @param snapshotId          exact review snapshot
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(String requester,
            UUID conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal,
            String snapshotId) throws IOException, GeneralSecurityException {
        return request(new ParticipantId(requester),
                new WorkIntentId(conflictingIntentId),
                kind,
                proposal,
                snapshotId == null ? null : new SnapshotId(snapshotId));
    }

    /**
     * Opens a request optionally bound to one exact typed snapshot identity.
     *
     * @param requester           authenticated requesting participant
     * @param conflictingIntentId conflicting work-intent lane
     * @param kind                request kind
     * @param proposal            request proposal
     * @param snapshotId          exact review snapshot, or {@code null}
     * @return durable request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest request(ParticipantId requester,
            WorkIntentId conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal,
            SnapshotId snapshotId) throws IOException, GeneralSecurityException {
        return coordinationRequests.request(requester, conflictingIntentId, kind, proposal, snapshotId);
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
        return offerHandoff(new ParticipantId(owner), new WorkIntentId(intentId), new ParticipantId(target), proposal);
    }

    /**
     * Offers an atomic handoff between exact participant and intent identities.
     *
     * @param owner    current owning participant
     * @param intentId intent to hand off
     * @param target   participant receiving the handoff
     * @param proposal handoff proposal
     * @return durable pending request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public CoordinationRequest offerHandoff(ParticipantId owner,
            WorkIntentId intentId,
            ParticipantId target,
            String proposal) throws IOException, GeneralSecurityException {
        return coordinationRequests.offerHandoff(owner, intentId, target, proposal);
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
        respond(new ParticipantId(participant), new RequestId(requestId), status, proposal);
    }

    /**
     * Responds idempotently using typed participant and request identities.
     *
     * @param participant exact participant identity
     * @param requestId   coordination request identity
     * @param status      projected status
     * @param proposal    proposed resolution or updated terms
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void respond(ParticipantId participant,
            RequestId requestId,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        coordinationRequests.respond(participant, requestId, status, proposal);
    }

    /**
     * Acknowledges a server-issued inbox item for the exact participant.
     *
     * @param participant exact participant handle
     * @param itemId      server-issued request/inbox item ID
     * @throws IOException              persistence or authorization failure
     * @throws GeneralSecurityException signing failure
     */
    public void acknowledgeInbox(String participant, UUID itemId) throws IOException, GeneralSecurityException {
        acknowledgeInbox(new ParticipantId(participant), new RequestId(itemId));
    }

    /**
     * Acknowledges a server-issued item for one exact participant incarnation.
     *
     * @param participant exact participant identity
     * @param itemId      server-issued inbox item identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void acknowledgeInbox(ParticipantId participant, RequestId itemId)
            throws IOException, GeneralSecurityException {
        coordinationRequests.acknowledgeInbox(participant, itemId);
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
        resolveInbox(new ParticipantId(participant), new RequestId(itemId), status, proposal);
    }

    /**
     * Resolves and acknowledges an inbox item using typed participant and item identities.
     *
     * @param participant exact participant identity
     * @param itemId      server-issued inbox item identity
     * @param status      projected status
     * @param proposal    proposed resolution or updated terms
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void resolveInbox(ParticipantId participant,
            RequestId itemId,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        coordinationRequests.resolveInbox(participant, itemId, status, proposal);
    }

    /**
     * Returns durable coordination requests for discovery.
     *
     * @return requests
     */
    public List<CoordinationRequest> requests() {
        return coordinationRequests.requests();
    }

    /**
     * Appends a signed verified-activity heartbeat for an active participant.
     *
     * @param participant participant ID
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    public void heartbeat(String participant) throws IOException, GeneralSecurityException {
        heartbeat(new ParticipantId(participant));
    }

    /**
     * Appends a verified-activity heartbeat for the exact typed participant identity.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void heartbeat(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.heartbeat(participant);
    }

    /**
     * Fences a participant after verified process loss without releasing its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void suspend(String participant) throws IOException, GeneralSecurityException {
        suspend(new ParticipantId(participant));
    }

    /**
     * Suspends one exact participant incarnation while retaining its claims.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void suspend(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.suspend(participant);
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
        holdRecovery(new ParticipantId(participant), new SnapshotReference(snapshotReference));
    }

    /**
     * Holds recovery evidence for an exact participant and snapshot reference.
     *
     * @param participant       exact participant identity
     * @param snapshotReference immutable snapshot reference supporting recovery evidence
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void holdRecovery(ParticipantId participant, SnapshotReference snapshotReference)
            throws IOException, GeneralSecurityException {
        participantRecovery.holdRecovery(participant, snapshotReference);
    }

    /**
     * Explicitly cancels a participant lane and releases its claims.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void cancel(String participant) throws IOException, GeneralSecurityException {
        cancel(new ParticipantId(participant));
    }

    /**
     * Cancels one exact participant incarnation.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void cancel(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.cancel(participant);
    }

    /**
     * Records a clean connection shutdown and releases the lane without completing its task.
     *
     * @param participant participant handle
     * @throws IOException              if persistence or validation fails
     * @throws GeneralSecurityException if signing fails
     */
    public void detach(String participant) throws IOException, GeneralSecurityException {
        detach(new ParticipantId(participant));
    }

    /**
     * Records clean detachment of one exact participant incarnation.
     *
     * @param participant exact participant identity
     * @throws IOException              when persistence, authorization, or transition validation fails
     * @throws GeneralSecurityException when event signing or verification fails
     */
    public void detach(ParticipantId participant) throws IOException, GeneralSecurityException {
        participantRecovery.detach(participant);
    }

    /**
     * Atomically transfers a held recovery lane to a newly authenticated participant.
     *
     * @param continuation continuation payload
     * @return durable continuation result, including replay state
     * @throws IOException              stale grant, epoch, participant, or projection state
     * @throws GeneralSecurityException signing failure
     */
    public ContinuationResult continueFromRecovery(LaneContinuation continuation)
            throws IOException, GeneralSecurityException {
        return participantRecovery.continueFromRecovery(continuation);
    }

    /**
     * Explicitly acquires a recovery-held lane for a fresh authenticated participant.
     *
     * <p>The grant is derived and issued only inside the same append-lock critical section as the
     * continuation event. Read-only eligibility therefore never assigns a successor, while two callers racing on one
     * source epoch cannot both
     * transfer its claims.</p>
     *
     * @param continuation successor request with a deterministic single-use grant identity
     * @return durable continuation result, including replay state
     * @throws IOException              stale, conflicting, or invalid continuation
     * @throws GeneralSecurityException signing failure
     */
    public ContinuationResult continueEligibleFromRecovery(LaneContinuation continuation)
            throws IOException, GeneralSecurityException {
        return participantRecovery.continueEligibleFromRecovery(continuation);
    }

    /**
     * Returns active intents from a fresh replay of the durable project event log.
     */
    private List<WorkIntent> freshIntents() {
        try {
            return freshStore().collaborationProjection().activeIntents();
        } catch (Exception failure) {
            throw new CoordinationStateException("COLLABORATION_STATE_UNAVAILABLE", failure);
        }
    }

    /**
     * Replays a fresh view of the project's durable coordination state.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private PredictionEventStore freshStore() throws IOException, GeneralSecurityException {
        return new PredictionEventStore(store.rootDirectory(), store.projectId());
    }

}
