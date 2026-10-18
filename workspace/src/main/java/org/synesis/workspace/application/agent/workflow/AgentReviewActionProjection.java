package org.synesis.workspace.application.agent.workflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewAdmissionRules;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Projects review requests, grants, snapshots, and read-only review actions.
 *
 * <p>These helpers read the already-loaded durable projection. They create, consume,
 * validate, and integrate no coordination state themselves.
 */
final class AgentReviewActionProjection {

    /**
     * Returns whether a reviewer still lacks a resolved review of a current producer lane.
     *
     * <p>Open review grants always count as unresolved. A consumed grant addressed to a successor
     * reviewer authorizes <em>continuation</em> of the reviewer lane and is explicitly not a review
     * obligation, so it never strands the successor in {@code REVIEWER_PENDING}.</p>
     *
     * @param store    current project projection
     * @param reviewer the reviewer intent being evaluated
     * @return {@code true} if the reviewer still owes a review
     */
    static boolean hasUnresolvedReviewObligation(PredictionEventStore store, WorkIntent reviewer) {
        if (reviewer.role() != WorkIntent.Role.REVIEWER) {
            return false;
        }
        var workGroups = store.workGroupProjection();
        List<LaneGrant> reviewerGrants = workGroups.grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId())).filter(grant -> grant.targetParticipant().equals(reviewer.participant()))
                .filter(grant -> !AgentReviewActionProjection.grantTargetsSupersededIntent(store, grant)).filter(grant -> !workGroups.grantSuperseded(grant.grantId())).toList();

        boolean unresolvedGrant = reviewerGrants.stream().filter(grant -> AgentReviewActionProjection.allowsIndependentReview(store, grant))
                .anyMatch(grant -> workGroups.reviewValidationForGrant(grant.grantId()).isEmpty());
        if (unresolvedGrant) {
            return true;
        }

        List<WorkIntent> producers = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> intent.workGroupId().equals(reviewer.workGroupId())).filter(intent -> !intent.participant().equals(reviewer.participant())).toList();
        List<WorkIntent> matching = reviewer.reviewTargetSelectors().isEmpty() ? producers : producers.stream().filter(producer -> AgentReviewActionProjection.reviewTargetsMatch(reviewer, producer)).toList();

        if (matching.isEmpty()) {
            if (reviewerGrants.stream().anyMatch(grant -> workGroups.continuationGrant(grant.grantId()))) {
                // A continuation grant restores the reviewer lane; it is not a review obligation.
                return false;
            }
            // With no producer yet, an empty grant list means the reviewer is waiting for review
            // work to emerge. Open grants were handled above, so any grant left here is either a
            // self-review or already validated and does not imply future review work.
            //
            // A grant the reviewer decided on stays decided when the producer's lane was continued
            // afterwards and superseded it. Counted as no grant at all, a reviewer whose accepted
            // snapshot was integrated waited for a producer that had finished, and its group
            // never closed.
            boolean decided = workGroups.grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId()))
                    .filter(grant -> grant.targetParticipant().equals(reviewer.participant())).anyMatch(grant -> workGroups.reviewValidationForGrant(grant.grantId()).isPresent());
            return reviewerGrants.isEmpty() && !decided;
        }
        return matching.stream().anyMatch(producer -> !AgentReviewActionProjection.hasCompletedReview(store, reviewer, producer));
    }

    /**
     * Maps a protocol action name to the {@link AgentNextAction} it implies.
     *
     * @param protocolAction wire name of the protocol action, such as {@code "wait"}
     * @return the matching action; {@link AgentNextAction#REQUEST_COORDINATION} for any name that
     *         has no dedicated mapping
     */
    static AgentNextAction protocolNextAction(String protocolAction) {
        return switch (protocolAction) {
            case NextActionProtocol.ACTION_RESPOND_COORDINATION -> AgentNextAction.RESPOND_COORDINATION;
            case NextActionProtocol.ACTION_REVIEW_DECISION -> AgentNextAction.REVIEW_DECISION;
            case NextActionProtocol.ACTION_WAIT -> AgentNextAction.WAIT;
            default -> AgentNextAction.REQUEST_COORDINATION;
        };
    }

    static boolean hasAvailableReviewerReviewContinuation(PredictionEventStore store, Map<String, Object> collaboration) {
        Object projected = collaboration.get(NextActionKeys.CONTINUATION_CANDIDATES);
        if (!(projected instanceof List<?> candidates)) {
            return false;
        }
        Set<String> availableReviewerIntents = new LinkedHashSet<>();
        for (Object value : candidates) {
            if (value instanceof Map<?, ?> candidate && Boolean.TRUE.equals(candidate.get(NextActionKeys.AVAILABLE)) && "reviewer".equals(candidate.get("role")) && candidate.get(NextActionKeys.SOURCE_INTENT_ID) instanceof String intentId) {
                availableReviewerIntents.add(intentId);
            }
        }
        if (availableReviewerIntents.isEmpty()) {
            return false;
        }
        List<TaskSnapshotRecord> pendingReviews = store.taskCompletionProjection().pendingReviewSnapshots();
        return store.collaborationProjection().activeIntents().stream().filter(intent -> availableReviewerIntents.contains(intent.intentId().toString())).filter(intent -> hasUnresolvedReviewObligation(store, intent))
                .anyMatch(reviewer -> pendingReviews.stream().filter(snapshot -> snapshot.provenance().workGroupId().equals(reviewer.workGroupId())).anyMatch(snapshot -> reviewTargetsMatch(reviewer, snapshot)));
    }

/**
     * Returns whether the current lane revision has an exact review grant.
     */
    static boolean hasCurrentReviewGrant(PredictionEventStore store, WorkIntent intent) {
        return store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(intent.workGroupId())).filter(grant -> grant.targetIntentId().equals(intent.intentId()))
                .filter(grant -> grant.claimEpoch() == intent.version()).filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId())).filter(grant -> allowsIndependentReview(store, grant))
                .anyMatch(grant -> store.workGroupProjection().grantAvailable(grant.grantId()) || store.workGroupProjection().grantConsumed(grant.grantId()));
    }

