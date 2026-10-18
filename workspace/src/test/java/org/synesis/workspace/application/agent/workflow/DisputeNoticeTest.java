package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * What the parties to a review dispute are told (ADR-0098 step 7, designer items 6 and 7). Two reviewers' verdicts
 * meet on one log only by arrival, so the second reviewer writes on its own log and the entry is copied here.
 */
final class DisputeNoticeTest {

    private static final String AUTHOR = "agt-author";
    private static final String ONE = "agt-reviewer-1";
    private static final String TWO = "agt-reviewer-2";
    private static final String SNAPSHOT = "snap_a";

    @TempDir
    Path temp;

    private final UUID projectId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID laneId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private NodeIdentity identity;
    private PredictionEventStore store;
    private LaneGrant grantOne;
    private LaneGrant grantTwo;
    private NodeIdentity second;
    private PredictionEventStore theirs;

    @BeforeEach
    void setUp() throws Exception {
        identity = NodeIdentity.generate();
        store = new PredictionEventStore(temp.resolve("first"), projectId);
        WorkIntent intent = new WorkIntent(laneId, projectId, AUTHOR, "codex", taskId, "implement", "acceptance", "base",
                List.of(ResourceSelector.pathExact("src/a.txt")), 1, groupId, WorkIntent.defaultAuthorityLineage(laneId),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        SnapshotProvenance provenance = new SnapshotProvenance(groupId, laneId, intent.authorityLineageId(), AUTHOR, "session-author", 1,
                List.of(), List.of(), List.of("PATH:src/a.txt"), "refs/synesis/snapshots/" + SNAPSHOT, "integrity", "artifacts");
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                new TaskSnapshotPayload(taskId, SNAPSHOT, "node", "supervisor", "worker", "session-author", "base", "commit-" + SNAPSHOT,
                        List.of("src/a.txt"), List.of(), "work", provenance, true).encode(), identity);
        grantOne = grant(ONE);
        grantTwo = grant(TWO);
    }

