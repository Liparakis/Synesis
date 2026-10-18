package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.projectedList;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.selectorMaps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.AwayLanes;

/**
 * Projects recovery-held lanes that a different participant may explicitly continue, and the
 * response that offers them.
 *
 * <p>Read-only: it grants nothing, changes no claims and transfers no authority. Separate because
 * both the collaboration view and the durable stage order consume it independently.</p>
 */
final class RecoveryContinuationProjection {

    /** How a candidate names the owner of another computer's lane when that computer is away (ADR-0098 step 7). */
    static final String AWAY = "AWAY";

    private RecoveryContinuationProjection() {
    }

    /**
     * Projects recovery-held work that a different current participant may explicitly continue.
     *
     * <p>This is a read-only projection. It never issues a grant, changes claims or exposes the
     * internal snapshot reference. The continuation request derives its target identity from the
     * exact authenticated caller and revalidates every field under the project append lock.</p>
     *
     * @param store         current project projection
     * @param participantId exact current caller participant
     * @return deterministic, public-safe continuation candidates ordered by source intent id
     */
    static List<Map<String, Object>> continuationCandidates(PredictionEventStore store, String participantId, String callerProvider) {
        return continuationCandidates(store, participantId, callerProvider, null);
    }

    /**
     * Projects the lanes a caller may continue, with the lanes of computers that are away when this computer is
     * stalled (ADR-0098 step 7, designer item 10): those are offered as {@code AWAY}, with the same request, and taken
     * over from what of them reached this computer. Another computer's lane held for recovery is that computer's to
     * continue, and is not offered here: its recovery snapshot is in that computer's folders.
     *
     * @param store         current project projection
     * @param participantId exact current caller participant
     * @param thisNode      this computer's node ID, or {@code null} when unknown, which offers no away lane and
     *                      every recovery-held one, as before step 7
     * @return deterministic, public-safe continuation candidates ordered by source intent id
     */
    static List<Map<String, Object>> continuationCandidates(PredictionEventStore store, String participantId, String callerProvider, String thisNode) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        List<WorkIntent> callerIntents = collaboration.activeIntents().stream().filter(existing -> existing.participant().equals(participantId)).toList();
        List<ResourceSelector> callerSelectors = callerIntents.stream().flatMap(existing -> existing.selectors().stream()).toList();

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (WorkIntent intent : collaboration.activeIntents()) {
            boolean recoveryHeld = collaboration.participantState(intent.participant()).orElse(null) == Participant.State.RECOVERY_HELD && collaboration.recoverySnapshotReference(intent.participant()).isPresent();
            if (!recoveryHeld || (thisNode != null && AwayLanes.anotherComputers(store, intent, thisNode))) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            boolean sameParticipant = intent.participant().equals(participantId);
            candidates.add(continuationCandidate(intent, group, continuationBlock(intent, sameParticipant, callerIntents, callerSelectors, callerProvider), Participant.State.RECOVERY_HELD.name()));
        }
        if (thisNode != null) {
            for (WorkIntent intent : AwayLanes.offered(store, store.rootDirectory(), thisNode)) {
                WorkGroup group = workGroups.group(intent.workGroupId()).orElseThrow();
                // Any provider may take it over: no session of the away computer is continued.
                candidates.add(continuationCandidate(intent, group, continuationBlock(intent, false, callerIntents, callerSelectors, null), AWAY));
            }
        }
        candidates.sort(Comparator.comparing(candidate -> String.valueOf(candidate.get(NextActionKeys.SOURCE_INTENT_ID))));
        return List.copyOf(candidates);
    }

    /**
     * Builds the public-safe description of one continuation candidate.
     *
     * @param intent        the recovery-held intent
     * @param group         the active work group of the intent
     * @param blockedReason why the caller may not continue it, or {@code null} when it may
     * @param ownerState    {@code RECOVERY_HELD}, or {@code AWAY} for another computer's lane taken over
     * @return the candidate map; {@code available} is {@code false} when blocked
     */
    private static Map<String, Object> continuationCandidate(WorkIntent intent, WorkGroup group, String blockedReason, String ownerState) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put(NextActionKeys.SOURCE_INTENT_ID, intent.intentId().toString());
        candidate.put(NextActionKeys.INTENT_ID, intent.intentId().toString());
        candidate.put("taskId", intent.taskId().toString());
        candidate.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
        candidate.put("goal", intent.goal());
        candidate.put("acceptance", intent.acceptance());
        candidate.put(NextActionKeys.SELECTORS, selectorMaps(intent));
        candidate.put(NextActionKeys.CLAIM_EPOCH, intent.version());
        candidate.put("role", intent.role().wireValue());
        candidate.put(NextActionKeys.KNOWN_DEPENDENCIES, intent.knownDependencies());
        candidate.put("formerOwner", intent.participant());
        candidate.put("formerOwnerState", ownerState);
        candidate.put(NextActionKeys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
        candidate.put("unfinished", true);
        candidate.put("blocksWorkGroupCompletion", true);
        candidate.put("durableArtifactsAvailable", true);
        candidate.put("provider", intent.provider());
        candidate.put(NextActionKeys.AVAILABLE, blockedReason == null);
        candidate.put("continuationRequest", Map.of(NextActionKeys.KIND, NextActionProtocol.KIND_CONTINUATION, NextActionKeys.PAYLOAD, Map.of(NextActionKeys.INTENT_ID, intent.intentId().toString(), NextActionKeys.CLAIM_EPOCH, intent.version())));
        if (blockedReason != null) {
            candidate.put("blockedReason", blockedReason);
        }
        return candidate;
    }

    /**
     * Names the reason the caller may not continue a recovery-held lane, most specific first.
     *
     * <p>A connection owns one lane and one worker context, so a caller that already holds a lane
     * cannot take a second. A lane is continued from its own provider's session binding, so a
     * caller of another provider cannot take it either. Offering either only hands the caller a
     * request that is then refused.</p>
     *
     * @return the stable reason, or {@code null} when the caller may continue the lane
     */
    private static String continuationBlock(WorkIntent held, boolean sameParticipant, List<WorkIntent> callerIntents, List<ResourceSelector> callerSelectors, String callerProvider) {
        if (sameParticipant) {
            return "CURRENT_PARTICIPANT_IS_FORMER_OWNER";
        }
        if (overlapsAny(callerSelectors, held.selectors())) {
            return "OVERLAPPING_CLAIM";
        }
        if (!callerIntents.isEmpty()) {
            return "CURRENT_PARTICIPANT_HOLDS_A_LANE";
        }
        if (callerProvider != null && !callerProvider.equals(held.provider())) {
            return "CONTINUATION_REQUIRES_SAME_PROVIDER";
        }
        return null;
    }

    /**
     * Lists the continuation candidates a caller is pointed at before any review: none when it can
     * instead be admitted as the reviewer of a snapshot and no waiting reviewer needs one of them.
     *
     * <p>A session holds one lane, and an author whose snapshot awaits review needs two: a reviewer
     * and someone in the author's lane to admit it. A held author named by a pending review request
     * comes first, because that reviewer waits for its successor (ADR-0085). Otherwise a review the
     * caller can be admitted to comes first, because it completes a pair a continued author is
     * waiting in; continuing a second held author instead left two successors waiting for reviewers
     * that never came. With no such review, every available continuation is offered.</p>
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @param candidates    the caller's continuation candidates, from {@link #continuationCandidates}
     * @return the available candidates to offer, empty when there are none or a review comes first
     */
    static List<Map<String, Object>> continuationsFirst(PredictionEventStore store, String participantId, List<Map<String, Object>> candidates) {
        List<Map<String, Object>> available = candidates.stream().filter(candidate -> Boolean.TRUE.equals(candidate.get(NextActionKeys.AVAILABLE))).toList();
        boolean awaitedByReviewer = available.stream().anyMatch(candidate -> store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING && request.kind() == CoordinationRequest.Kind.REVIEW
                        && request.conflictingIntentId().toString().equals(candidate.get(NextActionKeys.SOURCE_INTENT_ID))));
        return awaitedByReviewer || AgentReviewActionProjection.reviewCandidatesFor(store, participantId).isEmpty() ? available : List.of();
    }

    /**
     * Returns the highest-priority explicit successor action for the current participant.
     *
     * <p>With exactly one available candidate the executable payload is included; with several the
     * caller must choose explicitly because no candidate is ever auto-assigned.</p>
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @param collaboration public collaboration projection
     * @return a continuation action, or {@code null} when no candidate is available or a review
     *         comes first (see {@link #continuationsFirst})
     */
    static AgentResponse continuationAction(PredictionEventStore store, String participantId, Map<String, Object> collaboration) {
        List<Map<String, Object>> available = continuationsFirst(store, participantId, projectedList(collaboration, NextActionKeys.CONTINUATION_CANDIDATES));
        if (available.isEmpty()) {
            return null;
        }
        Map<String, Object> selected = available.getFirst();
        Map<String, Object> result = new LinkedHashMap<>(collaboration);
        result.put(NextActionKeys.STATE, NextActionProtocol.STATE_RECOVERY_CONTINUATION_AVAILABLE);
        result.put("continuationAvailable", true);
        result.put("continuationSelectionRequired", available.size() > 1);
        result.put(NextActionKeys.NEXT_PROTOCOL_ACTION, NextActionProtocol.ACTION_REQUEST_COORDINATION);
        result.put(NextActionKeys.NEXT_PROTOCOL_KIND, NextActionProtocol.KIND_CONTINUATION);
        if (available.size() == 1) {
            result.put("selectedContinuation", selected);
            result.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, Map.of(NextActionKeys.INTENT_ID, selected.get(NextActionKeys.SOURCE_INTENT_ID), NextActionKeys.CLAIM_EPOCH, selected.get(NextActionKeys.CLAIM_EPOCH)));
        } else {
            result.put("selectionInstruction", "Choose one continuation candidate explicitly; no candidate is auto-assigned.");
        }
        result.put("continuationParticipant", participantId);
        return new AgentResponse(AgentStatus.READY, AgentReason.CONTINUATION_AVAILABLE, AgentNextAction.REQUEST_COORDINATION, result);
    }

    /**
     * Returns whether any claimed selector overlaps any requested selector.
     *
     * @param claimed   selectors already claimed
     * @param requested selectors being requested
     * @return {@code true} if at least one pair overlaps
     */
    private static boolean overlapsAny(Collection<ResourceSelector> claimed, Collection<ResourceSelector> requested) {
        return claimed.stream().anyMatch(existing -> requested.stream().anyMatch(existing::overlaps));
    }
}