static AgentResponse reviewActionResponse(Map<String, Object> collaboration, boolean reviewOnly) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) collaboration.get("reviewActions");
        if (reviewActions == null || reviewActions.isEmpty()) {
            return null;
        }
        Map<String, Object> review = reviewActions.getFirst();
        String protocolAction = String.valueOf(review.get(NextActionKeys.NEXT_PROTOCOL_ACTION));
        AgentNextAction next = protocolNextAction(protocolAction);
        Map<String, Object> projection = new LinkedHashMap<>(collaboration);
        if (reviewOnly) {
            projection.put("reviewOnly", true);
        }
        projection.put(NextActionKeys.NEXT_PROTOCOL_ACTION, review.get(NextActionKeys.NEXT_PROTOCOL_ACTION));
        projection.put(NextActionKeys.NEXT_PROTOCOL_KIND, review.get(NextActionKeys.NEXT_PROTOCOL_KIND));
        projection.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, review.get(NextActionKeys.NEXT_PROTOCOL_PAYLOAD));
        if (review.containsKey(NextActionKeys.REVIEW_DECISION)) {
            projection.put(NextActionKeys.REVIEW_DECISION, review.get(NextActionKeys.REVIEW_DECISION));
        }
        if (review.containsKey(NextActionKeys.REVIEW_ACCESS)) {
            projection.put(NextActionKeys.REVIEW_ACCESS, review.get(NextActionKeys.REVIEW_ACCESS));
        }
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, next, projection);
    }

/**
     * Projects a wait while a declared reviewer has no resolved producer target.
     */
    static AgentResponse reviewerPendingAction(PredictionEventStore store, String participantId) {
        for (WorkIntent reviewer : store.collaborationProjection().activeIntents()) {
            if (!reviewer.participant().equals(participantId) || reviewer.role() != WorkIntent.Role.REVIEWER || !hasUnresolvedReviewObligation(store, reviewer)) {
                continue;
            }
            if (hasMandatoryReviewCandidate(store, reviewer)) {
                // The candidate list carries one exact request payload per immutable snapshot. Let the
                // reviewer choose explicitly instead of projecting a generic wait or selecting one.
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(NextActionKeys.WORK_GROUP_ID, reviewer.workGroupId().toString());
            payload.put(NextActionKeys.REVIEWER_PARTICIPANT, participantId);
            payload.put(NextActionKeys.REVIEW_TARGETS, reviewer.reviewTargetSelectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
            payload.put(NextActionKeys.REASON, "WAIT_FOR_ELIGIBLE_PRODUCER");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(NextActionKeys.STATE, "REVIEWER_PENDING");
            result.put(NextActionKeys.REVIEWER_PENDING, true);
            boolean producerLaneOpen = store.collaborationProjection().activeIntents().stream()
                    .anyMatch(intent -> intent.workGroupId().equals(reviewer.workGroupId()) && intent.role() == WorkIntent.Role.PRODUCER && reviewTargetsMatch(reviewer, intent));
            if (!producerLaneOpen) {
                // Reached when the producer left after this reviewer was admitted, or when what it
                // reviews was integrated through another reviewer: in a real run two agents waited
                // here for ever while other modules of the same work group were still open. A
                // session holds one lane, so the reviewer cannot take the implementation over itself.
                result.put("instruction", "No open producer lane claims what this lane reviews: its producer left, or its work was integrated after another review. "
                        + "Nothing will be submitted for this lane to review until a participant announces it again. Keep waiting, or call cancel_lane with the reason if no producer is coming.");
            } else if (admittedAnotherReviewer(store, reviewer)) {
                // In a real run a second reviewer lane waited here for ever: the author had accepted
                // the other reviewer's request for the only snapshot this lane could review.
                result.put("instruction", "What this lane reviews was published, and its author admitted another reviewer to that review. "
                        + "Nothing will be submitted for this lane to review unless that review is given up. Keep waiting, or call cancel_lane with the reason if that review is not coming back.");
            }
            result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(reviewer));
            result.put(NextActionKeys.WORK_GROUP_ID, reviewer.workGroupId().toString());
            result.put(NextActionKeys.REVIEWER_PARTICIPANT, participantId);
            result.put(NextActionKeys.REVIEW_TARGETS, reviewer.reviewTargetSelectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
            result.put(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_WAIT);
            result.put(NextActionKeys.NEXT_PROTOCOL_KIND, NextActionProtocol.KIND_REVIEW_ADMISSION);
            result.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, payload);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
        }
        return null;
    }

/**
     * Returns whether everything waiting for review that a reviewer lane targets was admitted to
     * another participant: its author accepted that participant's review request, or that
     * participant holds the review grant. A request still pending admits nobody yet, and the author
     * may still refuse it, so it does not count here, although it keeps the snapshot from being
     * offered.
     */
    private static boolean admittedAnotherReviewer(PredictionEventStore store, WorkIntent reviewer) {
        List<TaskSnapshotRecord> pending = store.taskCompletionProjection().pendingReviewSnapshots().stream().filter(snapshot -> snapshot.provenance().workGroupId().equals(reviewer.workGroupId()))
                .filter(snapshot -> reviewTargetsMatch(reviewer, snapshot)).toList();
        return !pending.isEmpty() && pending.stream().allMatch(snapshot -> store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.kind() == CoordinationRequest.Kind.REVIEW && request.status() == CoordinationRequest.Status.ACCEPTED && snapshot.snapshotId().equals(request.snapshotId())
                        && !request.requester().equals(reviewer.participant()))
                || store.workGroupProjection().grants().stream().anyMatch(grant -> snapshot.snapshotId().equals(grant.snapshotId()) && !grant.targetParticipant().equals(reviewer.participant())
                        && ReviewAdmissionRules.holdsReview(store, grant)));
    }

static boolean hasMandatoryReviewCandidate(PredictionEventStore store, WorkIntent reviewer) {
        boolean mandatory = store.workGroupProjection().group(reviewer.workGroupId()).map(group -> group.reviewPolicy().mandatory()).orElse(false);
        if (!mandatory) {
            return false;
        }
        Set<String> candidateIds = reviewCandidates(store).stream().filter(candidate -> reviewer.workGroupId().toString().equals(candidate.get(NextActionKeys.WORK_GROUP_ID))).map(candidate -> (String) candidate.get(NextActionKeys.SNAPSHOT_ID))
                .collect(Collectors.toSet());
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> candidateIds.contains(snapshot.snapshotId())).anyMatch(snapshot -> reviewTargetsMatch(reviewer, snapshot));
    }

