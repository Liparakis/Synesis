package org.synesis.workspace.application.agent.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * Projects collaboration records into the stable metadata maps embedded in agent responses.
 * This mapper owns response shape only; it does not choose actions or establish authority.
 */
public final class CollaborationResponseMapper {

    private CollaborationResponseMapper() {
    }

    static Map<String, Object> intentMap(WorkIntent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("intentId", intent.intentId().toString());
        map.put("participant", intent.participant());
        map.put("provider", intent.provider());
        map.put("goal", intent.goal());
        map.put("acceptance", intent.acceptance());
        map.put("selectors", intent.selectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
        map.put("version", intent.version());
        map.put("claimEpoch", intent.version());
        map.put("workGroupId", intent.workGroupId().toString());
        map.put("authorityLineageId", intent.authorityLineageId().toString());
        map.put("status", intent.status().name());
        map.put("role", intent.role().wireValue());
        map.put("reviewTargets", intent.reviewTargetSelectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
        map.put("knownDependencies", intent.knownDependencies());
        return map;
    }

    static Map<String, Object> participantMap(Participant participant) {
        Map<String, Object> map = participantFields(participant);
        map.put("claims", participant.claims().stream().map(CollaborationResponseMapper::selectorMap).toList());
        return map;
    }

    /**
     * Returns the shared participant fields used by agent and control-plane projections.
     *
     * <p>Callers add their own claims representation because the existing wire surfaces use
     * different selector keys.</p>
     *
     * @param participant collaboration participant
     * @return mutable insertion-ordered map of the shared participant fields
     */
    public static Map<String, Object> participantFields(Participant participant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", participant.id());
        map.put("provider", participant.provider());
        map.put("goal", participant.goal());
        map.put("state", participant.state().name());
        map.put("lastVerifiedActivity", participant.lastVerifiedActivity());
        return map;
    }

    static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", request.requestId().toString());
        map.put("inboxItemId", request.requestId().toString());
        map.put("requester", request.requester());
        map.put("target", request.target());
        map.put("conflictingIntentId", request.conflictingIntentId().toString());
        map.put("reviewedIntentId", request.conflictingIntentId().toString());
        map.put("reviewedParticipantId", request.target());
        map.put("reviewerParticipant", request.requester());
        map.put("kind", request.kind().name());
        map.put("proposal", request.proposal());
        if (request.snapshotId() != null) {
            map.put("snapshotId", request.snapshotId());
        }
        map.put("status", request.status().name());
        return map;
    }

    static Map<String, Object> workGroupMap(WorkGroup group) {
        return Map.of("workGroupId",
                group.workGroupId().toString(),
                "projectId",
                group.projectId().toString(),
                "goal",
                group.goal(),
                "acceptance",
                group.acceptance(),
                "version",
                group.version(),
                "status",
                group.status().name(),
                "reviewPolicy",
                Map.of("mandatory", group.reviewPolicy().mandatory(), "independenceRequired", group.reviewPolicy().independenceRequired()));
    }

    static Map<String, Object> laneGrantMap(LaneGrant grant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("grantId", grant.grantId().toString());
        map.put("workGroupId", grant.workGroupId().toString());
        map.put("targetIntentId", grant.targetIntentId().toString());
        map.put("reviewedIntentId", grant.targetIntentId().toString());
        map.put("targetParticipant", grant.targetParticipant());
        map.put("reviewerParticipant", grant.targetParticipant());
        map.put("claimEpoch", grant.claimEpoch());
        map.put("singleUse", grant.singleUse());
        if (grant.snapshotId() != null) {
            map.put("snapshotId", grant.snapshotId());
        }
        return map;
    }

    static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot, PredictionEventStore store) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", snapshot.taskId().toString());
        map.put("snapshotId", snapshot.snapshotId());
        map.put("baseCommit", snapshot.baseCommit());
        map.put("commitSha", snapshot.commitSha());
        map.put("changedPaths", snapshot.changedPaths());
        map.put("summary", snapshot.summary());
        map.put("createdAtMillis", snapshot.createdAtMillis());
        map.put("laneId", snapshot.provenance().laneId().toString());
        map.put("claimEpoch", snapshot.provenance().claimEpoch());
        map.put("workGroupId", snapshot.provenance().workGroupId().toString());
        map.put("participant", snapshot.provenance().participant());
        map.put("reviewRequired", snapshot.reviewRequired());
        store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).ifPresent(state -> map.put("snapshotState", state.name()));
        store.workGroupProjection().reviewValidationForSnapshot(snapshot.snapshotId())
                .ifPresent(decision -> map.put("reviewDecision", Map.of("grantId", decision.grantId().toString(), "snapshotId", decision.snapshotId(), "result", decision.result())));
        return map;
    }

    static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of("kind", selector.kind().name(), "path", selector.value());
    }
}
