package org.synesis.coordination.application;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationMissingElementException;
import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * Decides, from a fresh project projection, whether a review request, a reviewer admission, or a review grant is
 * allowed, and whether a repeated review request is an idempotent replay.
 *
 * <p>Every rule is a stateless check over projected state and appends nothing, so {@link CoordinationRequestService}
 * only sequences these decisions inside the project append lock. Callers must pass a projection read inside that
 * lock; the rules never re-read durable state.</p>
 */
final class ReviewAdmissionRules {

    private ReviewAdmissionRules() {
    }

    /**
     * Validates the semantic direction of an explicit review request.
     */
    private static void validateReviewDirection(PredictionEventStore current,
            ParticipantId requester,
            WorkIntent target) throws IOException {
        if (target.role() != WorkIntent.Role.PRODUCER) {
            throw new CoordinationException("REVIEW_TARGET_NOT_PRODUCER");
        }
        if (requester.equals(target.participantIdentity())) {
            throw new CoordinationException("REVIEW_SELF_NOT_ALLOWED");
        }
        List<WorkIntent> requesterIntents = current.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participantIdentity().equals(requester)).toList();
        if (requesterIntents.isEmpty()) {
            // Existing review callers may be unannounced, but the request's
            // exact target still provides the required reviewed-intent identity.
            return;
        }
        List<WorkIntent> sameGroupReviewers = requesterIntents.stream()
                .filter(intent -> intent.workGroupId().equals(target.workGroupId()))
                .filter(intent -> intent.role() == WorkIntent.Role.REVIEWER).toList();
        if (sameGroupReviewers.isEmpty()) {
            if (requesterIntents.stream().noneMatch(intent -> intent.workGroupId().equals(target.workGroupId()))) {
                throw new CoordinationException("REVIEW_WORK_GROUP_MISMATCH");
            }

            // An explicitly requested reciprocal review may be issued by an
            // active producer lane. Automatic review admission remains
            // restricted to explicit reviewer intents in the workspace
            // projection, so this does not restore peer-order inference.
            return;
        }
        if (sameGroupReviewers.stream().noneMatch(reviewer -> reviewTargetsMatch(reviewer, target))) {
            throw new CoordinationException("REVIEW_TARGET_MISMATCH");
        }
    }

    /**
     * Returns whether every declared review target overlaps a producer selector.
     */
    private static boolean reviewTargetsMatch(WorkIntent reviewer, WorkIntent producer) {
        return reviewer.reviewTargetSelectors().stream()
                .allMatch(targetSelector -> producer.selectors().stream().anyMatch(targetSelector::overlaps));
    }

    /**
     * Validates the review admission invariant before the state transition.
     *
     * @param current fresh authoritative project projection
     * @param target  proposed target intent
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void validateReviewAdmission(PredictionEventStore current,
            ParticipantId requester,
            WorkIntent target) throws IOException {
        validateReviewDirection(current, requester, target);
        boolean matchingReviewer = current.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participantIdentity().equals(requester))
                .filter(intent -> intent.workGroupId().equals(target.workGroupId()))
                .filter(intent -> intent.role() == WorkIntent.Role.REVIEWER)
                .anyMatch(reviewer -> reviewTargetsMatch(reviewer, target));
        if (!matchingReviewer) {
            throw new CoordinationException("REVIEWER_INTENT_REQUIRED");
        }
    }

    /**
     * Validates the review request invariant before the state transition.
     *
     * @param current fresh authoritative project projection
     * @param target  proposed target intent
     * @throws IOException when payload validation or durable I/O fails
     */
    static void validateReviewRequest(PredictionEventStore current,
            CoordinationRequest request,
            WorkIntent target) throws IOException {
        validateReviewAdmission(current, request.requesterIdentity(), target);
        SnapshotId snapshot = request.snapshot().orElse(null);
        requireSnapshotWhenMandatory(current, target, snapshot);
        if (snapshot != null) {
            validateReviewCandidate(current, target, snapshot);
            validateReviewSnapshotUnassigned(current, snapshot, request.identity());
        }
    }

    /**
     * Rejects an unpinned review request when the work group makes review mandatory.
     */
    private static void requireSnapshotWhenMandatory(PredictionEventStore current,
            WorkIntent target,
            SnapshotId snapshot) throws IOException {
        boolean mandatoryReview = current.workGroupProjection().group(target.group())
                .map(group -> group.reviewPolicy().mandatory()).orElse(false);
        if (mandatoryReview && snapshot == null) {
            throw new CoordinationException("REVIEW_SNAPSHOT_REQUIRED");
        }
    }

    /**
     * Validates the review candidate invariant before the state transition.
     *
     * @param current fresh authoritative project projection
     * @param target  proposed target intent
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void validateReviewCandidate(PredictionEventStore current, WorkIntent target, SnapshotId snapshotId)
            throws IOException {
        TaskSnapshotRecord snapshot = current.taskCompletionProjection().findSnapshotById(snapshotId)
                .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
        boolean exactSnapshotOwner =
                snapshot.provenance().laneIdentity().equals(target.identity()) && snapshot.provenance()
                        .claimEpochIdentity().equals(target.claimEpoch()) && snapshot.provenance().participantIdentity()
                        .equals(target.participantIdentity());
        boolean lawfulProducerSuccessor =
                target.role() == WorkIntent.Role.PRODUCER && target.claimEpoch().value() > snapshot.provenance()
                        .claimEpochIdentity().value() && isSuccessorOf(current,
                        snapshot.provenance().laneIdentity(),
                        target.identity());
        if (!snapshot.reviewRequired() || !snapshot.provenance().workGroupIdentity().equals(target.group())
                || target.role() != WorkIntent.Role.PRODUCER || (!exactSnapshotOwner && !lawfulProducerSuccessor)
                || !snapshot.taskIdentity().equals(target.task()) || !snapshot.provenance().lineageIdentity()
                .equals(target.lineage())) {
            throw new CoordinationException("REVIEW_SNAPSHOT_MISMATCH");
        }
        if (current.taskCompletionProjection().snapshotState(snapshotId).orElse(TaskCompletionState.ACTIVE)
                != TaskCompletionState.REVIEW_PENDING || current.workGroupProjection()
                .reviewValidationForSnapshot(snapshotId).isPresent()) {
            throw new CoordinationException("REVIEW_CANDIDATE_RESOLVED");
        }
    }

    /**
     * Follows durable successor links and detects cycles while checking the target intent.
     */
    private static boolean isSuccessorOf(PredictionEventStore current,
            WorkIntentId sourceIntentId,
            WorkIntentId targetIntentId) {
        Set<WorkIntentId> visited = new HashSet<>();
        WorkIntentId intentId = sourceIntentId;
        while (visited.add(intentId)) {
            Optional<WorkIntentId> successor = current.collaborationProjection().successorIntent(intentId);
            if (successor.isEmpty()) {
                return false;
            }
            intentId = successor.orElseThrow(CoordinationMissingElementException::new);
            if (intentId.equals(targetIntentId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates the review snapshot unassigned invariant before the state transition.
     *
     * @param current fresh authoritative project projection
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void validateReviewSnapshotUnassigned(PredictionEventStore current,
            SnapshotId snapshotId,
            RequestId requestId) throws IOException {
        UUID ownGrantId = requestId == null ? null : LaneGrant.reviewGrantId(requestId.value());
        boolean assigned = current.workGroupProjection().grants().stream()
                .filter(grant -> grant.snapshot().filter(snapshotId::equals).isPresent())
                .filter(grant -> !grant.grantId().equals(ownGrantId))
                .filter(grant -> !current.workGroupProjection().continuationGrant(grant.grantId()))
                .filter(grant -> !current.workGroupProjection().grantSuperseded(grant.grantId()))
                .anyMatch(grant -> current.workGroupProjection().grantAvailable(grant.grantId())
                        || current.workGroupProjection().grantConsumed(grant.grantId()));
        boolean acceptedWithoutGrant = current.collaborationProjection().requests().stream()
                .filter(request -> request.kind() == CoordinationRequest.Kind.REVIEW)
                .filter(request -> request.snapshot().filter(snapshotId::equals).isPresent())
                .filter(request -> !request.identity().equals(requestId))
                .filter(request -> request.status() == CoordinationRequest.Status.ACCEPTED)
                .map(request -> LaneGrant.reviewGrantId(request.requestId()))
                .anyMatch(expectedGrantId -> current.workGroupProjection().grants().stream()
                        .noneMatch(grant -> grant.grantId().equals(expectedGrantId)));
        if (assigned || acceptedWithoutGrant) {
            throw new CoordinationException("REVIEW_CANDIDATE_ALREADY_ASSIGNED");
        }
    }

    /**
     * Finds an idempotent review request for the same requester, intent, and snapshot.
     *
     * @param current           authoritative project projection
     * @param requester         requesting participant
     * @param conflictingIntent intent that the review targets
     * @param snapshot          exact candidate snapshot, or {@code null} for an unpinned request
     * @return matching request, if one exists
     */
    private static CoordinationRequest findExistingReviewRequest(PredictionEventStore current,
            ParticipantId requester,
            WorkIntentId conflictingIntent,
            SnapshotId snapshot) {
        return current.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.requesterIdentity().equals(requester))
                .filter(candidate -> candidate.conflictingIntent().equals(conflictingIntent))
                .filter(candidate -> candidate.kind() == CoordinationRequest.Kind.REVIEW)
                .filter(candidate -> Objects.equals(candidate.snapshot().orElse(null), snapshot)).findFirst()
                .orElse(null);
    }

    /**
     * Reports whether the accepted request still has a matching, unsuperseded review grant.
     *
     * @param current   authoritative project projection
     * @param requester requesting participant
     * @param target    current conflicting intent
     * @param snapshot  exact candidate snapshot
     * @param existing  matching review request
     * @return {@code true} when the response and grant still identify the same obligation
     */
    private static boolean hasCurrentAcceptedReviewGrant(PredictionEventStore current,
            ParticipantId requester,
            WorkIntent target,
            SnapshotId snapshot,
            CoordinationRequest existing) {
        if (existing == null || existing.status() != CoordinationRequest.Status.ACCEPTED) {
            return false;
        }
        return current.workGroupProjection().grants().stream()
                .anyMatch(grant -> grant.targetIntent().equals(target.identity()) && grant.epoch()
                        .equals(target.claimEpoch()) && grant.targetParticipantIdentity().equals(requester)
                        && Objects.equals(grant.snapshot().orElse(null), snapshot) && !current.workGroupProjection()
                        .grantSuperseded(grant.identity()));
    }

    /**
     * Returns whether a request retains a response that may be replayed without its target.
     *
     * @param existing matching review request, if any
     * @return {@code true} for pending or accepted requests
     */
    private static boolean isOpenReviewResponse(CoordinationRequest existing) {
        return existing != null && (existing.status() == CoordinationRequest.Status.PENDING
                || existing.status() == CoordinationRequest.Status.ACCEPTED);
    }

    /**
     * Applies review-specific admission and idempotent replay rules.
     */
    static Optional<CoordinationRequest> existingReviewRequest(PredictionEventStore current,
            ParticipantId requester,
            WorkIntentId conflictingIntent,
            SnapshotId snapshot) throws IOException {
        CoordinationRequest existing = findExistingReviewRequest(current, requester, conflictingIntent, snapshot);
        WorkIntent target = current.collaborationProjection().intent(conflictingIntent).orElse(null);
        if (target == null) {
            return replayRequestForMissingReviewTarget(current, conflictingIntent, existing);
        }
        if (current.collaborationProjection().successorIntent(target.identity()).isPresent()) {
            throw new CoordinationException("REVIEW_TARGET_STALE");
        }
        validateReviewDirection(current, requester, target);
        return existingReviewRequestForTarget(current, requester, target, snapshot, existing);
    }

    /**
     * Replays an open owner response if its target disappeared without a successor.
     *
     * @param current           authoritative project projection
     * @param conflictingIntent missing target intent
     * @param existing          matching review request, if one exists
     * @return existing request when its durable response remains replayable
     * @throws IOException when the target was superseded or no request can be replayed
     */
    private static Optional<CoordinationRequest> replayRequestForMissingReviewTarget(PredictionEventStore current,
            WorkIntentId conflictingIntent,
            CoordinationRequest existing) throws IOException {
        if (current.collaborationProjection().successorIntent(conflictingIntent).isPresent()) {
            throw new CoordinationException("REVIEW_TARGET_STALE");
        }
        if (isOpenReviewResponse(existing)) {
            return Optional.of(existing);
        }
        throw new CoordinationException("INTENT_NOT_FOUND");
    }

    /**
     * Applies pending-response replay and snapshot validation for a current review target.
     *
     * @param current   authoritative project projection
     * @param requester requesting participant
     * @param target    current conflicting intent
     * @param snapshot  exact candidate snapshot, or {@code null} for an unpinned request
     * @param existing  matching review request, if one exists
     * @return the request to replay, or empty when a new request may be appended
     * @throws IOException when the candidate is stale, unauthorized, or malformed
     */
    private static Optional<CoordinationRequest> existingReviewRequestForTarget(PredictionEventStore current,
            ParticipantId requester,
            WorkIntent target,
            SnapshotId snapshot,
            CoordinationRequest existing) throws IOException {
        if (existing != null && existing.status() == CoordinationRequest.Status.PENDING) {
            return Optional.of(existing);
        }
        if (hasCurrentAcceptedReviewGrant(current, requester, target, snapshot, existing)) {
            return Optional.of(existing);
        }
        validateReviewSnapshotRequest(current, requester, target, snapshot, existing);
        if (shouldReplayDurableReviewResponse(current, target, snapshot, existing)) {
            return Optional.of(existing);
        }
        return Optional.empty();
    }

    /**
     * Enforces the group's snapshot requirement and validates an explicitly pinned candidate.
     *
     * @param current   authoritative project projection
     * @param requester requesting participant
     * @param target    current conflicting intent
     * @param snapshot  exact candidate snapshot, or {@code null} for an unpinned request
     * @param existing  matching review request, if one exists
     * @throws IOException when the group requires a snapshot or the pinned candidate is invalid
     */
    private static void validateReviewSnapshotRequest(PredictionEventStore current,
            ParticipantId requester,
            WorkIntent target,
            SnapshotId snapshot,
            CoordinationRequest existing) throws IOException {
        requireSnapshotWhenMandatory(current, target, snapshot);
        if (snapshot != null) {
            validateReviewAdmission(current, requester, target);
            validateReviewCandidate(current, target, snapshot);
            validateReviewSnapshotUnassigned(current, snapshot, existing == null ? null : existing.identity());
        }
    }

    /**
     * Replays a durable owner response whose matching grant append may have been interrupted.
     *
     * @param current  authoritative project projection
     * @param target   current conflicting intent
     * @param snapshot exact candidate snapshot, or {@code null} for an unpinned request
     * @param existing matching review request, if one exists
     * @return {@code true} when the existing response remains the idempotent result
     */
    private static boolean shouldReplayDurableReviewResponse(PredictionEventStore current,
            WorkIntent target,
            SnapshotId snapshot,
            CoordinationRequest existing) {
        if (existing == null) {
            return false;
        }
        UUID expectedGrantId = LaneGrant.reviewGrantId(existing.requestId());
        boolean currentEpochGrant = current.workGroupProjection().grants().stream()
                .anyMatch(grant -> grant.identity().value().equals(expectedGrantId) && grant.targetIntent()
                        .equals(target.identity()) && grant.epoch().equals(target.claimEpoch()));
        // A durable owner response can precede its grant append; retries replay it safely.
        return currentEpochGrant || (existing.status() == CoordinationRequest.Status.ACCEPTED && snapshot != null);
    }

    /**
     * Validates an accepted review response against the current owner and candidate.
     *
     * @param current authoritative project projection
     * @param request addressed request
     * @param status  requested response status
     * @throws IOException when review authority or candidate state is invalid
     */
    static void validateReviewApproval(PredictionEventStore current,
            CoordinationRequest request,
            CoordinationRequest.Status status) throws IOException {
        if (request.status() != CoordinationRequest.Status.PENDING || status != CoordinationRequest.Status.ACCEPTED
                || request.kind() != CoordinationRequest.Kind.REVIEW) {
            return;
        }
        WorkIntent intent = current.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        validateReviewRequest(current, request, intent);
    }
}