static boolean pendingReviewAdmissionTargets(PredictionEventStore store, CoordinationRequest request, WorkIntent intent, String participantId) {
        if (request.status() != CoordinationRequest.Status.PENDING || request.kind() != CoordinationRequest.Kind.REVIEW || !request.target().equals(participantId)) {
            return false;
        }
        return request.conflictingIntentId().equals(intent.intentId()) || store.collaborationProjection().successorIntent(request.conflictingIntentId()).map(intent.intentId()::equals).orElse(false);
    }

static boolean allowsIndependentReview(PredictionEventStore store, LaneGrant grant) {
        boolean activeSelfReview = store.collaborationProjection().intent(grant.targetIntentId()).map(intent -> intent.participant().equals(grant.targetParticipant())).orElse(false);
        if (activeSelfReview) {
            return false;
        }
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.provenance().laneId().equals(grant.targetIntentId()))
                .filter(snapshot -> snapshot.provenance().claimEpoch() == grant.claimEpoch()).noneMatch(snapshot -> snapshot.provenance().participant().equals(grant.targetParticipant()));
    }

/**
     * Returns whether a grant names an obsolete intent without a live exact review snapshot.
     *
     * <p>A snapshot-bound review grant remains actionable after producer continuation only while
     * that exact mandatory review is still pending. Ordinary grants to the replaced intent remain
     * stale.</p>
     *
     * @param store durable project projection
     * @param grant grant under consideration
     * @return {@code true} when the grant cannot authorize current work
     */
    static boolean grantTargetsSupersededIntent(PredictionEventStore store, LaneGrant grant) {
        if (store.collaborationProjection().successorIntent(grant.targetIntentId()).isEmpty()) {
            return false;
        }
        if (grant.snapshotId() == null) {
            return true;
        }
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.snapshotId().equals(grant.snapshotId()))
                .filter(snapshot -> snapshot.provenance().workGroupId().equals(grant.workGroupId())).filter(snapshot -> snapshot.provenance().laneId().equals(grant.targetIntentId()))
                .filter(snapshot -> snapshot.provenance().claimEpoch() == grant.claimEpoch()).filter(TaskSnapshotRecord::reviewRequired)
                .noneMatch(snapshot -> store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_PENDING);
    }

    /**
     * Returns whether an active reviewer lane of the same work group is declared for exactly this
     * producer lane, by the matching rule of automatic review admission (ADR-0046): an empty target
     * means the only producer of the group, explicit targets the one producer they cover.
     *
     * <p>Such a declaration is a durable statement that the lane is to be reviewed. Counting only
     * requests and grants left a window between a reviewer's announcement and its request: a
     * producer that published in it was integrated unreviewed, and the reviewer that arrived a
     * second later had nothing it could lawfully decide. A reviewer whose session is suspended or
     * held for recovery does not count: it may never return, and its lane must not hold the
     * producer's publication.</p>
     */
    private static boolean hasDeclaredReviewer(PredictionEventStore store, WorkIntent producer) {
        List<WorkIntent> active = store.collaborationProjection().activeIntents();
        return active.stream().filter(reviewer -> reviewer.role() == WorkIntent.Role.REVIEWER && reviewer.workGroupId().equals(producer.workGroupId()) && !reviewer.participant().equals(producer.participant()))
                .filter(reviewer -> store.collaborationProjection().participantState(reviewer.participant()).filter(Participant.State.ACTIVE::equals).isPresent())
                .anyMatch(reviewer -> {
                    List<WorkIntent> candidates = active.stream().filter(candidate -> candidate.role() == WorkIntent.Role.PRODUCER && candidate.workGroupId().equals(reviewer.workGroupId()))
                            .filter(candidate -> !candidate.participant().equals(reviewer.participant()))
                            .filter(candidate -> reviewer.reviewTargetSelectors().isEmpty() || reviewTargetsMatch(reviewer, candidate)).toList();
                    return candidates.size() == 1 && candidates.getFirst().intentId().equals(producer.intentId());
                });
    }

    /**
     * Determines whether this exact lane revision is subject to review authority.
     */
    static boolean reviewRequired(PredictionEventStore store, WorkIntent intent, String participantHandle) {
        if (intent.role() != WorkIntent.Role.PRODUCER) {
            return false;
        }
        if (store.workGroupProjection().group(intent.workGroupId()).map(group -> group.reviewPolicy().mandatory()).orElse(false)) {
            return true;
        }
        boolean exactGrant = store.workGroupProjection().grants().stream().filter(grant -> grantAppliesToIntent(grant, intent)).filter(grant -> !grant.targetParticipant().equals(participantHandle))
                .filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId()))
                .anyMatch(grant -> store.workGroupProjection().grantAvailable(grant.grantId()) || store.workGroupProjection().grantConsumed(grant.grantId()));
        if (exactGrant) {
            return true;
        }
        boolean requested = store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.kind() == CoordinationRequest.Kind.REVIEW && requestAppliesToIntent(request.conflictingIntentId(), intent) && (
                        request.status() == CoordinationRequest.Status.PENDING
                                || request.status() == CoordinationRequest.Status.ACCEPTED));
        if (requested || hasDeclaredReviewer(store, intent)) {
            return true;
        }
        return store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.taskId().equals(intent.taskId()) && snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId()) && snapshot.provenance().claimEpoch() < intent.version()
                        && store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_REJECTED);
    }

    private static boolean grantAppliesToIntent(LaneGrant grant, WorkIntent intent) {
        // A late historical grant must not become review authority for the successor.  The
        // continuation projection creates a current-epoch grant for the successor when needed.
        return grant.workGroupId().equals(intent.workGroupId()) && grant.targetIntentId().equals(intent.intentId()) && grant.claimEpoch() == intent.version();
    }

    private static boolean requestAppliesToIntent(UUID historicalIntentId, WorkIntent intent) {
        // Requests created after a continuation against the predecessor are stale; only the
        // explicitly retargeted successor request remains actionable.
        return historicalIntentId.equals(intent.intentId());
    }

