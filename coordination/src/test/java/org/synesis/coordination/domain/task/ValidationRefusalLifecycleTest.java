package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.RecoveryEvidence;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * A snapshot the project's validation command refused at integration: it never integrates, its lane is reopened at the
 * next version, and a stale refusal is not recorded.
 */
final class ValidationRefusalLifecycleTest {

    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID laneId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final NodeIdentity identity;
    private final WorkIntent intent = new WorkIntent(laneId, projectId, "agt-owner", "codex", taskId, "implement",
            "acceptance", "base", List.of(ResourceSelector.pathExact("src/a.py")), 1, groupId,
            WorkIntent.defaultAuthorityLineage(laneId), WorkIntent.Status.ANNOUNCED);

    ValidationRefusalLifecycleTest() throws Exception {
        identity = NodeIdentity.generate();
    }

    private PredictionEventStore announced(Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, projectId);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        return new PredictionEventStore(temp, projectId);
    }

    private TaskSnapshotPayload snapshot(String snapshotId, long epoch) {
        return snapshot(snapshotId, epoch, false);
    }

    private TaskSnapshotPayload snapshot(String snapshotId, long epoch, boolean reviewRequired) {
        SnapshotProvenance provenance = new SnapshotProvenance(groupId, laneId, intent.authorityLineageId(),
                "agt-owner", "session-owner", epoch, List.of(), List.of(), List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + snapshotId, "integrity-" + snapshotId, "artifacts-" + snapshotId);
        return new TaskSnapshotPayload(taskId, snapshotId, "node", "supervisor", "worker", "session-owner", "base",
                "commit-" + snapshotId, List.of("src/a.py"), List.of(), snapshotId, provenance, reviewRequired);
    }

    private void publish(PredictionEventStore store, String snapshotId, long epoch) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                snapshot(snapshotId, epoch).encode(), identity);
    }

    private IntegrationAttemptPayload attempt(String attemptId, String snapshotId, String status, String reason) {
        return new IntegrationAttemptPayload(attemptId, projectId, List.of(snapshotId), "base", "integration", status,
                reason);
    }

    private void attemptStarted(PredictionEventStore store, String attemptId, String snapshotId) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED, identity.nodeId(),
                attempt(attemptId, snapshotId, "started", "").encode(), identity);
    }

    private void attemptFailedPending(PredictionEventStore store, String attemptId, String snapshotId) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(),
                attempt(attemptId, snapshotId, "pending", "VALIDATION_FAILED:non_zero_exit").encode(), identity);
    }

    private void failedAttempt(PredictionEventStore store, String attemptId, String snapshotId) throws Exception {
        attemptStarted(store, attemptId, snapshotId);
        attemptFailedPending(store, attemptId, snapshotId);
    }

    private ValidationRefusalPayload refusal(String attemptId, String snapshotId, long claimEpoch, long intentVersion) {
        return new ValidationRefusalPayload(attemptId, snapshotId, taskId, groupId, intent.authorityLineageId(),
                claimEpoch, laneId, intentVersion, "agt-owner", "integration", "[\"git\"]", ".", "non_zero_exit", 1, "FAIL " + snapshotId);
    }

    private void refuse(PredictionEventStore store, ValidationRefusalPayload payload) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_VALIDATION_REFUSED, identity.nodeId(),
                payload.encode(), identity);
    }

    private PredictionEventStore afterFailedAttempt(Path temp) throws Exception {
        PredictionEventStore store = announced(temp);
        publish(store, "snap_s1", 1);
        failedAttempt(store, "att_1", "snap_s1");
        return store;
    }

    private static TaskCompletionState stateOf(PredictionEventStore store, String snapshotId) {
        return store.taskCompletionProjection().snapshotState(snapshotId).orElseThrow();
    }

    @Test
    void refusedSnapshotNeverIntegratesAndItsLaneIsReopenedAtTheNextVersion(@TempDir Path temp) throws Exception {
        PredictionEventStore store = announced(temp);
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer", 1, true);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        publish(store, "snap_s1", 1);
        attemptStarted(store, "att_1", "snap_s1");
        assertEquals(TaskCompletionState.INTEGRATING, stateOf(store, "snap_s1"));
        attemptFailedPending(store, "att_1", "snap_s1");
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, stateOf(store, "snap_s1"));

        ValidationRefusalPayload refusal = refusal("att_1", "snap_s1", 1, 1);
        refuse(store, refusal);

        assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"));
        assertTrue(store.taskCompletionProjection().eligibleSnapshots().isEmpty());
        assertEquals(refusal, store.taskCompletionProjection().validationRefusal("snap_s1").orElseThrow());
        assertThrows(Exception.class, () -> attemptStarted(store, "att_2", "snap_s1"));
        assertThrows(Exception.class, () -> store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED,
                identity.nodeId(), snapshot("snap_s1", 1).encode(), identity));
        assertThrows(Exception.class, () -> store.append(UUID.randomUUID(),
                PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(grant.grantId(), groupId, laneId, "agt-reviewer", 1, taskId, "snap_s1",
                        "ACCEPTED", "", "agt-owner").encode(), identity));
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"));

        WorkIntent reopened = store.collaborationProjection().intent(laneId).orElseThrow();
        assertEquals(2, reopened.version());
        assertEquals(WorkIntent.Status.ANNOUNCED, reopened.status());
        assertEquals(intent.selectors(), reopened.selectors());
        assertEquals("agt-owner", reopened.participant());
    }

    @Test
    void refusalsAreCountedPerTaskAndLineageAcrossRepublishedSnapshots(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);
        assertEquals(0, store.taskCompletionProjection().validationRefusals(taskId, intent.authorityLineageId()));
        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        assertEquals(1, store.taskCompletionProjection().validationRefusals(taskId, intent.authorityLineageId()));

        publish(store, "snap_s2", 2);
        failedAttempt(store, "att_2", "snap_s2");
        refuse(store, refusal("att_2", "snap_s2", 2, 2));

        assertEquals(2, store.taskCompletionProjection().validationRefusals(taskId, intent.authorityLineageId()));
        assertEquals(0, store.taskCompletionProjection().validationRefusals(taskId, UUID.randomUUID()));
        assertEquals(0, store.taskCompletionProjection().validationRefusals(UUID.randomUUID(), intent.authorityLineageId()));
        assertEquals(3, store.collaborationProjection().intent(laneId).orElseThrow().version());
        assertEquals("FAIL snap_s2", store.taskCompletionProjection().validationRefusal("snap_s2").orElseThrow().output());
        assertTrue(store.taskCompletionProjection().validationRefusal("snap_unknown").isEmpty());
    }

    @Test
    void refusalForAChangedLaneIsRefusedAndLeavesTheProjectionsAsTheyWere(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);
        List<ValidationRefusalPayload> stale = List.of(
                refusal("att_1", "snap_s1", 1, 2),
                new ValidationRefusalPayload("att_1", "snap_s1", taskId, groupId, intent.authorityLineageId(), 1, laneId,
                        1, "agt-other", "integration", "[\"git\"]", ".", "non_zero_exit", 1, "x"),
                new ValidationRefusalPayload("att_1", "snap_s1", taskId, groupId, intent.authorityLineageId(), 1,
                        UUID.randomUUID(), 1, "agt-owner", "integration", "[\"git\"]", ".", "non_zero_exit", 1, "x"),
                new ValidationRefusalPayload("att_1", "snap_s1", UUID.randomUUID(), groupId, intent.authorityLineageId(),
                        1, laneId, 1, "agt-owner", "integration", "[\"git\"]", ".", "non_zero_exit", 1, "x"),
                refusal("att_1", "snap_unknown", 1, 1));

        for (ValidationRefusalPayload payload : stale) {
            assertThrows(Exception.class, () -> refuse(store, payload), payload.toString());
            assertEquals(TaskCompletionState.INTEGRATION_PENDING, stateOf(store, "snap_s1"));
            assertEquals(1, store.collaborationProjection().intent(laneId).orElseThrow().version());
            assertEquals(0, store.taskCompletionProjection().validationRefusals(taskId, intent.authorityLineageId()));
        }
        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"));
    }

    @Test
    void refusalOfAReleasedLaneIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);
        new WorkIntentService(store, identity).release(laneId, "agt-owner");
        PredictionEventStore afterRelease = new PredictionEventStore(temp, projectId);
        assertTrue(afterRelease.collaborationProjection().intent(laneId).isEmpty());

        assertThrows(Exception.class, () -> refuse(afterRelease, refusal("att_1", "snap_s1", 1, 1)));
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, stateOf(afterRelease, "snap_s1"));
    }

    @Test
    void refusalOfASnapshotThatCouldNotIntegrateIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = announced(temp);
        publish(store, "snap_s1", 1);
        attemptStarted(store, "att_1", "snap_s1");
        assertEquals(TaskCompletionState.INTEGRATING, stateOf(store, "snap_s1"));
        assertThrows(Exception.class, () -> refuse(store, refusal("att_1", "snap_s1", 1, 1)),
                "an attempt still running has not given a verdict");

        attemptFailedPending(store, "att_1", "snap_s1");
        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        assertThrows(Exception.class, () -> refuse(store, refusal("att_1", "snap_s1", 1, 2)),
                "a snapshot is refused once");
        assertEquals(1, store.taskCompletionProjection().validationRefusals(taskId, intent.authorityLineageId()));
    }

    @Test
    void aSnapshotAcceptedByReviewThenRefusedByValidationNeedsANewReviewAfterItsRepair(@TempDir Path temp) throws Exception {
        PredictionEventStore store = announced(temp);
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer", 1, true);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                snapshot("snap_s1", 1, true).encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(grant.grantId(), groupId, laneId, "agt-reviewer", 1, taskId, "snap_s1",
                        "ACCEPTED", "", "agt-owner").encode(), identity);
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, stateOf(store, "snap_s1"));
        failedAttempt(store, "att_1", "snap_s1");
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, stateOf(store, "snap_s1"));

        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                snapshot("snap_s2", 2, true).encode(), identity);

        assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"));
        assertEquals(TaskCompletionState.REVIEW_PENDING, stateOf(store, "snap_s2"));
        assertTrue(store.taskCompletionProjection().eligibleSnapshots().isEmpty(), "the repair waits for a review");
        assertEquals(List.of("snap_s2"), store.taskCompletionProjection().pendingReviewSnapshots().stream()
                .map(TaskSnapshotRecord::snapshotId).toList());
    }

    @Test
    void aReviewRejectionRecordedWhileTheSnapshotIsBeingIntegratedSurvivesTheStaleAttemptEnding(@TempDir Path temp) throws Exception {
        PredictionEventStore store = announced(temp);
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer", 1, true);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(grant), identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                snapshot("snap_s1", 1, true).encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(grant.grantId(), groupId, laneId, "agt-reviewer", 1, taskId, "snap_s1",
                        "ACCEPTED", "", "agt-owner").encode(), identity);
        attemptStarted(store, "att_1", "snap_s1");
        assertEquals(TaskCompletionState.INTEGRATING, stateOf(store, "snap_s1"));

        LaneGrant second = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer-2", 1, true);
        store.append(second.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(second), identity);
        store.append(second.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                CollaborationCodec.encodeLaneGrant(second), identity);
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(second.grantId(), groupId, laneId, "agt-reviewer-2", 1, taskId, "snap_s1",
                        "REJECTED", "needs work", "agt-owner").encode(), identity);
        // Two reviewers' different verdicts are a dispute, which counts as rejected (ADR-0100 row 4).
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, stateOf(store, "snap_s1"));
        attemptFailedPending(store, "att_1", "snap_s1");

        assertEquals(TaskCompletionState.REVIEW_DISPUTED, stateOf(store, "snap_s1"));
        assertTrue(store.taskCompletionProjection().eligibleSnapshots().isEmpty());
    }

    @Test
    void replayInANewStoreGivesTheSameStatesAndVersions(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);
        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        publish(store, "snap_s2", 2);

        PredictionEventStore replayed = new PredictionEventStore(temp, projectId);

        assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(replayed, "snap_s1"));
        assertEquals(stateOf(store, "snap_s2"), stateOf(replayed, "snap_s2"));
        assertEquals(2, replayed.collaborationProjection().intent(laneId).orElseThrow().version());
        assertEquals(store.taskCompletionProjection().validationRefusal("snap_s1"),
                replayed.taskCompletionProjection().validationRefusal("snap_s1"));
        assertEquals(1, replayed.taskCompletionProjection().validationRefusals(taskId, intent.authorityLineageId()));
        assertEquals(List.of("snap_s2"), replayed.taskCompletionProjection().eligibleSnapshots().stream()
                .map(TaskSnapshotRecord::snapshotId).toList());
    }

    @Test
    void onlyTheThreeRefusedStatesAreRefused() {
        for (TaskCompletionState state : TaskCompletionState.values()) {
            boolean expected = state == TaskCompletionState.REVIEW_REJECTED
                    || state == TaskCompletionState.REVIEW_DISPUTED || state == TaskCompletionState.VALIDATION_REFUSED;
            assertEquals(expected, state.refused(), state.name());
        }
        assertFalse(TaskCompletionState.INTEGRATION_FAILED.refused());
    }

    private static final ParticipantId OWNER = new ParticipantId("agt-owner");

    private void fence(PredictionEventStore store, Participant.State state) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.PARTICIPANT_SUSPENDED, identity.nodeId(),
                CollaborationCodec.encodeHeartbeat(OWNER), identity);
        if (state == Participant.State.RECOVERY_HELD) {
            store.append(UUID.randomUUID(), PredictionEventType.RECOVERY_SNAPSHOT_HELD, identity.nodeId(),
                    CollaborationCodec.encodeRecovery(new RecoveryEvidence(OWNER, new SnapshotReference("refs/synesis/recovery/owner"))),
                    identity);
        }
        assertEquals(state, store.collaborationProjection().participantState(OWNER).orElseThrow());
    }

    @Test
    void aRefusalKeepsASuspendedOrHeldParticipantFencedWhileTheLaneMovesToItsNextVersion(@TempDir Path temp) throws Exception {
        for (Participant.State fenced : List.of(Participant.State.SUSPENDED, Participant.State.RECOVERY_HELD)) {
            PredictionEventStore store = afterFailedAttempt(Files.createDirectory(temp.resolve(fenced.name())));
            fence(store, fenced);

            refuse(store, refusal("att_1", "snap_s1", 1, 1));

            assertEquals(fenced, store.collaborationProjection().participantState(OWNER).orElseThrow(), fenced.name());
            WorkIntent reopened = store.collaborationProjection().intent(laneId).orElseThrow();
            assertEquals(2, reopened.version(), fenced.name());
            assertEquals(intent.selectors(), reopened.selectors(), fenced.name());
            assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"), fenced.name());
        }
    }

    @Test
    void aRefusalLeavesAnActiveParticipantActive(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);

        refuse(store, refusal("att_1", "snap_s1", 1, 1));

        assertEquals(Participant.State.ACTIVE, store.collaborationProjection().participantState(OWNER).orElseThrow());
    }

    @Test
    void aReviewRejectionStillMakesASuspendedOrHeldParticipantActive(@TempDir Path temp) throws Exception {
        for (Participant.State fenced : List.of(Participant.State.SUSPENDED, Participant.State.RECOVERY_HELD)) {
            PredictionEventStore store = announced(Files.createDirectory(temp.resolve(fenced.name())));
            LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, "agt-reviewer", 1, true);
            store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, identity.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant), identity);
            store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, identity.nodeId(),
                    CollaborationCodec.encodeLaneGrant(grant), identity);
            store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                    snapshot("snap_s1", 1, true).encode(), identity);
            fence(store, fenced);

            store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                    new ReviewValidationPayload(grant.grantId(), groupId, laneId, "agt-reviewer", 1, taskId, "snap_s1",
                            "REJECTED", "needs work", "agt-owner").encode(), identity);

            assertEquals(TaskCompletionState.REVIEW_REJECTED, stateOf(store, "snap_s1"), fenced.name());
            assertEquals(2, store.collaborationProjection().intent(laneId).orElseThrow().version(), fenced.name());
            assertEquals(Participant.State.ACTIVE, store.collaborationProjection().participantState(OWNER).orElseThrow(), fenced.name());
        }
    }

    @Test
    void aRefusedSnapshotKeepsItsStateWhenALaterEventNamesItAgain(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);
        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        IntegrationAttemptPayload naming = attempt("att_1", "snap_s1", "pending", "late");
        PredictionEventType[] later = {PredictionEventType.INTEGRATION_ATTEMPT_FAILED, PredictionEventType.INTEGRATION_BLOCKED,
                PredictionEventType.INTEGRATION_CONFLICTED, PredictionEventType.REPAIR_REQUIRED};

        for (PredictionEventType type : later) {
            try {
                store.append(UUID.randomUUID(), type, identity.nodeId(), naming.encode(), identity);
            } catch (Exception refusedByTheStore) {
                // The store may refuse the event itself; either way the state below must hold.
            }
            assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"), type.name());
            assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(new PredictionEventStore(temp, projectId), "snap_s1"), "replayed after " + type.name());
        }
        IntegrationAttemptPayload failed = attempt("att_1", "snap_s1", "failed", "late");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(), failed.encode(), identity);
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, stateOf(store, "snap_s1"));
        assertTrue(store.taskCompletionProjection().eligibleSnapshots().isEmpty());
    }

    @Test
    void repairsLeftFallAsRefusalsAccumulateForATaskAndLineage(@TempDir Path temp) throws Exception {
        PredictionEventStore store = afterFailedAttempt(temp);
        assertEquals(2, TaskCompletionProjection.VALIDATION_REPAIRS);
        assertEquals(2, store.taskCompletionProjection().validationRepairsLeft(taskId, intent.authorityLineageId()));

        refuse(store, refusal("att_1", "snap_s1", 1, 1));
        assertEquals(1, store.taskCompletionProjection().validationRepairsLeft(taskId, intent.authorityLineageId()));
        publish(store, "snap_s2", 2);
        failedAttempt(store, "att_2", "snap_s2");
        refuse(store, refusal("att_2", "snap_s2", 2, 2));
        assertEquals(0, store.taskCompletionProjection().validationRepairsLeft(taskId, intent.authorityLineageId()));
        publish(store, "snap_s3", 3);
        failedAttempt(store, "att_3", "snap_s3");
        refuse(store, refusal("att_3", "snap_s3", 3, 3));

        assertEquals(0, store.taskCompletionProjection().validationRepairsLeft(taskId, intent.authorityLineageId()), "never below zero");
        assertEquals(2, store.taskCompletionProjection().validationRepairsLeft(taskId, UUID.randomUUID()), "another lineage has its own repairs");
        assertEquals(2, store.taskCompletionProjection().validationRepairsLeft(UUID.randomUUID(), intent.authorityLineageId()), "another task has its own repairs");
    }
}