    private LaneGrant grant(String reviewer) throws Exception {
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), groupId, laneId, reviewer, 1, true);
        for (PredictionEventType type : List.of(PredictionEventType.LANE_GRANT_ISSUED, PredictionEventType.LANE_GRANT_CONSUMED)) {
            store.append(grant.grantId(), type, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
        }
        return grant;
    }

    private ReviewValidationPayload verdict(LaneGrant grant, String reviewer, String result) {
        return new ReviewValidationPayload(grant.grantId(), groupId, laneId, reviewer, 1, taskId, SNAPSHOT, result,
                "REJECTED".equals(result) ? "not good enough" : null, AUTHOR);
    }

    private void record(LaneGrant grant, String reviewer, String result) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(), verdict(grant, reviewer, result).encode(),
                identity);
    }

    /** Reviewer one accepts here; reviewer two rejects on its own log, which is copied here. */
    private void dispute() throws Exception {
        second = NodeIdentity.generate();
        theirs = new PredictionEventStore(temp.resolve("second"), projectId);
        theirs.placeCopies(identity.nodeId(), store.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        UUID reviewerLane = UUID.randomUUID();
        WorkIntent introduced = new WorkIntent(reviewerLane, projectId, TWO, "codex", UUID.randomUUID(), "review", "acceptance", "base",
                List.of(ResourceSelector.pathExact("src/other.txt")), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(reviewerLane),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(theirs, second).announce(introduced).acquired());
        theirs.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, second.nodeId(), verdict(grantTwo, TWO, "REJECTED").encode(),
                second);
        record(grantOne, ONE, "ACCEPTED");
        store.placeCopies(second.nodeId(), theirs.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, store.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void aReviewerIsToldWhoDisagreesAndHowToReplaceItsVerdictOnItsOwnGrant() throws Exception {
        dispute();

        List<Map<String, Object>> notices = DisputeNotice.forParticipant(store, TWO);

        assertEquals(1, notices.size(), notices.toString());
        Map<String, Object> notice = notices.getFirst();
        assertEquals(SNAPSHOT, notice.get("snapshotId"));
        assertEquals(AUTHOR, notice.get("author"));
        assertEquals(Map.of(ONE, "ACCEPTED", TWO, "REJECTED"), notice.get("verdicts"));
        assertEquals("REJECTED", notice.get("yourVerdict"));
        assertEquals(List.of(ONE, AUTHOR), notice.get("messageTo"));
        Map<String, Object> newVerdict = map(notice.get("newVerdict"));
        assertEquals("respond_coordination", newVerdict.get("tool"));
        assertEquals("review_validation", newVerdict.get("kind"));
        Map<String, Object> payload = map(newVerdict.get("payload"));
        assertEquals(grantTwo.grantId().toString(), payload.get("grantId"));
        assertEquals(SNAPSHOT, payload.get("snapshotId"));
        assertEquals(laneId.toString(), payload.get("intentId"));
        assertEquals(1L, ((Number) payload.get("claimEpoch")).longValue());
        assertTrue(notice.get("instruction").toString().contains("newVerdict"));

        Map<String, Object> other = DisputeNotice.forParticipant(store, ONE).getFirst();
        assertEquals(grantOne.grantId().toString(), map(map(other.get("newVerdict")).get("payload")).get("grantId"));
        assertEquals(List.of(TWO, AUTHOR), other.get("messageTo"));
    }

    @Test
    void theAuthorIsToldButHasNoVerdictToReplace() throws Exception {
        dispute();

        List<Map<String, Object>> notices = DisputeNotice.forParticipant(store, AUTHOR);

        assertEquals(1, notices.size(), notices.toString());
        Map<String, Object> notice = notices.getFirst();
        assertEquals(SNAPSHOT, notice.get("snapshotId"));
        assertNull(notice.get("newVerdict"));
        assertNull(notice.get("yourVerdict"));
        assertEquals(List.of(ONE, TWO), notice.get("messageTo"));
        assertTrue(notice.get("instruction").toString().contains("disagree"));
    }

    @Test
    void anUnrelatedParticipantIsToldNothing() throws Exception {
        dispute();

        assertEquals(List.of(), DisputeNotice.forParticipant(store, "agt-bystander"));
        assertFalse(DisputeNotice.verdictUnsettled(store, "agt-bystander"));
    }

    @Test
    void nothingIsToldOnceTheReviewersAgree() throws Exception {
        dispute();
        // Only the computer that owns reviewer two signs its new verdict; the entry reaches this log by copy.
        theirs.placeCopies(identity.nodeId(), store.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        theirs.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, second.nodeId(), verdict(grantTwo, TWO, "ACCEPTED").encode(),
                second);
        store.placeCopies(second.nodeId(), theirs.entriesAfter(0, 10_000, Integer.MAX_VALUE));

        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, store.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
        for (String participant : List.of(ONE, TWO, AUTHOR)) {
            assertEquals(List.of(), DisputeNotice.forParticipant(store, participant), participant);
        }
        assertTrue(store.taskCompletionProjection().everDisputed(store.taskCompletionProjection().findSnapshotById(SNAPSHOT).orElseThrow()
                .snapshotIdentity()), "the dispute stays on record");
    }

    @Test
    void noDisputeWhileOnlyOneReviewerHasSpoken() throws Exception {
        assertEquals(List.of(), DisputeNotice.forParticipant(store, ONE));
        record(grantOne, ONE, "ACCEPTED");
        assertEquals(List.of(), DisputeNotice.forParticipant(store, ONE));
        assertEquals(List.of(), DisputeNotice.forParticipant(store, AUTHOR));
    }

    @Test
    void aVerdictIsUnsettledWhileTheSnapshotIsAcceptedAndNotBefore() throws Exception {
        assertFalse(DisputeNotice.verdictUnsettled(store, ONE), "no verdict yet");
        record(grantOne, ONE, "ACCEPTED");
        assertTrue(DisputeNotice.verdictUnsettled(store, ONE), "accepted, not integrated");
        assertFalse(DisputeNotice.verdictUnsettled(store, TWO), "granted but silent");
        assertFalse(DisputeNotice.verdictUnsettled(store, AUTHOR), "the author gave no verdict");
    }

    @Test
    void bothPartiesToADisputeHaveAnUnsettledVerdict() throws Exception {
        dispute();

        assertTrue(DisputeNotice.verdictUnsettled(store, ONE));
        assertTrue(DisputeNotice.verdictUnsettled(store, TWO));
        assertFalse(DisputeNotice.verdictUnsettled(store, AUTHOR));
    }

    @Test
    void aSingleRejectionIsSettledNotUnsettled() throws Exception {
        record(grantOne, ONE, "REJECTED");

        assertEquals(TaskCompletionState.REVIEW_REJECTED, store.taskCompletionProjection().snapshotState(SNAPSHOT).orElseThrow());
        assertFalse(DisputeNotice.verdictUnsettled(store, ONE));
        assertEquals(List.of(), DisputeNotice.forParticipant(store, ONE));
    }
}