/**
     * Returns whether the reviewer has a terminal validation for the producer revision.
     */
    static boolean hasCompletedReview(PredictionEventStore store, WorkIntent reviewer, WorkIntent producer) {
        return store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId())).filter(grant -> grant.targetParticipant().equals(reviewer.participant()))
                .filter(grant -> grant.targetIntentId().equals(producer.intentId())).filter(grant -> grant.claimEpoch() == producer.version())
                .filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId())).anyMatch(grant -> store.workGroupProjection().reviewValidationForGrant(grant.grantId()).isPresent());
    }

static AgentResponse pendingReviewRequestResponse(Map<String, Object> collaboration, PredictionEventStore store, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        for (CoordinationRequest request : store.collaborationProjection().requests()) {
            if (request.status() != CoordinationRequest.Status.PENDING || request.kind() != CoordinationRequest.Kind.REVIEW || !request.requester().equals(participantId)) {
                continue;
            }
            Map<String, Object> requestProjection = enrichPendingRequest(CollaborationResponseMapper.requestMap(request), store);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(NextActionKeys.REQUEST_ID, request.requestId().toString());
            payload.put(NextActionKeys.INTENT_ID, request.conflictingIntentId().toString());
            payload.put("target", request.target());
            payload.put(NextActionKeys.STATUS, request.status().name());
            if (requestProjection.get(NextActionKeys.WORK_GROUP_ID) != null) {
                payload.put(NextActionKeys.WORK_GROUP_ID, requestProjection.get(NextActionKeys.WORK_GROUP_ID));
            }
            if (requestProjection.get(NextActionKeys.CLAIM_EPOCH) != null) {
                payload.put(NextActionKeys.CLAIM_EPOCH, requestProjection.get(NextActionKeys.CLAIM_EPOCH));
            }

            Map<String, Object> result = new LinkedHashMap<>(collaboration);
            result.put("reviewRequestPending", true);
            result.put("reviewRequest", requestProjection);
            result.put(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_WAIT);
            result.put(NextActionKeys.NEXT_PROTOCOL_KIND, NextActionProtocol.KIND_REVIEW_ADMISSION);
            result.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, payload);
            result.put("instruction", "Poll get_next_action for the owner response; do not resubmit this request.");
            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
        }
        return null;
    }

/**
     * The work groups whose review a participant whose lane ended may still owe: those it produced a snapshot in,
     * and those of a re-merge it is granted to review again (ADR-0098 step 5, designer item 14).
     */
    static Set<UUID> completedParticipantWorkGroups(PredictionEventStore store, String participant) {
        Set<UUID> groups = new LinkedHashSet<>();
        for (TaskSnapshotRecord snapshot : store.taskCompletionProjection().allSnapshots()) {
            if (participant.equals(snapshot.provenance().participant())) {
                groups.add(snapshot.provenance().workGroupId());
            }
        }
        remergeGrants(store, List.of(participant)).forEach(grant -> groups.add(grant.workGroupId()));
        return Set.copyOf(groups);
    }

    /**
     * The grants not yet taken up that ask one of these participants to review a re-merge snapshot again (ADR-0098
     * step 5, designer item 14). A re-merge snapshot is no review candidate: only its grant offers it.
     *
     * @param store        current project projection
     * @param participants exact participant handles
     * @return the grants, in log order
     */
    static List<LaneGrant> remergeGrants(PredictionEventStore store, java.util.Collection<String> participants) {
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        return workGroups.grants().stream().filter(grant -> participants.contains(grant.targetParticipant()))
                .filter(grant -> grant.snapshotId() != null && workGroups.grantAvailable(grant.grantId()) && !workGroups.grantSuperseded(grant.grantId()))
                .filter(grant -> completion.remergeOf(new org.synesis.coordination.domain.identity.SnapshotId(grant.snapshotId())).isPresent()).toList();
    }

static Map<String, Object> enrichPendingRequest(Map<String, Object> request, PredictionEventStore store) {
        Map<String, Object> enriched = new LinkedHashMap<>(request);
        Object conflictingIntent = request.get("conflictingIntentId");
        if (conflictingIntent instanceof String intentId) {
            store.collaborationProjection().activeIntents().stream().filter(intent -> intent.intentId().toString().equals(intentId)).findFirst().ifPresent(intent -> {
                enriched.put(NextActionKeys.INTENT_ID, intent.intentId().toString());
                enriched.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
                enriched.put(NextActionKeys.CLAIM_EPOCH, intent.version());
            });
        }
        return enriched;
    }

