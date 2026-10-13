package org.synesis.workspace.application.agent.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractChangeRequest;
import org.synesis.coordination.domain.contract.ContractRecord;
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
        map.put(NextActionKeys.INTENT_ID, intent.intentId().toString());
        map.put(NextActionKeys.PARTICIPANT, intent.participant());
        map.put("provider", intent.provider());
        map.put("goal", intent.goal());
        map.put("acceptance", intent.acceptance());
        map.put(NextActionKeys.SELECTORS, intent.selectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
        map.put("version", intent.version());
        map.put(NextActionKeys.CLAIM_EPOCH, intent.version());
        map.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
        map.put(NextActionKeys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        map.put(NextActionKeys.STATUS, intent.status().name());
        map.put("role", intent.role().wireValue());
        map.put(NextActionKeys.REVIEW_TARGETS, intent.reviewTargetSelectors().stream().map(CollaborationResponseMapper::selectorMap).toList());
        map.put(NextActionKeys.KNOWN_DEPENDENCIES, intent.knownDependencies());
        return map;
    }

    /**
     * Describes a lane by what it is for and the files it claims: what a caller needs to tell
     * whose a file is, without an identifier to copy.
     *
     * @param intent the lane's intent
     * @return {@code goal} and {@code claims}
     */
    public static Map<String, Object> laneMap(WorkIntent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goal", intent.goal());
        map.put("claims", intent.selectors().stream().map(ResourceSelector::value).toList());
        return map;
    }

    /**
     * Lists the open lanes of every participant but the caller.
     *
     * @param collaboration collaboration projection
     * @param participant   the caller, whose own lane is left out
     * @return one {@link #laneMap} per lane
     */
    public static List<Map<String, Object>> otherLanes(CollaborationProjection collaboration, String participant) {
        return collaboration.activeIntents().stream().filter(intent -> !intent.participant().equals(participant)).map(CollaborationResponseMapper::laneMap).toList();
    }

    /**
     * Shows a public contract: its files, its revision and what its owner promises.
     *
     * @param contract the contract at its current revision
     * @return JSON-safe contract fields
     */
    public static Map<String, Object> contractMap(ContractRecord contract) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractId", contract.contractId().toString());
        map.put("revision", contract.revision());
        map.put("state", contract.state().name());
        // A directory is shown with the trailing slash it is named by. Shown bare, a contract
        // published again as it was read gained a file of that name and a revision nobody asked for.
        map.put("paths", contract.paths().stream().map(path -> path.kind() == ResourceSelector.Kind.PATH_SUBTREE ? path.value() + "/" : path.value()).toList());
        map.put("contract", termsMap(contract.terms()));
        return map;
    }

    /**
     * Shows the eleven fields of a contract under the names an agent sends them by.
     *
     * @param terms what the owner promises
     * @return JSON-safe contract terms
     */
    public static Map<String, Object> termsMap(CapabilityContract terms) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("purpose", terms.purpose());
        map.put("inputs", terms.inputs());
        map.put("output", terms.output());
        map.put("requiredBehavior", terms.requiredBehavior());
        map.put("errorSemantics", terms.errorSemantics());
        map.put("sideEffects", terms.sideEffects());
        map.put("invariants", terms.invariants());
        map.put("compatibility", terms.compatibility());
        map.put("performance", terms.performance());
        map.put("concurrency", terms.concurrency());
        map.put("acceptanceTests", terms.acceptanceTests());
        return map;
    }

    /**
     * Shows one change request with how it stands.
     *
     * @param request the change request
     * @return JSON-safe change request fields
     */
    public static Map<String, Object> changeRequestMap(ContractChangeRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("changeRequest", request.id().toString());
        map.put("path", request.path().value());
        map.put("need", request.need());
        map.put("state", request.state().name());
        if (request.answeredByRevision() > 0) {
            map.put("answeredByRevision", request.answeredByRevision());
        }
        if (!request.reason().isEmpty()) {
            map.put("reason", request.reason());
        }
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
        map.put(NextActionKeys.STATE, participant.state().name());
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
        fields.put(NextActionKeys.REVIEWED_INTENT_ID, request.conflictingIntentId().toString());
        fields.put(NextActionKeys.REVIEWED_PARTICIPANT_ID, request.target());
        fields.put(NextActionKeys.REVIEWER_PARTICIPANT, request.requester());
    }

    static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(NextActionKeys.REQUEST_ID, request.requestId().toString());
        map.put(NextActionKeys.INBOX_ITEM_ID, request.requestId().toString());
        appendRequestIdentityFields(map, request);
        map.put(NextActionKeys.KIND, request.kind().name());
        map.put("proposal", request.proposal());
        if (request.snapshotId() != null) {
            map.put(NextActionKeys.SNAPSHOT_ID, request.snapshotId());
        }
        map.put(NextActionKeys.STATUS, request.status().name());
        return map;
    }

    static Map<String, Object> workGroupMap(WorkGroup group) {
        return Map.of(NextActionKeys.WORK_GROUP_ID,
                group.workGroupId().toString(),
                "projectId",
                group.projectId().toString(),
                "goal",
                group.goal(),
                "acceptance",
                group.acceptance(),
                "version",
                group.version(),
                NextActionKeys.STATUS,
                group.status().name(),
                "reviewPolicy",
                Map.of("mandatory", group.reviewPolicy().mandatory(), "independenceRequired", group.reviewPolicy().independenceRequired()));
    }

    static Map<String, Object> laneGrantMap(LaneGrant grant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(NextActionKeys.GRANT_ID, grant.grantId().toString());
        map.put(NextActionKeys.WORK_GROUP_ID, grant.workGroupId().toString());
        map.put("targetIntentId", grant.targetIntentId().toString());
        map.put(NextActionKeys.REVIEWED_INTENT_ID, grant.targetIntentId().toString());
        map.put(NextActionKeys.TARGET_PARTICIPANT, grant.targetParticipant());
        map.put(NextActionKeys.REVIEWER_PARTICIPANT, grant.targetParticipant());
        map.put(NextActionKeys.CLAIM_EPOCH, grant.claimEpoch());
        map.put("singleUse", grant.singleUse());
        if (grant.snapshotId() != null) {
            map.put(NextActionKeys.SNAPSHOT_ID, grant.snapshotId());
        }
        return map;
    }

    static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot, PredictionEventStore store) {
        return snapshotMap(snapshot, store, false);
    }

    /**
     * Projects a refused snapshot for the lane that owes its correction, with what the project's
     * validation command printed. Every other view of a snapshot leaves the output out: a view of
     * the whole collaboration lists every snapshot, and carried up to 8 KiB for each refused one
     * on every call of every lane.
     *
     * @param snapshot the refused snapshot
     * @param store    current project projection
     * @return the snapshot as its producer is shown it
     */
    static Map<String, Object> rejectedSnapshotMap(TaskSnapshotRecord snapshot, PredictionEventStore store) {
        return snapshotMap(snapshot, store, true);
    }

    private static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot, PredictionEventStore store, boolean withValidationOutput) {
        Map<String, Object> map = new LinkedHashMap<>();
        appendSnapshotIdentityFields(map, snapshot);
        map.put(NextActionKeys.WORK_GROUP_ID, snapshot.provenance().workGroupId().toString());
        map.put(NextActionKeys.PARTICIPANT, snapshot.provenance().participant());
        map.put(NextActionKeys.REVIEW_REQUIRED, snapshot.reviewRequired());
        store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).ifPresent(state -> map.put(NextActionKeys.SNAPSHOT_STATE, state.name()));
        store.workGroupProjection().reviewValidationForSnapshot(snapshot.snapshotId()).ifPresent(decision -> {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put(NextActionKeys.GRANT_ID, decision.grantId().toString());
            shown.put(NextActionKeys.SNAPSHOT_ID, decision.snapshotId());
            shown.put("result", decision.result());
            // The reviewer's explanation is the correction the implementer has to make. It was
            // recorded and never shown.
            if (decision.reason() != null && !decision.reason().isBlank()) {
                shown.put("reason", decision.reason());
            }
            map.put(NextActionKeys.REVIEW_DECISION, shown);
        });
        store.taskCompletionProjection().validationRefusal(snapshot.snapshotId()).ifPresent(refusal -> {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("outcome", refusal.outcome());
            shown.put("exitCode", refusal.exitCode());
            if (withValidationOutput) {
                // What the command printed is the correction the lane has to make. Only whoever
                // pumped the attempt was shown it.
                shown.put("output", refusal.output());
            }
            // The lane is told what was run and where, so that it can run the same thing itself.
            shown.put("command", refusal.command());
            shown.put("workingDirectory", refusal.workingDirectory());
            shown.put("attemptId", refusal.attemptId());
            shown.put("candidateCommit", refusal.candidateCommit());
            // Counts the repair now owed with the refusals still open to the lane. After the last
            // one a further failure refuses nobody and goes to the operator, so the lane is told
            // when this is its last.
            shown.put("repairsLeft", 1 + store.taskCompletionProjection().validationRepairsLeft(snapshot.taskId(), snapshot.provenance().authorityLineageId()));
            map.put("validationFailure", shown);
        });
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
        fields.put(NextActionKeys.SNAPSHOT_ID, snapshot.snapshotId());
        fields.put("baseCommit", snapshot.baseCommit());
        fields.put("commitSha", snapshot.commitSha());
        fields.put("changedPaths", snapshot.changedPaths());
        fields.put(NextActionKeys.SUMMARY, snapshot.summary());
        fields.put("createdAtMillis", snapshot.createdAtMillis());
        fields.put(NextActionKeys.LANE_ID, snapshot.provenance().laneId().toString());
        fields.put(NextActionKeys.CLAIM_EPOCH, snapshot.provenance().claimEpoch());
    }

    static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of(NextActionKeys.KIND, selector.kind().name(), "path", selector.value());
    }
}
