package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationException;

/**
 * The rules that decide who may claim, act, review, finish or cancel, checked against the projections before an
 * event is written.
 *
 * <p>These rules sit here and not in the services because the service that runs them is not always the one that
 * decides. A member computer runs the services on its copy of the leader's log and proposes the entry; the leader
 * accepts it only if its projections accept it (ADR-0098). A service on one computer can also be working from state
 * that moved since it looked, when it appends without the project append lock. A rule in the projection holds on
 * every entry, wherever it came from. The services keep the same checks as an early answer with a better context;
 * they call the same functions, so there is one rule, not two.</p>
 *
 * <p>The rules are checked at append time only. They are not part of replay: a log that holds an entry from before
 * a rule existed still replays, because replay never asks whether an entry would be admitted today.</p>
 *
 * <p>What is not here is what the log cannot say: who the caller of a tool is (a session binding, not an event
 * field), whether a Git commit or a worktree is what the caller claims, and the shape of an input. Those stay in
 * the services.</p>
 */
public final class AppendAdmission {

    private static final String CLAIM_EVIDENCE_STALE = "SNAPSHOT_COMPLETION_EVIDENCE_STALE";

    private AppendAdmission() {
    }

    /**
     * Refuses the event when it breaks a rule that guards authority.
     *
     * @param event signed event about to be written; the projections have not applied it
     * @param view  projections as they stand before the event
     * @throws IOException with the refusal code when the event is not admitted
     */
    public static void check(PredictionEvent event, ProjectionView view) throws IOException {
        byte[] payload = event.payload();
        switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> announced(CollaborationCodec.decodeIntent(payload), view);
            case WORK_INTENT_RELEASED -> {
                if (CollaborationCodec.isNoChangeCompletion(payload)) {
                    finishedWithoutChange(CollaborationCodec.decodeNoChangeCompletion(payload), view);
                }
            }
            case COORDINATION_REQUESTED -> requested(CollaborationCodec.decodeRequest(payload), view);
            case COORDINATION_RESPONDED -> responded(CollaborationCodec.decodeResponse(payload), view);
            case LANE_GRANT_ISSUED -> issued(CollaborationCodec.decodeLaneGrant(payload), view);
            case LANE_GRANT_CONSUMED -> consumed(CollaborationCodec.decodeLaneGrant(payload), view);
            case LANE_CONTINUATION_ACCEPTED -> continued(CollaborationCodec.decodeContinuation(payload), view);
            case WORK_GROUP_STATUS_CHANGED -> groupStatusChanged(CollaborationCodec.decodeWorkGroup(payload));
            case REVIEW_VALIDATION_RECORDED -> reviewed(ReviewValidationPayload.decode(payload), view);
            case COMPLETION_PREPARED -> prepared(CompletionPreparedPayload.decode(payload), view);
            case TASK_SNAPSHOT_CREATED -> snapshotted(TaskSnapshotPayload.decode(payload), view);
            case TASK_CANCELLATION_REQUESTED, TASK_CANCELLED -> cancelled(event, view);
            case CAPABILITY_IMPLEMENTATION_PUBLISHED ->
                    published(ImplementationEventPayload.decode(payload), view);
            default -> {
            }
        }
    }

    /**
     * Refuses a grant that no longer points at something its holder can still act on: the lane it names has a
     * successor, or the review snapshot it names is not waiting for this review any more.
     *
     * @param view  projections
     * @param grant the grant as the log holds it
     * @throws IOException with {@code LANE_GRANT_TARGET_STALE} or {@code REVIEW_SNAPSHOT_MISMATCH}
     */
    public static void requireGrantTargetCurrent(ProjectionView view, LaneGrant grant) throws IOException {
        if (grant.snapshotId() == null) {
            if (view.collaborationProjection().successorIntent(grant.targetIntent()).isPresent()) {
                throw new CoordinationException("LANE_GRANT_TARGET_STALE");
            }
            return;
        }
        SnapshotId snapshotId = grant.snapshot()
                .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
        TaskSnapshotRecord snapshot = view.taskCompletionProjection().findSnapshotById(snapshotId)
                .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
        if (!snapshot.reviewRequired() || !snapshot.provenance().workGroupIdentity().equals(grant.group())
                || !snapshot.provenance().laneIdentity().equals(grant.targetIntent()) || !snapshot.provenance()
                .claimEpochIdentity().equals(grant.epoch()) || snapshot.provenance().participantIdentity()
                .equals(grant.targetParticipantIdentity())
                || view.taskCompletionProjection().snapshotState(snapshot.snapshotIdentity())
                .orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.REVIEW_PENDING
                || view.workGroupProjection().reviewValidationForSnapshot(snapshot.snapshotIdentity()).isPresent()) {
            throw new CoordinationException("REVIEW_SNAPSHOT_MISMATCH");
        }
    }

    /**
     * Revalidates a continuation against its source lane: owner, claim epoch, recovery hold and snapshot, work
     * group, grant, and that the successor is not the other side of a review.
     *
     * @param view         projections
     * @param continuation the proposed continuation
     * @param requireGrant whether the grant must already be on the log
     * @throws IOException with the refusal code
     */
    public static void requireLawfulContinuation(ProjectionView view, LaneContinuation continuation,
            boolean requireGrant) throws IOException {
        CollaborationProjection collaboration = view.collaborationProjection();
        WorkIntent source = collaboration.intent(continuation.sourceIntent())
                .orElseThrow(() -> new CoordinationException("CONTINUATION_SOURCE_NOT_FOUND"));
        if (!source.participantIdentity().equals(continuation.sourceOwner())) {
            throw new CoordinationException("CONTINUATION_SOURCE_MISMATCH");
        }
        if (!source.claimEpoch().equals(continuation.claimEpoch())) {
            throw new CoordinationException("CLAIM_EPOCH_STALE");
        }
        boolean recoveryHeld = collaboration.participantState(continuation.sourceOwner())
                .filter(state -> state == Participant.State.RECOVERY_HELD).isPresent();
        if (!recoveryHeld) {
            throw new CoordinationException("RECOVERY_NOT_HELD");
        }
        if (collaboration.recoverySnapshotReference(continuation.sourceOwner())
                .filter(continuation.recoverySnapshot()::equals).isEmpty()) {
            throw new CoordinationException("RECOVERY_SNAPSHOT_MISMATCH");
        }
        WorkGroup group = view.workGroupProjection().group(source.group())
                .orElseThrow(() -> new CoordinationException("WORK_GROUP_NOT_FOUND"));
        if (group.status() != WorkGroup.Status.ACTIVE) {
            throw new CoordinationException("WORK_GROUP_NOT_ACTIVE");
        }
        LaneGrant grant = view.workGroupProjection().grants().stream()
                .filter(candidate -> candidate.identity().equals(continuation.grant())).findFirst().orElse(null);
        if (grant == null && requireGrant) {
            throw new CoordinationException("LANE_GRANT_NOT_FOUND");
        }
        if (grant != null && (!grant.singleUse() || !grant.epoch().equals(continuation.claimEpoch())
                || !grant.targetParticipantIdentity().equals(continuation.targetOwner()) || !grant.targetIntent()
                .equals(continuation.targetIntent().identity()) || !grant.group().equals(source.group()))) {
            throw new CoordinationException("LANE_GRANT_TARGET_MISMATCH");
        }
        requireRoleIsolation(collaboration, source, continuation);
    }

    private static void announced(WorkIntent intent, ProjectionView view) throws IOException {
        WorkGroup group = view.workGroupProjection().group(intent.group()).orElse(null);
        if (group != null && group.status() != WorkGroup.Status.ACTIVE) {
            throw new CoordinationException("WORK_GROUP_NOT_ACTIVE");
        }
        ReviewAdmissionRules.requireProducerToReview(view, intent);
    }

    private static void finishedWithoutChange(NoChangeCompletion completion, ProjectionView view)
            throws IOException {
        // A repeat of a recorded completion is a replay; the collaboration projection compares it.
        if (view.collaborationProjection().noChangeCompletion(completion.intentIdentity()).isPresent()) {
            return;
        }
        WorkIntent intent = LaneCompletionRules.validateNoChangeIntent(view, completion);
        LaneCompletionRules.validateNoChangeGroup(view, intent, completion);
    }

    private static void requested(CoordinationRequest request, ProjectionView view) throws IOException {
        if (request.kind() != CoordinationRequest.Kind.REVIEW && request.kind() != CoordinationRequest.Kind.HANDOFF) {
            return;
        }
        WorkIntent target = view.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        if (request.kind() == CoordinationRequest.Kind.REVIEW) {
            ReviewAdmissionRules.validateNewReviewRequest(view, request, target);
            return;
        }
        // A handoff is offered by the lane's owner to a participant that is active.
        target.requireOwnedBy(request.requesterIdentity());
        CollaborationProjection collaboration = view.collaborationProjection();
        boolean activeTarget = collaboration.participants().stream()
                .anyMatch(participant -> participant.identity().equals(request.targetIdentity())
                        && participant.state() == Participant.State.ACTIVE) || collaboration.activeIntents().stream()
                .anyMatch(candidate -> candidate.participantIdentity().equals(request.targetIdentity()));
        if (!activeTarget) {
            throw new CoordinationException("HANDOFF_TARGET_NOT_ACTIVE");
        }
    }

    private static void responded(CoordinationResponsePayload response, ProjectionView view) throws IOException {
        CoordinationRequest request = view.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.identity().equals(response.request())).findFirst().orElse(null);
        if (request != null) {
            ReviewAdmissionRules.validateReviewApproval(view, request, response.status());
        }
    }

    /**
     * A grant a review request produced is issued only to the lane's current owner, for a review that is still
     * lawful. Other grants have no request behind them and nothing to check beyond the work group.
     */
    private static void issued(LaneGrant grant, ProjectionView view) throws IOException {
        Optional<CoordinationRequest> reviewRequest = view.collaborationProjection().requests().stream()
                .filter(request -> request.kind() == CoordinationRequest.Kind.REVIEW)
                .filter(request -> LaneGrant.reviewGrantId(request.requestId()).equals(grant.grantId()))
                .findFirst();
        if (reviewRequest.isEmpty()) {
            return;
        }
        CoordinationRequest request = reviewRequest.orElseThrow();
        WorkIntent intent = view.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        if (view.collaborationProjection().successorIntent(intent.identity()).isPresent()) {
            throw new CoordinationException("REVIEW_TARGET_STALE");
        }
        ReviewAdmissionRules.validateReviewRequest(view, request, intent);
        if (!request.targetIdentity().equals(intent.participantIdentity())) {
            throw new CoordinationException("REVIEW_TARGET_PARTICIPANT_MISMATCH");
        }
    }

    private static void consumed(LaneGrant eventGrant, ProjectionView view) throws IOException {
        LaneGrantId id = eventGrant.identity();
        // The grant is read from the log, not trusted from the entry.
        LaneGrant grant = view.workGroupProjection().grants().stream()
                .filter(candidate -> candidate.identity().equals(id)).findFirst()
                .orElseThrow(() -> new CoordinationException("LANE_GRANT_NOT_FOUND"));
        if (!view.workGroupProjection().grantAvailable(id)) {
            throw new CoordinationException("LANE_GRANT_REPLAYED");
        }
        requireGrantTargetCurrent(view, grant);
    }

    private static void continued(LaneContinuation continuation, ProjectionView view) throws IOException {
        requireLawfulContinuation(view, continuation, false);
        WorkIntent target = continuation.targetIntent();
        WorkIntent source = view.collaborationProjection().intent(continuation.sourceIntent()).orElseThrow();
        if (target.status() != WorkIntent.Status.ANNOUNCED
                || target.participantIdentity().equals(source.participantIdentity())) {
            throw new CoordinationException("CONTINUATION_TARGET_INVALID");
        }
    }

    private static void groupStatusChanged(WorkGroup update) throws IOException {
        if (update.status() == WorkGroup.Status.ACTIVE) {
            throw new CoordinationException("GROUP_STATUS_INVALID");
        }
    }

    private static void reviewed(ReviewValidationPayload review, ProjectionView view) throws IOException {
        TaskSnapshotRecord snapshot = view.taskCompletionProjection().findSnapshotById(review.snapshotIdentity())
                .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
        String producer = snapshot.provenance().participant();
        if (producer.equals(review.targetParticipant()) || review.sourceParticipant()
                .equals(review.targetParticipant())) {
            throw new CoordinationException("REVIEW_SELF_APPROVAL_FORBIDDEN");
        }
        if (!producer.equals(review.sourceParticipant())) {
            throw new CoordinationException("REVIEW_SNAPSHOT_MISMATCH");
        }
        // A lane that has moved on is not the one the review was granted for.
        WorkIntent lane = view.collaborationProjection().intent(review.targetIntentId()).orElse(null);
        if (lane != null && (lane.status() != WorkIntent.Status.ANNOUNCED || lane.version() != review.claimEpoch()
                || !lane.taskId().equals(snapshot.taskId()) || !lane.participant().equals(producer)
                || !lane.authorityLineageId().equals(snapshot.provenance().authorityLineageId()))) {
            throw new CoordinationException("REVIEW_GRANT_STALE");
        }
    }

    private static void prepared(CompletionPreparedPayload prepared, ProjectionView view) throws IOException {
        requireCurrentLane(view, prepared.laneIdentity().value(), prepared.claimEpoch(), prepared.taskId());
    }

    private static void snapshotted(TaskSnapshotPayload snapshot, ProjectionView view) throws IOException {
        SnapshotProvenance provenance = snapshot.provenance();
        WorkIntent lane = requireCurrentLane(view, provenance.laneId(), provenance.claimEpoch(), snapshot.taskId());
        if (lane != null && (!lane.participant().equals(provenance.participant())
                || !lane.authorityLineageId().equals(provenance.authorityLineageId())
                || !lane.workGroupId().equals(provenance.workGroupId()))) {
            throw new CoordinationException(CLAIM_EVIDENCE_STALE);
        }
    }

    /**
     * Finishing needs a lane that is still the caller's at the epoch it names. A project that never announced a
     * lane uses its task as the lane, and has nothing to check.
     */
    private static WorkIntent requireCurrentLane(ProjectionView view, UUID laneId, long claimEpoch,
            UUID taskId) throws IOException {
        if (!view.collaborationProjection().activated()) {
            return null;
        }
        WorkIntent lane = view.collaborationProjection().intent(laneId).orElse(null);
        if (lane == null || lane.version() != claimEpoch || !lane.taskId().equals(taskId)) {
            throw new CoordinationException(CLAIM_EVIDENCE_STALE);
        }
        return lane;
    }

    private static void cancelled(PredictionEvent event, ProjectionView view) throws IOException {
        if (view.taskCompletionProjection().taskState(event.predictionId()) == TaskCompletionState.INTEGRATED) {
            throw new CoordinationException("task_not_cancellable");
        }
    }

    private static void published(ImplementationEventPayload payload, ProjectionView view) throws IOException {
        if (!view.collaborationProjection().activated()) {
            return;
        }
        boolean publisherActive = view.collaborationProjection().activeIntents().stream()
                .anyMatch(lane -> lane.authorityLineageId().equals(payload.authorityLineageId())
                        && !lane.selectors().isEmpty());
        if (!publisherActive) {
            throw new CoordinationException("CAPABILITY_PUBLISHER_STALE");
        }
    }

    private static void requireRoleIsolation(CollaborationProjection collaboration, WorkIntent source,
            LaneContinuation continuation) throws IOException {
        if (source.role() != WorkIntent.Role.PRODUCER && source.role() != WorkIntent.Role.REVIEWER) {
            return;
        }
        WorkIntent.Role oppositeRole =
                source.role() == WorkIntent.Role.PRODUCER ? WorkIntent.Role.REVIEWER : WorkIntent.Role.PRODUCER;
        boolean conflicts = collaboration.activeIntents().stream()
                .filter(intent -> intent.group().equals(source.group())).filter(intent -> intent.role() == oppositeRole)
                .anyMatch(intent -> intent.participantIdentity().equals(continuation.targetOwner()));
        if (conflicts) {
            throw new CoordinationException("REVIEW_SELF_NOT_ALLOWED");
        }
    }
}