/**
     * Builds the owner's answer to the first review admission asked of it: accept, or reject with
     * the refusal that accepting would meet. Several producers may ask for the same snapshot; only
     * one can be admitted, and an owner shown an accept that is refused every time was shown it
     * again ahead of its own work, while the requester waited for an answer that never came.
     *
     * @param store               current project projection
     * @param pendingCoordination pending requests addressed to the owner
     * @return the response action, or {@code null} when no review admission is pending
     */
    static Map<String, Object> reviewAcceptanceAction(PredictionEventStore store, List<Map<String, Object>> pendingCoordination) {
        for (Map<String, Object> request : pendingCoordination) {
            if (!"REVIEW".equals(request.get(NextActionKeys.KIND))) {
                continue;
            }
            Object requestId = request.get(NextActionKeys.REQUEST_ID);
            Object intentId = request.get(NextActionKeys.INTENT_ID);
            Object workGroupId = request.get(NextActionKeys.WORK_GROUP_ID);
            Object claimEpoch = request.get(NextActionKeys.CLAIM_EPOCH);
            if (!(requestId instanceof String) || !(intentId instanceof String) || !(workGroupId instanceof String) || !(claimEpoch instanceof Number)) {
                continue;
            }
            String refusal = admissionRefusal(store, (String) requestId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("coordinationRequest", requestId);
            payload.put("coordinationStatus", (refusal == null ? CoordinationRequest.Status.ACCEPTED : CoordinationRequest.Status.REJECTED).name());
            payload.put("proposal", refusal == null ? "admitted" : "not admitted: " + refusal);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put(NextActionKeys.REQUEST_ID, requestId);
            context.put(NextActionKeys.KIND, request.get(NextActionKeys.KIND));
            context.put(NextActionKeys.WORK_GROUP_ID, workGroupId);
            context.put(NextActionKeys.INTENT_ID, intentId);
            context.put(NextActionKeys.CLAIM_EPOCH, claimEpoch);
            context.put("requester", request.get("requester"));
            context.put("target", request.get("target"));
            return Map.of(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_RESPOND_COORDINATION, NextActionKeys.NEXT_PROTOCOL_KIND, NextActionProtocol.KIND_COORDINATION_RESPONSE, NextActionKeys.NEXT_PROTOCOL_PAYLOAD, payload, "nextProtocolContext", context);
        }
        return null;
    }

    /**
     * Returns the refusal that accepting the review request would meet, by the rules the
     * acceptance itself is checked against, or {@code null} when it would be admitted.
     */
    private static String admissionRefusal(PredictionEventStore store, String requestId) {
        CoordinationRequest request = store.collaborationProjection().requests().stream().filter(candidate -> candidate.requestId().toString().equals(requestId)).findFirst().orElse(null);
        if (request == null) {
            return null;
        }
        try {
            ReviewAdmissionRules.validateReviewApproval(store, request, CoordinationRequest.Status.ACCEPTED);
            return null;
        } catch (java.io.IOException refused) {
            return refused.getMessage();
        }
    }

static List<Map<String, Object>> reviewActions(PredictionEventStore store, String participantId, Set<UUID> reviewGroupFilter) {
        List<Map<String, Object>> actions = new ArrayList<>();
        var projection = store.workGroupProjection();
        List<WorkIntent> activeIntents = store.collaborationProjection().activeIntents();
        for (LaneGrant grant : projection.grants()) {
            // A revoked grant holds no review: its reviewer could not be reached, and its verdict would be refused.
            if (projection.grantSuperseded(grant.grantId()) || projection.grantRevoked(grant.identity()) || grantTargetsSupersededIntent(store, grant)) {
                continue;
            }
            if (!grant.targetParticipant().equals(participantId)) {
                continue;
            }
            if (reviewGroupFilter != null && !reviewGroupFilter.contains(grant.workGroupId())) {
                continue;
            }
            WorkIntent reviewedIntent = activeIntents.stream().filter(intent -> intent.intentId().equals(grant.targetIntentId())).findFirst().orElse(null);
            // A grant naming a snapshot finds it by its ID: a re-merge snapshot is in no task's history (ADR-0098 step 5).
            Stream<TaskSnapshotRecord> named = grant.snapshotId() == null ? store.taskCompletionProjection().allSnapshots().stream()
                    : store.taskCompletionProjection().findSnapshotById(grant.snapshotId()).stream();
            TaskSnapshotRecord snapshot = named
                    .filter(value -> value.provenance().workGroupId().equals(grant.workGroupId())).filter(value -> value.provenance().laneId().equals(grant.targetIntentId()))
                    .filter(value -> value.provenance().claimEpoch() == grant.claimEpoch()).findFirst().orElse(null);
            if (reviewedIntent == null && snapshot == null) {
                continue;
            }
            if (reviewedIntent != null && reviewedIntent.role() != WorkIntent.Role.PRODUCER) {
                continue;
            }
            String reviewedParticipant = reviewedIntent == null ? snapshot.provenance().participant() : reviewedIntent.participant();
            // A malformed or historical grant must not turn a producer into its own reviewer.
            // Keep the durable record available for audit, but fail closed in the agent-facing
            // projection so it cannot be used for self-approval.
            if (reviewedParticipant.equals(participantId)) {
                continue;
            }
            boolean activeReviewer = activeIntents.stream()
                    .anyMatch(intent -> intent.participant().equals(participantId) && intent.workGroupId().equals(grant.workGroupId()) && intent.role() == WorkIntent.Role.REVIEWER && (reviewedIntent != null
                            ? reviewTargetsMatch(intent, reviewedIntent) : reviewTargetsMatch(intent, snapshot)));
            boolean unannouncedReviewer = activeIntents.stream().noneMatch(intent -> intent.participant().equals(participantId));
            boolean explicitReviewGrant = hasExplicitReviewGrant(store, grant, participantId);
            boolean completedReviewer = reviewGroupFilter != null && reviewGroupFilter.contains(grant.workGroupId());
            if (!activeReviewer && !unannouncedReviewer && !explicitReviewGrant && !completedReviewer) {
                continue;
            }
            if (snapshot == null && !projection.grantAvailable(grant.grantId())) {
                Map<String, Object> waiting = new LinkedHashMap<>();
                waiting.put(NextActionKeys.STATE, "SNAPSHOT_PENDING");
                waiting.put(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_WAIT);
                waiting.put(NextActionKeys.NEXT_PROTOCOL_KIND, NextActionProtocol.KIND_REVIEW_VALIDATION);
                waiting.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD,
                        Map.of(NextActionKeys.GRANT_ID,
                                grant.grantId().toString(),
                                NextActionKeys.WORK_GROUP_ID,
                                grant.workGroupId().toString(),
                                NextActionKeys.REVIEWED_INTENT_ID,
                                grant.targetIntentId().toString(),
                                NextActionKeys.REVIEWED_PARTICIPANT_ID,
                                reviewedParticipant,
                                NextActionKeys.REVIEWER_PARTICIPANT,
                                grant.targetParticipant(),
                                "snapshotRequired",
                                true));
                waiting.put("grant", CollaborationResponseMapper.laneGrantMap(grant));
                actions.add(waiting);
                continue;
            }
            if (projection.reviewValidationForGrant(grant.grantId()).isPresent()) {
                continue;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put(NextActionKeys.STATE, projection.grantAvailable(grant.grantId()) ? "GRANT_AVAILABLE" : "VALIDATION_REQUIRED");
            action.put(NextActionKeys.NEXT_PROTOCOL_ACTION, projection.grantAvailable(grant.grantId()) ? NextActionProtocol.ACTION_REQUEST_COORDINATION : NextActionProtocol.ACTION_REVIEW_DECISION);
            action.put(NextActionKeys.NEXT_PROTOCOL_KIND, projection.grantAvailable(grant.grantId()) ? NextActionProtocol.KIND_WORK_GROUP_JOIN : NextActionProtocol.KIND_REVIEW_VALIDATION);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(NextActionKeys.GRANT_ID, grant.grantId().toString());
            payload.put(NextActionKeys.INTENT_ID, grant.targetIntentId().toString());
            payload.put(NextActionKeys.REVIEWED_INTENT_ID, grant.targetIntentId().toString());
            payload.put(NextActionKeys.REVIEWED_PARTICIPANT_ID, reviewedParticipant);
            payload.put(NextActionKeys.REVIEWER_PARTICIPANT, grant.targetParticipant());
            payload.put(NextActionKeys.CLAIM_EPOCH, grant.claimEpoch());
            if (snapshot != null) {
                payload.put(NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId());
            }
            if (projection.grantAvailable(grant.grantId())) {
                payload.put(NextActionKeys.WORK_GROUP_ID, grant.workGroupId().toString());
                payload.put(NextActionKeys.TARGET_PARTICIPANT, grant.targetParticipant());
            } else {
                TaskSnapshotRecord reviewedSnapshot = Objects.requireNonNull(snapshot, "review snapshot");
                action.put(NextActionKeys.REVIEW_DECISION, Map.of("required", true, "field", "result", "allowedResults", List.of("accepted", "rejected"), "rejectionReasonRequired", true));
                action.put(NextActionKeys.REVIEW_ACCESS,
                        Map.of("workspace",
                                "immutable_review_snapshot",
                                NextActionKeys.SNAPSHOT_ID,
                                reviewedSnapshot.snapshotId(),
                                "commitSha",
                                snapshot.commitSha(),
                                "readTools",
                                List.of(NextActionProtocol.TOOL_READ_FILE, NextActionProtocol.TOOL_RUN_COMMAND),
                                "writeLaneProtected",
                                true,
                                "instruction",
                                "Inspect the immutable snapshot with read_file and run_command before deciding; do not patch it."));
            }
            action.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, payload);
            action.put("grant", CollaborationResponseMapper.laneGrantMap(grant));
            if (snapshot != null) {
                action.put(NextActionKeys.SNAPSHOT, CollaborationResponseMapper.snapshotMap(snapshot, store));
            }
            actions.add(action);
        }
        if (actions.isEmpty() && !participantId.isBlank()) {
            Set<String> completedReviewRevisions = new LinkedHashSet<>();
            Set<UUID> pendingReviewIntents = new LinkedHashSet<>();
            for (LaneGrant grant : projection.grants()) {
                if (grant.targetParticipant().equals(participantId) && !grantTargetsSupersededIntent(store, grant) && projection.reviewValidationForGrant(grant.grantId()).isPresent()) {
                    completedReviewRevisions.add(grant.targetIntentId() + ":" + grant.claimEpoch());
                }
            }
            for (CoordinationRequest request : store.collaborationProjection().requests()) {
                if (request.status() == CoordinationRequest.Status.PENDING && request.kind() == CoordinationRequest.Kind.REVIEW && request.requester().equals(participantId)) {
                    pendingReviewIntents.add(request.conflictingIntentId());
                }
            }
            List<WorkIntent> callerReviewers = activeIntents.stream().filter(intent -> intent.participant().equals(participantId)).filter(intent -> intent.role() == WorkIntent.Role.REVIEWER)
                    .filter(intent -> reviewGroupFilter == null || reviewGroupFilter.contains(intent.workGroupId())).toList();
            for (WorkIntent reviewer : callerReviewers) {
                WorkGroup group = projection.group(reviewer.workGroupId()).orElse(null);
                if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                    continue;
                }
                WorkIntent owner = selectReviewTarget(store, activeIntents, reviewer, participantId, completedReviewRevisions, pendingReviewIntents);
                if (owner == null || group.reviewPolicy().mandatory()) {
                    continue;
                }
                actions.add(reviewAdmissionAction(group, owner, participantId));
            }
        }
        return List.copyOf(actions);
    }

