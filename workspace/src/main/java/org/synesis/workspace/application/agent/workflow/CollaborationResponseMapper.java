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

    /**
     * Appends the fields shared by workspace and MCP coordination-request projections.
     *
     * @param fields destination response map
     * @param request typed coordination request
     */
    public static void appendRequestIdentityFields(Map<String, Object> fields, CoordinationRequest request) {
        fields.put("requester", request.requester());
        fields.put("target", request.target());
        fields.put("conflictingIntentId", request.conflictingIntentId().toString());
        fields.put("reviewedIntentId", request.conflictingIntentId().toString());
        fields.put("reviewedParticipantId", request.target());
        fields.put("reviewerParticipant", request.requester());
    }

    static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", request.requestId().toString());
        map.put("inboxItemId", request.requestId().toString());
        appendRequestIdentityFields(map, request);
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
        appendSnapshotIdentityFields(map, snapshot);
        map.put("workGroupId", snapshot.provenance().workGroupId().toString());
        map.put("participant", snapshot.provenance().participant());
        map.put("reviewRequired", snapshot.reviewRequired());
        store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).ifPresent(state -> map.put("snapshotState", state.name()));
        store.workGroupProjection().reviewValidationForSnapshot(snapshot.snapshotId())
                .ifPresent(decision -> map.put("reviewDecision", Map.of("grantId", decision.grantId().toString(), "snapshotId", decision.snapshotId(), "result", decision.result())));
        return map;
    }

    /**
     * Appends the stable snapshot fields shared by workspace and MCP projections.
     *
     * @param fields destination response map
     * @param snapshot immutable task snapshot
     */
    public static void appendSnapshotIdentityFields(Map<String, Object> fields, TaskSnapshotRecord snapshot) {
        fields.put("taskId", snapshot.taskId().toString());
        fields.put("snapshotId", snapshot.snapshotId());
        fields.put("baseCommit", snapshot.baseCommit());
        fields.put("commitSha", snapshot.commitSha());
        fields.put("changedPaths", snapshot.changedPaths());
        fields.put("summary", snapshot.summary());
        fields.put("createdAtMillis", snapshot.createdAtMillis());
        fields.put("laneId", snapshot.provenance().laneId().toString());
        fields.put("claimEpoch", snapshot.provenance().claimEpoch());
    }

    static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of("kind", selector.kind().name(), "path", selector.value());
    }
}
