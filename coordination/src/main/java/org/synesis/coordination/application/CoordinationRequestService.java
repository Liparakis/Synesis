package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimHandoff;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotId;
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
 * Owns coordination-request admission, response, and review-grant transitions.
 */
final class CoordinationRequestService {

    private final PredictionEventStore store;
    private final NodeIdentity signer;

    /**
     * Validates and initializes this coordination request service.
     *
     * @param store  project event store
     * @param signer identity used to sign the event
     */
    CoordinationRequestService(PredictionEventStore store, NodeIdentity signer) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.signer = CoordinationNullException.requireNonNull(signer, "signer");
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
    private static void validateReviewRequest(PredictionEventStore current,
            CoordinationRequest request,
            WorkIntent target) throws IOException {
        validateReviewAdmission(current, request.requesterIdentity(), target);
        boolean mandatoryReview = current.workGroupProjection().group(target.workGroupId())
                .map(group -> group.reviewPolicy().mandatory()).orElse(false);
        if (mandatoryReview && request.snapshot().isEmpty()) {
            throw new CoordinationException("REVIEW_SNAPSHOT_REQUIRED");
        }
        if (request.snapshot().isPresent()) {
            SnapshotId snapshot = request.snapshot().orElseThrow(CoordinationMissingElementException::new);
            validateReviewCandidate(current, target, snapshot);
            validateReviewSnapshotUnassigned(current, snapshot, request.identity());
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
        UUID ownGrantId = requestId == null ? null : UUID.nameUUIDFromBytes(("synesis-review-grant:"
                                                                             + requestId.value()).getBytes(
                StandardCharsets.UTF_8));
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
                .map(request -> UUID.nameUUIDFromBytes(("synesis-review-grant:" + request.identity().value()).getBytes(
                        StandardCharsets.UTF_8)))
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
     * Finds a request and confirms that the caller remains its non-terminal target.
     *
     * @param current     authoritative project projection
     * @param participant responding participant
     * @param requestId   request identity
     * @return addressed request
     * @throws IOException when the request is missing, misaddressed, or terminally fenced
     */
    private static CoordinationRequest requireAddressedRequest(PredictionEventStore current,
            ParticipantId participant,
            RequestId requestId) throws IOException {
        CoordinationRequest request = current.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.identity().equals(requestId)).findFirst()
                .orElseThrow(() -> new CoordinationException("REQUEST_NOT_FOUND"));
        if (!request.targetIdentity().equals(participant)) {
            throw new CoordinationException("REQUEST_TARGET_MISMATCH");
        }
        if (current.collaborationProjection().isParticipantTerminal(participant)) {
            throw new CoordinationException("SESSION_TERMINAL");
        }
        return request;
    }

    /**
     * Finds an inbox request and confirms that it targets the exact participant.
     *
     * @param current     authoritative project projection
     * @param participant intended inbox recipient
     * @param item        server-issued inbox item identity
     * @return request addressed to the participant
     * @throws IOException when the item is missing or belongs to another participant
     */
    private static CoordinationRequest requireInboxItem(PredictionEventStore current,
            ParticipantId participant,
            RequestId item) throws IOException {
        CoordinationRequest request = current.collaborationProjection().requests().stream()
                .filter(candidate -> candidate.identity().equals(item)).findFirst()
                .orElseThrow(() -> new CoordinationException("INBOX_ITEM_NOT_FOUND"));
        if (!request.targetIdentity().equals(participant)) {
            throw new CoordinationException("INBOX_ITEM_CALLER_MISMATCH");
        }
        return request;
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
    CoordinationRequest request(ParticipantId requester,
            WorkIntentId conflictingIntentId,
            CoordinationRequest.Kind kind,
            String proposal) throws IOException, GeneralSecurityException {
        return request(requester, conflictingIntentId, kind, proposal, null);
    }

    /**
     * Opens a negotiation request, optionally bound to one exact immutable review snapshot.
     *
     * @param requesterIdentity requester participant identity
     * @param conflictingIntent target intent identity
     * @param kind              request kind
     * @param proposal          proposal
     * @param snapshotIdentity  exact review snapshot, required by mandatory-review groups
     * @return request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    CoordinationRequest request(ParticipantId requesterIdentity,
            WorkIntentId conflictingIntent,
            CoordinationRequest.Kind kind,
            String proposal,
            SnapshotId snapshotIdentity) throws IOException, GeneralSecurityException {
        ParticipantId requester = CoordinationNullException.requireNonNull(requesterIdentity, "requester");
        WorkIntentId target = CoordinationNullException.requireNonNull(conflictingIntent, "conflicting intent");
        CoordinationRequest.Kind requestKind = CoordinationNullException.requireNonNull(kind, "request kind");
        String requestProposal = CoordinationNullException.requireNonNull(proposal, "proposal");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            if (requestKind == CoordinationRequest.Kind.REVIEW) {
                Optional<CoordinationRequest> replay = existingReviewRequest(current,
                        requester,
                        target,
                        snapshotIdentity);
                if (replay.isPresent()) {
                    return replay.orElseThrow(CoordinationMissingElementException::new);
                }
            }
            WorkIntent conflict = current.collaborationProjection().intent(target)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(),
                    current.projectId(),
                    requester.value(),
                    conflict.participantIdentity().value(),
                    target.value(),
                    requestKind,
                    requestProposal,
                    CoordinationRequest.Status.PENDING,
                    snapshotIdentity == null ? null : snapshotIdentity.value());
            current.append(request.requestId(),
                    PredictionEventType.COORDINATION_REQUESTED,
                    signer.nodeId(),
                    CollaborationCodec.encodeRequest(request),
                    signer);
            return request;
        }
    }

    /**
     * Applies review-specific admission and idempotent replay rules.
     */
    private Optional<CoordinationRequest> existingReviewRequest(PredictionEventStore current,
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
    private Optional<CoordinationRequest> replayRequestForMissingReviewTarget(PredictionEventStore current,
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
    private Optional<CoordinationRequest> existingReviewRequestForTarget(PredictionEventStore current,
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
    private void validateReviewSnapshotRequest(PredictionEventStore current,
            ParticipantId requester,
            WorkIntent target,
            SnapshotId snapshot,
            CoordinationRequest existing) throws IOException {
        boolean mandatoryReview = current.workGroupProjection().group(target.group())
                .map(group -> group.reviewPolicy().mandatory()).orElse(false);
        if (mandatoryReview && snapshot == null) {
            throw new CoordinationException("REVIEW_SNAPSHOT_REQUIRED");
        }
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
    private boolean shouldReplayDurableReviewResponse(PredictionEventStore current,
            WorkIntent target,
            SnapshotId snapshot,
            CoordinationRequest existing) {
        if (existing == null) {
            return false;
        }
        UUID expectedGrantId = UUID.nameUUIDFromBytes(("synesis-review-grant:" + existing.identity().value()).getBytes(
                StandardCharsets.UTF_8));
        boolean currentEpochGrant = current.workGroupProjection().grants().stream()
                .anyMatch(grant -> grant.identity().value().equals(expectedGrantId) && grant.targetIntent()
                        .equals(target.identity()) && grant.epoch().equals(target.claimEpoch()));
        // A durable owner response can precede its grant append; retries replay it safely.
        return currentEpochGrant || (existing.status() == CoordinationRequest.Status.ACCEPTED && snapshot != null);
    }

    /**
     * Offers an atomic handoff to another active participant.
     *
     * @param ownerIdentity  current owner identity
     * @param intentIdentity intent identity being handed off
     * @param targetIdentity target participant identity
     * @param proposal       handoff proposal
     * @return pending request
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    CoordinationRequest offerHandoff(ParticipantId ownerIdentity,
            WorkIntentId intentIdentity,
            ParticipantId targetIdentity,
            String proposal) throws IOException, GeneralSecurityException {
        ParticipantId owner = CoordinationNullException.requireNonNull(ownerIdentity, "owner");
        WorkIntentId handoffIntent = CoordinationNullException.requireNonNull(intentIdentity, "intent ID");
        ParticipantId target = CoordinationNullException.requireNonNull(targetIdentity, "target participant");
        String handoffProposal = CoordinationNullException.requireNonNull(proposal, "proposal");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            WorkIntent intent = current.collaborationProjection().intent(handoffIntent)
                    .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
            intent.requireOwnedBy(owner);
            if (current.collaborationProjection().isParticipantTerminal(owner) || current.collaborationProjection()
                    .isParticipantTerminal(target)) {
                throw new CoordinationException("SESSION_TERMINAL");
            }
            boolean activeTarget = current.collaborationProjection().participants().stream()
                    .anyMatch(participant -> participant.identity().equals(target)
                            && participant.state() == Participant.State.ACTIVE) || current.collaborationProjection()
                    .activeIntents().stream().anyMatch(candidate -> candidate.participantIdentity().equals(target));
            if (!activeTarget) {
                throw new CoordinationException("HANDOFF_TARGET_NOT_ACTIVE");
            }
            CoordinationRequest request = new CoordinationRequest(UUID.randomUUID(),
                    current.projectId(),
                    owner.value(),
                    target.value(),
                    handoffIntent.value(),
                    CoordinationRequest.Kind.HANDOFF,
                    handoffProposal,
                    CoordinationRequest.Status.PENDING);
            current.append(request.requestId(),
                    PredictionEventType.COORDINATION_REQUESTED,
                    signer.nodeId(),
                    CollaborationCodec.encodeRequest(request),
                    signer);
            return request;
        }
    }

    /**
     * Responds idempotently to a request addressed to the participant.
     *
     * @param participantIdentity target participant identity
     * @param requestIdentity     request identity
     * @param status              response status
     * @param proposal            revised proposal
     * @throws IOException              persistence or validation failure
     * @throws GeneralSecurityException signing failure
     */
    void respond(ParticipantId participantIdentity,
            RequestId requestIdentity,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        RequestId requestId = CoordinationNullException.requireNonNull(requestIdentity, "request ID");
        if (status == CoordinationRequest.Status.PENDING) {
            throw new CoordinationArgumentException("pending is not a response");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            CoordinationRequest request = requireAddressedRequest(current, participant, requestId);
            validateReviewApproval(current, request, status);
            if (replayResolvedResponse(current, request, status)) {
                return;
            }
            current.append(requestId.value(),
                    PredictionEventType.COORDINATION_RESPONDED,
                    signer.nodeId(),
                    CollaborationCodec.encodeResponse(requestId, status, proposal),
                    signer);
            if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.HANDOFF) {
                acceptHandoff(current, request);
            }
            if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.REVIEW) {
                issueReviewGrantIfAbsent(current, request);
            }
        }
    }

    /**
     * Validates an accepted review response against the current owner and candidate.
     *
     * @param current authoritative project projection
     * @param request addressed request
     * @param status  requested response status
     * @throws IOException when review authority or candidate state is invalid
     */
    private void validateReviewApproval(PredictionEventStore current,
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

    /**
     * Replays a matching resolved response and repairs any missing accepted-review grant.
     *
     * @param current authoritative project projection
     * @param request addressed request
     * @param status  requested response status
     * @return true when the existing response was replayed
     * @throws IOException              when the request already has a different response
     * @throws GeneralSecurityException when repairing the review grant fails
     */
    private boolean replayResolvedResponse(PredictionEventStore current,
            CoordinationRequest request,
            CoordinationRequest.Status status) throws IOException, GeneralSecurityException {
        if (request.status() == CoordinationRequest.Status.PENDING) {
            return false;
        }
        if (request.status() != status) {
            throw new CoordinationException("REQUEST_ALREADY_RESOLVED");
        }
        if (status == CoordinationRequest.Status.ACCEPTED && request.kind() == CoordinationRequest.Kind.REVIEW) {
            issueReviewGrantIfAbsent(current, request);
        }
        return true;
    }

    /**
     * Appends the handoff-specific transition following an accepted request response.
     *
     * @param current authoritative project projection
     * @param request accepted handoff request
     * @throws IOException              when the source intent no longer exists
     * @throws GeneralSecurityException when event signing fails
     */
    private void acceptHandoff(PredictionEventStore current, CoordinationRequest request)
            throws IOException, GeneralSecurityException {
        WorkIntent intent = current.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(CoordinationMissingElementException::new);
        current.append(request.conflictingIntent().value(),
                PredictionEventType.CLAIM_HANDOFF_ACCEPTED,
                signer.nodeId(),
                CollaborationCodec.encodeHandoff(new ClaimHandoff(intent.identity(),
                        request.targetIdentity(),
                        intent.claimEpoch())),
                signer);
    }

    /**
     * Issues the deterministic single-use grant after an accepted review request.
     *
     * @param current fresh authoritative project projection
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void issueReviewGrantIfAbsent(PredictionEventStore current, CoordinationRequest request)
            throws IOException, GeneralSecurityException {
        WorkIntent intent = current.collaborationProjection().intent(request.conflictingIntent())
                .orElseThrow(() -> new CoordinationException("INTENT_NOT_FOUND"));
        if (current.collaborationProjection().successorIntent(intent.identity()).isPresent()) {
            throw new CoordinationException("REVIEW_TARGET_STALE");
        }
        validateReviewRequest(current, request, intent);
        if (!request.targetIdentity().equals(intent.participantIdentity())) {
            throw new CoordinationException("REVIEW_TARGET_PARTICIPANT_MISMATCH");
        }
        UUID grantId = UUID.nameUUIDFromBytes(("synesis-review-grant:" + request.identity().value()).getBytes(
                StandardCharsets.UTF_8));
        boolean exists = current.workGroupProjection().grants().stream()
                .anyMatch(grant -> grant.grantId().equals(grantId));
        if (!exists) {
            validateReviewRequest(current, request, intent);
            UUID reviewedIntentId = intent.intentId();
            long reviewedClaimEpoch = intent.version();
            if (request.snapshot().isPresent()) {
                TaskSnapshotRecord snapshot = current.taskCompletionProjection()
                        .findSnapshotById(request.snapshot().orElseThrow(CoordinationMissingElementException::new))
                        .orElseThrow(() -> new CoordinationException("REVIEW_SNAPSHOT_NOT_FOUND"));
                reviewedIntentId = snapshot.provenance().laneId();
                reviewedClaimEpoch = snapshot.provenance().claimEpoch();
            }
            LaneGrant grant = new LaneGrant(grantId,
                    intent.workGroupId(),
                    reviewedIntentId,
                    request.requesterIdentity().value(),
                    reviewedClaimEpoch,
                    true,
                    request.snapshot().map(SnapshotId::value).orElse(null));
            current.append(grantId,
                    PredictionEventType.LANE_GRANT_ISSUED,
                    signer.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant),
                    signer);
        }
    }

    /**
     * Acknowledges a server-issued inbox item for the exact participant, idempotently.
     *
     * @param participantIdentity exact participant identity
     * @param itemIdentity        server-issued request/inbox item identity
     * @throws IOException              persistence or authorization failure
     * @throws GeneralSecurityException signing failure
     */
    void acknowledgeInbox(ParticipantId participantIdentity, RequestId itemIdentity)
            throws IOException, GeneralSecurityException {
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        RequestId item = CoordinationNullException.requireNonNull(itemIdentity, "inbox item ID");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            requireInboxItem(current, participant, item);
            if (!current.collaborationProjection().inboxAcknowledged(item)) {
                current.append(item.value(),
                        PredictionEventType.INBOX_ITEM_ACKNOWLEDGED,
                        signer.nodeId(),
                        CollaborationCodec.encodeUuidText(item),
                        signer);
            }
        }
    }

    /**
     * Resolves and acknowledges a coordination inbox item atomically.
     *
     * @param participantIdentity exact participant identity
     * @param itemIdentity        server-issued inbox item identity
     * @param status              terminal response status
     * @param proposal            response or resolution proposal
     * @throws IOException              authorization or transition failure
     * @throws GeneralSecurityException signing failure
     */
    void resolveInbox(ParticipantId participantIdentity,
            RequestId itemIdentity,
            CoordinationRequest.Status status,
            String proposal) throws IOException, GeneralSecurityException {
        if (status == null || status == CoordinationRequest.Status.PENDING) {
            throw new CoordinationArgumentException("inbox resolution requires terminal status");
        }
        ParticipantId participant = CoordinationNullException.requireNonNull(participantIdentity, "participant");
        RequestId item = CoordinationNullException.requireNonNull(itemIdentity, "inbox item ID");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                throw new CoordinationException("event append lock unavailable");
            }
            PredictionEventStore current = freshStore();
            CoordinationRequest request = requireInboxItem(current, participant, item);
            if (request.status() == CoordinationRequest.Status.PENDING) {
                current.append(item.value(),
                        PredictionEventType.COORDINATION_RESPONDED,
                        signer.nodeId(),
                        CollaborationCodec.encodeResponse(item, status, proposal),
                        signer);
            } else if (request.status() != status) {
                throw new CoordinationException("REQUEST_ALREADY_RESOLVED");
            }
            if (!current.collaborationProjection().inboxAcknowledged(item)) {
                current.append(item.value(),
                        PredictionEventType.INBOX_ITEM_ACKNOWLEDGED,
                        signer.nodeId(),
                        CollaborationCodec.encodeUuidText(item),
                        signer);
            }
        }
    }

    /**
     * Returns durable coordination requests for discovery.
     *
     * @return requests
     */
    List<CoordinationRequest> requests() {
        try {
            return freshStore().collaborationProjection().requests();
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