static List<Map<String, Object>> reviewCandidates(PredictionEventStore store) {
        record SnapshotProducer(TaskSnapshotRecord snapshot, WorkIntent producer) {

        }

        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        return completion.allSnapshots().stream().filter(TaskSnapshotRecord::reviewRequired)
                .filter(snapshot -> completion.snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) == TaskCompletionState.REVIEW_PENDING)
                .filter(snapshot -> workGroups.group(snapshot.provenance().workGroupId()).map(group -> group.status() == WorkGroup.Status.ACTIVE).orElse(false)).flatMap(snapshot -> {
                    WorkIntent producer = reviewProducerIntent(store, snapshot);
                    return producer == null ? Stream.empty() : Stream.of(new SnapshotProducer(snapshot, producer));
                }).filter(source -> workGroups.grants().stream()
                        .noneMatch(grant -> ReviewAdmissionRules.holdsReview(store, grant) && (source.snapshot().snapshotId().equals(grant.snapshotId()) || (
                                grant.snapshotId() == null && grant.workGroupId().equals(source.snapshot().provenance().workGroupId()) && grant.targetIntentId().equals(source.snapshot().provenance().laneId())
                                        && grant.claimEpoch() == source.snapshot().provenance().claimEpoch()))))
                // An accepted request holds the review until its grant is issued; from then on the grant does, as at
                // admission (ReviewAdmissionRules), so a review released from an unreachable reviewer is offered again.
                .filter(source -> collaboration.requests().stream()
                        .noneMatch(request -> request.kind() == CoordinationRequest.Kind.REVIEW && source.snapshot().snapshotId().equals(request.snapshotId()) && (request.status() == CoordinationRequest.Status.PENDING
                                || request.status() == CoordinationRequest.Status.ACCEPTED && workGroups.grants().stream()
                                        .noneMatch(grant -> grant.grantId().equals(LaneGrant.reviewGrantId(request.requestId())))))).sorted(Comparator.comparing(source -> source.snapshot().snapshotId())).map(source -> {
                    TaskSnapshotRecord snapshot = source.snapshot();
                    WorkGroup group = workGroups.group(snapshot.provenance().workGroupId()).orElseThrow();
                    WorkIntent producer = source.producer();
                    Map<String, Object> metadata = new LinkedHashMap<>(CollaborationResponseMapper.snapshotMap(snapshot, store));
                    metadata.put("candidateState", "AVAILABLE");
                    metadata.put(NextActionKeys.AUTHORITY_LINEAGE_ID, snapshot.provenance().authorityLineageId().toString());
                    metadata.put("reviewPolicy", Map.of("mandatory", group.reviewPolicy().mandatory(), "independenceRequired", group.reviewPolicy().independenceRequired()));
                    metadata.put("reviewerIntent", reviewerIntentGuidance(group, snapshot));
                    metadata.put("requestCoordination",
                            Map.of(NextActionKeys.KIND,
                                    NextActionProtocol.KIND_WORK_GROUP_JOIN,
                                    NextActionKeys.PAYLOAD,
                                    Map.of(NextActionKeys.WORK_GROUP_ID,
                                            snapshot.provenance().workGroupId().toString(),
                                            NextActionKeys.INTENT_ID,
                                            producer.intentId().toString(),
                                            NextActionKeys.SNAPSHOT_ID,
                                            snapshot.snapshotId(),
                                            "proposal",
                                            "Review this exact immutable snapshot")));
                    return metadata;
                }).toList();
    }

