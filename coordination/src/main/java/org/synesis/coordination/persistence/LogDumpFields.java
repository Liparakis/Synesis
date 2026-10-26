package org.synesis.coordination.persistence;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.membership.MembershipEntries;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.task.CombinePayload;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.trash.TrashEntries;

/**
 * The few fields of an entry that identify what it is about: the lane, snapshot, grant, combine, member or operation
 * it names, with its refs and verdicts. Decoded with the same codecs the projections use, so a field here is what the
 * projection reads.
 *
 * <p>Allow-list by construction: a field is printed only when this class names it. Keys, invitations and the free
 * text of a message are never named, so a secret that a payload carries is not printed whatever the payload holds.</p>
 */
final class LogDumpFields {

    /** Longest value printed; a longer one ends in {@code ...}. */
    private static final int MAX_VALUE = 120;

    private LogDumpFields() {
    }

    /**
     * Returns the fields of one entry, in the order they print.
     *
     * @param event a decoded entry
     * @return the fields; empty when the type has none or its payload cannot be read
     */
    static Map<String, String> of(PredictionEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            collect(event, fields);
        } catch (IOException | RuntimeException unreadable) {
            fields.clear();
            fields.put("payload", "UNREADABLE");
        }
        return fields;
    }

    private static void collect(PredictionEvent event, Map<String, String> fields) throws IOException {
        byte[] payload = event.payload();
        switch (event.type()) {
            case WORK_INTENT_ANNOUNCED -> {
                var lane = CollaborationCodec.decodeIntent(payload);
                put(fields, "lane", lane.intentId());
                put(fields, "participant", lane.participant());
                put(fields, "role", lane.role());
                put(fields, "group", lane.workGroupId());
                put(fields, "task", lane.taskId());
                put(fields, "version", lane.version());
            }
            case WORK_INTENT_RELEASED -> put(fields, "lane", CollaborationCodec.decodeRelease(payload));
            case WORK_GROUP_CREATED, WORK_GROUP_STATUS_CHANGED -> {
                var group = CollaborationCodec.decodeWorkGroup(payload);
                put(fields, "group", group.workGroupId());
                put(fields, "status", group.status());
                put(fields, "version", group.version());
            }
            case LANE_GRANT_ISSUED, LANE_GRANT_CONSUMED, LANE_REVOKED -> {
                var grant = CollaborationCodec.decodeLaneGrant(payload);
                put(fields, "grant", grant.grantId());
                put(fields, "group", grant.workGroupId());
                put(fields, "lane", grant.targetIntentId());
                put(fields, "target", grant.targetParticipant());
                put(fields, "snapshot", grant.snapshotId());
            }
            case LANE_CONTINUATION_ACCEPTED -> {
                var continuation = CollaborationCodec.decodeContinuation(payload);
                put(fields, "grant", continuation.grant().value());
                put(fields, "lane", continuation.sourceIntent().value());
                put(fields, "successor", continuation.targetIntent().intentId());
            }
            case REVIEW_VALIDATION_RECORDED -> {
                var review = ReviewValidationPayload.decode(payload);
                put(fields, "grant", review.grantId());
                put(fields, "snapshot", review.snapshotId());
                put(fields, "lane", review.targetIntentId());
                put(fields, "verdict", review.result());
                put(fields, "reviewer", review.sourceParticipant());
            }
            case TASK_SNAPSHOT_CREATED -> {
                var snapshot = TaskSnapshotPayload.decode(payload);
                put(fields, "snapshot", snapshot.snapshotId());
                put(fields, "task", snapshot.taskId());
                put(fields, "commit", snapshot.commitSha());
                put(fields, "base", snapshot.baseCommit());
                put(fields, "review", snapshot.reviewRequired());
            }
            case REMERGE_SNAPSHOT_CREATED -> {
                var remerge = RemergeSnapshotPayload.decode(payload);
                put(fields, "remerge", remerge.remergeId());
                put(fields, "original", remerge.originalSnapshotId());
                put(fields, "snapshot", remerge.snapshot().snapshotId());
                put(fields, "own", remerge.ownHead());
                put(fields, "other", remerge.otherHead());
                put(fields, "result", remerge.result());
            }
            case COMBINE_REQUIRED -> {
                var combine = CombinePayload.decode(payload);
                put(fields, "combine", combine.combineId());
                put(fields, "remerge", combine.remergeId());
                put(fields, "base", combine.base());
                put(fields, "own", combine.ownHead());
                put(fields, "other", combine.otherHead());
                put(fields, "snapshots", String.join(",", combine.snapshotIds()));
            }
            case INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED, INTEGRATION_CONFLICTED,
                 INTEGRATION_BLOCKED, INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED -> {
                var attempt = IntegrationAttemptPayload.decode(payload);
                put(fields, "attempt", attempt.attemptId());
                put(fields, "snapshots", String.join(",", attempt.taskSnapshotIds()));
                put(fields, "head", attempt.expectedControlHead());
                put(fields, "commit", attempt.integrationCommitSha());
                put(fields, "status", attempt.status());
                put(fields, "failure", attempt.failureReason());
            }
            case OPERATOR_REQUEST_RAISED -> {
                OperatorRequest request = OperatorRequest.decodeRaised(payload);
                put(fields, "request", request.id());
                put(fields, "condition", request.condition());
            }
            case COORDINATION_REQUESTED -> {
                var request = CollaborationCodec.decodeRequest(payload);
                put(fields, "request", request.requestId());
                put(fields, "kind", request.kind());
                put(fields, "requester", request.requester());
                put(fields, "target", request.target());
                put(fields, "snapshot", request.snapshotId());
            }
            case JOIN_REQUESTED -> {
                var join = MembershipEntries.JoinRequested.decode(payload);
                put(fields, "request", join.requestId());
                put(fields, "member", join.joinerNodeId());
            }
            case JOIN_ANSWERED -> {
                var join = MembershipEntries.JoinAnswered.decode(payload);
                put(fields, "request", join.requestId());
                put(fields, "member", join.joinerNodeId());
                put(fields, "accepted", join.accepted());
                put(fields, "revision", join.revision());
            }
            case JOIN_POLICY_SET -> {
                var policy = MembershipEntries.JoinPolicySet.decode(payload);
                put(fields, "policy", policy.policy());
                put(fields, "revision", policy.revision());
            }
            case MEMBER_REMOVED -> {
                var removed = MembershipEntries.MemberRemoved.decode(payload);
                put(fields, "member", removed.nodeId());
                put(fields, "head", removed.head());
                put(fields, "revision", removed.revision());
            }
            case OWNER_CHANGED -> put(fields, "revision", MembershipEntries.OwnerChanged.decode(payload).revision());
            case WORK_TRASHED -> {
                var trashed = TrashEntries.WorkTrashed.decode(payload);
                put(fields, "lane", trashed.laneId());
                put(fields, "epoch", trashed.epoch());
                put(fields, "group", trashed.workGroupId());
                put(fields, "task", trashed.taskId());
            }
            case TRASH_RESTORED -> {
                var restored = TrashEntries.TrashRestored.decode(payload);
                put(fields, "lane", restored.laneId());
                put(fields, "epoch", restored.epoch());
            }
            case TRASH_EMPTIED -> {
                var emptied = TrashEntries.TrashEmptied.decode(payload);
                put(fields, "lane", emptied.laneId());
                put(fields, "epoch", emptied.epoch());
            }
            default -> {
                // PROJECT_SHARED and MEMBER_CARD carry keys and an invitation, which are never printed. The
                // other types are named by their type alone.
            }
        }
    }

    /**
     * Makes a value fit on one line: control characters and spaces become {@code _}, and a long value is cut.
     *
     * @param value any value
     * @return the text, or empty when there is nothing to print
     */
    static String clean(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        StringBuilder line = new StringBuilder(Math.min(text.length(), MAX_VALUE + 3));
        for (int index = 0; index < text.length() && line.length() < MAX_VALUE; index++) {
            char c = text.charAt(index);
            line.append(c <= ' ' || Character.isISOControl(c) ? '_' : c);
        }
        return text.length() > MAX_VALUE ? line.append("...").toString() : line.toString();
    }

    static String list(List<?> values) {
        StringBuilder text = new StringBuilder();
        for (Object value : values) {
            text.append(text.isEmpty() ? "" : ",").append(clean(value));
        }
        return text.toString();
    }

    private static void put(Map<String, String> fields, String key, Object value) {
        String text = clean(value);
        if (!text.isEmpty()) {
            fields.put(key, text);
        }
    }
}
