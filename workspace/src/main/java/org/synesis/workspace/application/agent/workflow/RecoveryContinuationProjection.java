package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.projectedList;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.selectorMaps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Projects recovery-held lanes that a different participant may explicitly continue, and the
 * response that offers them.
 *
 * <p>Read-only: it grants nothing, changes no claims and transfers no authority. Separate because
 * both the collaboration view and the durable stage order consume it independently.</p>
 */
final class RecoveryContinuationProjection {

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
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        List<WorkIntent> callerIntents = collaboration.activeIntents().stream().filter(existing -> existing.participant().equals(participantId)).toList();
        List<ResourceSelector> callerSelectors = callerIntents.stream().flatMap(existing -> existing.selectors().stream()).toList();

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (WorkIntent intent : collaboration.activeIntents()) {
            boolean recoveryHeld = collaboration.participantState(intent.participant()).orElse(null) == Participant.State.RECOVERY_HELD && collaboration.recoverySnapshotReference(intent.participant()).isPresent();
            if (!recoveryHeld) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            boolean sameParticipant = intent.participant().equals(participantId);
            candidates.add(continuationCandidate(intent, group, continuationBlock(intent, sameParticipant, callerIntents, callerSelectors, callerProvider)));
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
     * @return the candidate map; {@code available} is {@code false} when blocked
     */
    private static Map<String, Object> continuationCandidate(WorkIntent intent, WorkGroup group, String blockedReason) {
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
        candidate.put("formerOwnerState", Participant.State.RECOVERY_HELD.name());
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
     * Returns the highest-priority explicit successor action for the current participant.
     *
     * <p>With exactly one available candidate the executable payload is included; with several the
     * caller must choose explicitly because no candidate is ever auto-assigned.</p>
     *
     * @param participantId exact caller participant
     * @param collaboration public collaboration projection
     * @return a continuation action, or {@code null} when no candidate is available
     */
    static AgentResponse continuationAction(String participantId, Map<String, Object> collaboration) {
        List<Map<String, Object>> available = projectedList(collaboration, NextActionKeys.CONTINUATION_CANDIDATES).stream().filter(candidate -> Boolean.TRUE.equals(candidate.get(NextActionKeys.AVAILABLE))).toList();
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