/**
     * Lists the review candidates a participant may take up: those whose snapshot neither it nor
     * the lane now holding that work produced, in an authority lineage it never published in, and
     * whose author can admit a reviewer. Independence is still enforced at admission; this only keeps
     * guidance from pointing a caller at work it would be refused or never answered.
     *
     * <p>A review request is answered by the author's lane, and while that lane is held for
     * recovery only its successor can answer it (ADR-0085). A caller that claimed a reviewer lane
     * for such a snapshot could neither be admitted nor, holding a lane, continue the author: in a
     * real run both fresh agents did this and the run stalled. Such a caller is pointed at the
     * continuation instead, and the snapshot is offered again once the author's lane is continued.
     * An author that is suspended, or otherwise not active, cannot answer either: it is held moments
     * later, and in the overnight run across two computers fresh agents took reviews of suspended
     * authors while a continuation waited for them.</p>
     *
     * <p>Only the participant state in the log counts, the same on every computer. A quiet author is
     * not an absent one: an agent told WAIT makes no call until it is woken, so a session lease that has
     * gone stale says nothing about whether the author will answer (ADR-0099).</p>
     *
     * @param store         current project projection
     * @param participantId exact participant handle
     * @return the open candidates the participant did not produce and whose author is active
     */
    static List<Map<String, Object>> reviewCandidatesFor(PredictionEventStore store, String participantId) {
        return reviewCandidatesFor(store, List.of(participantId));
    }

    /**
     * Lists the review candidates that every one of several participants may take up, as
     * {@link #reviewCandidatesFor(PredictionEventStore, String)} decides for one: the sessions an
     * agent has had, where the work any of them produced is the agent's own.
     *
     * @param store        current project projection
     * @param participants exact participant handles
     * @return the open candidates none of the participants produced and whose author is active
     */
    static List<Map<String, Object>> reviewCandidatesFor(PredictionEventStore store, java.util.Collection<String> participants) {
        var collaboration = store.collaborationProjection();
        List<ParticipantId> callers = participants.stream().map(ParticipantId::new).toList();
        return reviewCandidates(store).stream().filter(candidate -> store.taskCompletionProjection().findSnapshotById(String.valueOf(candidate.get(NextActionKeys.SNAPSHOT_ID))).filter(snapshot -> {
            WorkIntent producer = reviewProducerIntent(store, snapshot);
            boolean ownWork = callers.stream().anyMatch(caller -> ReviewAdmissionRules.authoredInLineage(store, caller, snapshot.provenance().lineageIdentity()))
                    || participants.contains(snapshot.provenance().participant()) || producer != null && participants.contains(producer.participant());
            boolean authorAbsent = producer != null && collaboration.participantState(producer.participant()).orElse(null) != Participant.State.ACTIVE;
            return !ownWork && !authorAbsent;
        }).isPresent()).toList();
    }

static List<Map<String, Object>> actionableReviewCandidates(PredictionEventStore store, String participantId, List<Map<String, Object>> candidates) {
        WorkIntent reviewer = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantId)).filter(intent -> intent.role() == WorkIntent.Role.REVIEWER).findFirst()
                .orElse(null);
        if (reviewer == null) {
            return List.of();
        }
        return candidates.stream().filter(candidate -> reviewer.workGroupId().toString().equals(candidate.get(NextActionKeys.WORK_GROUP_ID)))
                .filter(candidate -> store.taskCompletionProjection().findSnapshotById(String.valueOf(candidate.get(NextActionKeys.SNAPSHOT_ID))).filter(snapshot -> reviewTargetsMatch(reviewer, snapshot)).isPresent()).toList();
    }

static WorkIntent reviewProducerIntent(PredictionEventStore store, TaskSnapshotRecord snapshot) {
        var collaboration = store.collaborationProjection();
        var provenance = snapshot.provenance();
        UUID intentId = provenance.laneId();
        Set<UUID> visited = new LinkedHashSet<>();
        while (visited.add(intentId)) {
            var successor = collaboration.successorIntent(intentId);
            if (successor.isEmpty()) {
                return collaboration.intent(intentId).filter(intent -> intent.role() == WorkIntent.Role.PRODUCER).filter(intent -> intent.workGroupId().equals(provenance.workGroupId()))
                        .filter(intent -> intent.taskId().equals(snapshot.taskId())).filter(intent -> intent.authorityLineageId().equals(provenance.authorityLineageId()))
                        .filter(intent -> intent.version() >= provenance.claimEpoch()).orElse(null);
            }
            intentId = successor.orElseThrow();
        }
        return null;
    }

