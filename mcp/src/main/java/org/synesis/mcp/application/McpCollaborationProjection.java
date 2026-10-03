package org.synesis.mcp.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.collaboration.CollaborationSnapshot;

/**
 * Converts typed collaboration projections into JSON-compatible MCP result objects.
 *
 * <p>JSON objects remain maps only at the wire boundary; source values enter each mapper as their
 * domain type so protocol serialization does not perform unchecked domain casts.
 */
final class McpCollaborationProjection {

    /**
     * Utility class; projections are exposed as static mappers.
     */
    private McpCollaborationProjection() {
    }

    /**
     * Converts a typed contract record into the JSON object used by MCP discovery results.
     *
     * @param contract authoritative contract projection
     * @return JSON-safe contract fields; {@code supersedes} is {@code null} for a first revision
     */
    static Map<String, Object> contractMap(ContractRecord contract) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractId", contract.contractId().toString());
        map.put("projectId", contract.projectId().toString());
        map.put("revision", contract.revision());
        map.put("owner", contract.owner());
        map.put("contentHash", contract.contentHash());
        map.put("body", contract.body());
        map.put("status", contract.status().name());
        map.put("supersedes", contract.supersedes() == null ? null : contract.supersedes().toString());
        map.put("selectorRefs", contract.selectorRefs());
        return map;
    }

    /**
     * Converts a typed contract dependency into its JSON representation.
     *
     * @param dependency authoritative dependency projection
     * @return JSON-safe dependency fields
     */
    static Map<String, Object> dependencyMap(ContractDependency dependency) {
        return Map.of("intentId",
                dependency.intentId().toString(),
                "participant",
                dependency.participant(),
                "contractId",
                dependency.contractId().toString(),
                "revision",
                dependency.revision(),
                "state",
                dependency.state().name());
    }

    /**
     * Builds the complete JSON-safe collaboration status payload from its typed snapshot.
     *
     * @param snapshot typed collaboration state returned by the application service
     * @return MCP-ready status fields
     */
    static Map<String, Object> collaborationStatusMap(CollaborationSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intents", snapshot.intents().stream().map(McpCollaborationProjection::intentMap).toList());
        result.put("requests", snapshot.requests().stream().map(McpCollaborationProjection::requestMap).toList());
        result.put("participants", snapshot.participants().stream().map(McpCollaborationProjection::participantMap).toList());
        result.put("groups", snapshot.groups().stream().map(McpCollaborationProjection::workGroupMap).toList());
        result.put("grants", snapshot.grants().stream().map(McpCollaborationProjection::laneGrantMap).toList());
        result.put("snapshots", snapshot.snapshots().stream().map(McpCollaborationProjection::snapshotMap).toList());
        return result;
    }

    /**
     * Converts one work group into the stable MCP response shape.
     *
     * @param group typed work-group state
     * @return JSON-safe work-group fields
     */
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
                group.status().name());
    }

    /**
     * Converts one lane grant while retaining the response aliases expected by clients:
     * {@code reviewedIntentId} repeats {@code targetIntentId} and {@code reviewerParticipant} repeats
     * {@code targetParticipant}.
     *
     * @param grant typed lane-grant state
     * @return JSON-safe grant fields
     */
    static Map<String, Object> laneGrantMap(LaneGrant grant) {
        return Map.of("grantId",
                grant.grantId().toString(),
                "workGroupId",
                grant.workGroupId().toString(),
                "targetIntentId",
                grant.targetIntentId().toString(),
                "reviewedIntentId",
                grant.targetIntentId().toString(),
                "targetParticipant",
                grant.targetParticipant(),
                "reviewerParticipant",
                grant.targetParticipant(),
                "claimEpoch",
                grant.claimEpoch(),
                "singleUse",
                grant.singleUse());
    }

    /**
     * Converts one immutable snapshot into the review response shape.
     *
     * @param snapshot typed task snapshot
     * @return JSON-safe snapshot fields
     */
    static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        CollaborationResponseMapper.appendSnapshotIdentityFields(result, snapshot);
        return result;
    }

    /**
     * Converts one work intent into its discovery response shape.
     *
     * @param intent typed work-intent state
     * @return JSON-safe intent fields
     */
    static Map<String, Object> intentMap(WorkIntent intent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intentId", intent.intentId().toString());
        result.put("projectId", intent.projectId().toString());
        result.put("participant", intent.participant());
        result.put("provider", intent.provider());
        result.put("taskId", intent.taskId().toString());
        result.put("goal", intent.goal());
        result.put("acceptance", intent.acceptance());
        result.put("baseCommit", intent.baseCommit());
        result.put("selectors", selectorMaps(intent.selectors()));
        result.put("version", intent.version());
        result.put("workGroupId", intent.workGroupId().toString());
        result.put("authorityLineageId", intent.authorityLineageId().toString());
        result.put("status", intent.status().name());
        result.put("role", intent.role().wireValue());
        result.put("reviewTargets", selectorMaps(intent.reviewTargetSelectors()));
        result.put("knownDependencies", intent.knownDependencies());
        return result;
    }

    /**
     * Converts one participant into its discovery response shape.
     *
     * @param participant typed participant state
     * @return JSON-safe participant fields
     */
    static Map<String, Object> participantMap(Participant participant) {
        Map<String, Object> result = CollaborationResponseMapper.participantFields(participant);
        result.put("claims", selectorMaps(participant.claims()));
        result.put("recoveryHeld", participant.state() == Participant.State.RECOVERY_HELD);
        return result;
    }

    /**
     * Converts one coordination request into its discovery response shape. The {@code reviewed*} and
     * {@code reviewerParticipant} fields are aliases of {@code conflictingIntentId}, {@code target}
     * and {@code requester} for review-oriented clients.
     *
     * @param request typed coordination request
     * @return JSON-safe request fields
     */
    static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", request.requestId().toString());
        result.put("projectId", request.projectId().toString());
        CollaborationResponseMapper.appendRequestIdentityFields(result, request);
        result.put("kind", request.kind().name());
        result.put("proposal", request.proposal());
        result.put("status", request.status().name());
        return result;
    }

    /**
     * Converts one resource selector to the canonical kind-and-path representation.
     *
     * @param selector typed resource selector
     * @return JSON-safe selector fields
     */
    static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of("kind", selector.kind().name(), "path", selector.value());
    }

    /**
     * Converts a selector collection, preserving its order.
     *
     * @param selectors typed resource selectors
     * @return JSON-safe selector fields, one entry per selector
     */
    private static List<Map<String, Object>> selectorMaps(Collection<? extends ResourceSelector> selectors) {
        return selectors.stream().map(McpCollaborationProjection::selectorMap).toList();
    }
}