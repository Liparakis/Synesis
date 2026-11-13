package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * The agent side of a re-merge review (ADR-0098 step 5, designer item 14): a re-merge snapshot is in no task's history,
 * so only its grant offers it, and a reviewer holding no lane is still shown that grant.
 */
final class RemergeReviewGrantTest {

    private static final String ORIGINAL = "snap_original";
    private static final String REMERGE = "snap_remerge";
    private static final String REVIEWER = "agt-reviewer";

    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID laneId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private NodeIdentity identity;
    private WorkIntent intent;
    private PredictionEventStore store;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        identity = NodeIdentity.generate();
        intent = new WorkIntent(laneId,
                projectId,
                "agt-owner",
                "codex",
                taskId,
                "implement",
                "acceptance",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(laneId),
                WorkIntent.Status.ANNOUNCED);
        store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        store = new PredictionEventStore(temp, projectId);

        TaskSnapshotPayload original = snapshot(ORIGINAL, false);
        append(PredictionEventType.TASK_SNAPSHOT_CREATED, original.encode());
        append(PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                new IntegrationAttemptPayload("att_original", projectId, List.of(ORIGINAL), "base", "integration",
                        "started", "").encode());
        append(PredictionEventType.TASK_INTEGRATED, original.encode());
        append(PredictionEventType.REMERGE_SNAPSHOT_CREATED,
                new RemergeSnapshotPayload("remerge-1", ORIGINAL, "other-head", "own-head", "result",
                        snapshot(REMERGE, true)).encode());
    }

    private TaskSnapshotPayload snapshot(String snapshotId, boolean reviewRequired) {
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                laneId,
                intent.authorityLineageId(),
                "agt-owner",
                "session-owner",
                1,
                List.of(),
                List.of(),
                List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "artifacts-" + snapshotId);
        return new TaskSnapshotPayload(taskId,
                snapshotId,
                "node",
                "supervisor",
                "worker",
                "session-owner",
                "base",
                "commit-" + snapshotId,
                List.of("src/a.py"),
                List.of(),
                snapshotId,
                provenance,
                reviewRequired);
    }

    private void append(PredictionEventType type, byte[] payload) throws Exception {
        store.append(UUID.randomUUID(), type, identity.nodeId(), payload, identity);
    }

    private LaneGrant issue(String participant, String snapshotId) throws Exception {
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, participant, 1, true, snapshotId);
        append(PredictionEventType.LANE_GRANT_ISSUED, CollaborationCodec.encodeLaneGrant(grant));
        return grant;
    }

    @SuppressWarnings("unchecked")
    private static String snapshotIdOf(Map<String, Object> action) {
        return (String) ((Map<String, Object>) action.get("nextProtocolPayload")).get("snapshotId");
    }

    @Test
    void aLaneLessOriginalReviewerIsOfferedTheReMergeSnapshotOnItsAvailableGrant() throws Exception {
        LaneGrant grant = issue(REVIEWER, REMERGE);

        List<Map<String, Object>> actions = AgentReviewActionProjection.reviewActions(store, REVIEWER, null);

        assertEquals(1, actions.size(), actions.toString());
        Map<String, Object> action = actions.getFirst();
        assertEquals("GRANT_AVAILABLE", action.get("state"));
        assertEquals(REMERGE, snapshotIdOf(action));
        assertEquals(grant.grantId().toString(), CollaborationResponseMapper.laneGrantMap(grant).get("grantId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> shown = (Map<String, Object>) action.get("snapshot");
        assertEquals(REMERGE, shown.get("snapshotId"));
        // The re-merge snapshot is in no task's history, so it is no ordinary review candidate.
        assertEquals(List.of(), CollaborationResponseMapper.openReviewSnapshots(store, List.of(REVIEWER)));
    }

    @Test
    void remergeReviewsGrantedNamesTheSnapshotUntilTheGrantIsConsumed() throws Exception {
        LaneGrant grant = issue(REVIEWER, REMERGE);

        assertEquals(List.of(REMERGE), CollaborationResponseMapper.remergeReviewsGranted(store, List.of(REVIEWER)));
        assertEquals(List.of(grant), AgentReviewActionProjection.remergeGrants(store, List.of(REVIEWER)));
        assertEquals(Set.of(groupId), AgentReviewActionProjection.completedParticipantWorkGroups(store, REVIEWER));

        append(PredictionEventType.LANE_GRANT_CONSUMED, CollaborationCodec.encodeLaneGrant(grant));

        assertEquals(List.of(), CollaborationResponseMapper.remergeReviewsGranted(store, List.of(REVIEWER)));
        assertEquals(List.of(), AgentReviewActionProjection.remergeGrants(store, List.of(REVIEWER)));
        // Consumed with no verdict, the review is still owed, so its work group stays the reviewer's to finish: the
        // session's open authority lists it as PENDING_REVIEW, and the lane's answer must show the review (aaac272b).
        assertEquals(Set.of(groupId), AgentReviewActionProjection.completedParticipantWorkGroups(store, REVIEWER));

        // Consumed, the grant is no longer a join but the verdict still owed.
        List<Map<String, Object>> owed = AgentReviewActionProjection.reviewActions(store, REVIEWER, null);
        assertEquals(1, owed.size(), owed.toString());
        assertEquals("VALIDATION_REQUIRED", owed.getFirst().get("state"));
        assertEquals(REMERGE, snapshotIdOf(owed.getFirst()));
        // ADR-0098 step 5 item 60 (s18a-run1): the reviewer is told what a re-merge review judges, not left to the whole goal.
        String instruction = String.valueOf(((Map<?, ?>) owed.getFirst().get("reviewAccess")).get("instruction"));
        assertTrue(instruction.contains("Do not reject it for parts of the project goal that no change has delivered yet"), instruction);

        append(PredictionEventType.REVIEW_VALIDATION_RECORDED,
                new ReviewValidationPayload(grant.grantId(), groupId, laneId, REVIEWER, 1, taskId, REMERGE, "ACCEPTED",
                        "", "agt-owner").encode());
        assertEquals(List.of(), AgentReviewActionProjection.reviewActions(store, REVIEWER, null));
    }

    @Test
    void aGrantToAnotherParticipantOrWithoutAReMergeSnapshotIsNotShown() throws Exception {
        issue("agt-other", REMERGE);
        issue(REVIEWER, ORIGINAL);

        assertEquals(List.of(), CollaborationResponseMapper.remergeReviewsGranted(store, List.of(REVIEWER)));
        assertEquals(List.of(), AgentReviewActionProjection.remergeGrants(store, List.of(REVIEWER)));
        assertEquals(List.of(REMERGE), CollaborationResponseMapper.remergeReviewsGranted(store, List.of("agt-other")));
        assertEquals(List.of(), AgentReviewActionProjection.reviewActions(store, "agt-third", null));
        assertEquals(Set.of(groupId), AgentReviewActionProjection.completedParticipantWorkGroups(store, "agt-other"));
        assertEquals(Set.of(), AgentReviewActionProjection.completedParticipantWorkGroups(store, "agt-third"));
    }
}