static Map<String, Object> reviewerIntentGuidance(WorkGroup group, TaskSnapshotRecord snapshot) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("goal", "Independently review this candidate against the WorkGroup acceptance criteria");
        task.put("acceptance", group.acceptance());
        task.put(NextActionKeys.WORK_GROUP_ID, group.workGroupId().toString());
        task.put("role", WorkIntent.Role.REVIEWER.wireValue());
        task.put(NextActionKeys.REVIEW_TARGETS, snapshotReviewTargets(snapshot));

        Map<String, Object> claimRequirement = new LinkedHashMap<>();
        claimRequirement.put("field", "task.claims");
        claimRequirement.put("required", true);
        claimRequirement.put("purpose", "review report or test evidence");
        claimRequirement.put("selectorKinds", List.of("path_exact", "path_subtree"));
        claimRequirement.put("mustBeDisjointFrom", List.of("producer snapshot paths", "all active claims"));
        claimRequirement.put("guidance", "Choose a report or test path to claim; Synesis will validate that the claim is disjoint.");

        return Map.of(NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId(), NextActionKeys.TOOL, NextActionProtocol.TOOL_ENSURE_SESSION, NextActionKeys.ARGUMENTS, Map.of("task", task), "claimRequirement", claimRequirement);
    }

static List<Map<String, Object>> snapshotReviewTargets(TaskSnapshotRecord snapshot) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String encoded : snapshot.provenance().claimSelectors()) {
            // Invalid historical selectors are omitted from agent guidance; server-side review
            // admission still validates the immutable snapshot provenance.
            ResourceSelector.parse(encoded).ifPresent(selector -> targets.add(Map.of(NextActionKeys.KIND, selector.kind().name().toLowerCase(Locale.ROOT), "path", selector.value())));
        }
        return List.copyOf(targets);
    }

/**
     * Selects one semantically identified producer without using arrival order as a fallback.
     */
    static WorkIntent selectReviewTarget(PredictionEventStore store,
            List<WorkIntent> activeIntents,
            WorkIntent reviewer,
            String reviewerParticipant,
            Set<String> completedReviewRevisions,
            Set<UUID> pendingReviewIntents) {
        List<WorkIntent> candidates = activeIntents.stream().filter(intent -> intent.role() == WorkIntent.Role.PRODUCER).filter(intent -> intent.workGroupId().equals(reviewer.workGroupId()))
                .filter(intent -> !intent.participant().equals(reviewerParticipant)).filter(intent -> !completedReviewRevisions.contains(intent.intentId() + ":" + intent.version()))
                .filter(intent -> !pendingReviewIntents.contains(intent.intentId())).toList();
        List<WorkIntent> publishedReviewCandidates = candidates.stream().filter(candidate -> hasPublishedReviewRequiredSnapshot(store, candidate)).toList();
        if (!publishedReviewCandidates.isEmpty()) {
            candidates = publishedReviewCandidates;
        }
        if (reviewer.reviewTargetSelectors().isEmpty()) {
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }
        List<WorkIntent> matching = candidates.stream().filter(candidate -> reviewer.reviewTargetSelectors().stream().allMatch(target -> candidate.selectors().stream().anyMatch(target::overlaps))).toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }

static boolean hasPublishedReviewRequiredSnapshot(PredictionEventStore store, WorkIntent producer) {
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(producer.taskId()))
                .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(producer.authorityLineageId())).filter(snapshot -> snapshot.provenance().claimEpoch() <= producer.version())
                .anyMatch(org.synesis.coordination.domain.task.TaskSnapshotRecord::reviewRequired);
    }

/**
     * Returns whether a reviewer declaration covers the producer intent.
     */
    static boolean reviewTargetsMatch(WorkIntent reviewer, WorkIntent producer) {
        return reviewer.reviewTargetSelectors().stream().allMatch(targetSelector -> producer.selectors().stream().anyMatch(targetSelector::overlaps));
    }

/**
     * Returns whether a reviewer declaration covers an already published snapshot claim.
     */
    static boolean reviewTargetsMatch(WorkIntent reviewer, TaskSnapshotRecord snapshot) {
        return reviewer.reviewTargetSelectors().stream().allMatch(targetSelector -> snapshot.provenance().claimSelectors().stream()
                .anyMatch(encoded -> ResourceSelector.parse(encoded).map(targetSelector::overlaps).orElse(false)));
    }

/**
     * Returns whether a durable accepted review request authorizes this grant recipient: the grant
     * its acceptance issued, or the successor of that grant a continuation carried forward to the
     * participant that now holds the request, for the same snapshot.
     */
    static boolean hasExplicitReviewGrant(PredictionEventStore store, LaneGrant grant, String participantId) {
        return store.collaborationProjection().requests().stream().filter(request -> request.kind() == CoordinationRequest.Kind.REVIEW).filter(request -> request.status() == CoordinationRequest.Status.ACCEPTED)
                .filter(request -> request.requester().equals(participantId)).filter(request -> request.conflictingIntentId().equals(grant.targetIntentId()))
                .anyMatch(request -> LaneGrant.reviewGrantId(request.requestId()).equals(grant.grantId()) || (request.snapshotId() != null && request.snapshotId().equals(grant.snapshotId())));
    }

/**
     * Builds an explicit review-admission request for one reviewed producer intent.
     */
    static Map<String, Object> reviewAdmissionAction(WorkGroup group, WorkIntent owner, String reviewerParticipant) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put(NextActionKeys.STATE, "REVIEW_ADMISSION_REQUIRED");
        action.put(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_REQUEST_COORDINATION);
        action.put(NextActionKeys.NEXT_PROTOCOL_KIND, NextActionProtocol.KIND_WORK_GROUP_JOIN);
        action.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD,
                Map.of(NextActionKeys.WORK_GROUP_ID,
                        group.workGroupId().toString(),
                        NextActionKeys.INTENT_ID,
                        owner.intentId().toString(),
                        NextActionKeys.REVIEWED_INTENT_ID,
                        owner.intentId().toString(),
                        NextActionKeys.REVIEWED_PARTICIPANT_ID,
                        owner.participant(),
                        NextActionKeys.REVIEWER_PARTICIPANT,
                        reviewerParticipant,
                        "proposal",
                        "Review the immutable snapshot for this work group"));
        action.put(NextActionKeys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
        return action;
    }

}
