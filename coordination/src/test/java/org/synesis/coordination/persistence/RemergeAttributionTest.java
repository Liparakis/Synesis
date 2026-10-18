package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.link.identity.NodeIdentity;

/**
 * A re-merge snapshot acts for the participant of its inner snapshot's provenance, so only the computer that
 * introduced that participant may write it (ADR-0098 step 5).
 */
final class RemergeAttributionTest {

    private final UUID project = UUID.randomUUID();
    private Path leaderRoot;
    private Path memberRoot;
    private NodeIdentity leader;
    private NodeIdentity member;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        leaderRoot = temp.resolve("leader");
        memberRoot = temp.resolve("member");
        leader = NodeIdentity.generate();
        member = NodeIdentity.generate();
        ReplicationRole.register(memberRoot, ReplicationRole.memberOf(leader.nodeId()));
    }

    @AfterEach
    void tearDown() throws Exception {
        ReplicationRole.register(memberRoot, ReplicationRole.leader());
    }

    private static TaskSnapshotPayload snapshot(WorkIntent lane, String snapshotId, boolean reviewRequired) {
        return new TaskSnapshotPayload(lane.taskId(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                "session-" + lane.participant(),
                "base",
                "commit-" + snapshotId,
                List.of("src/a.py"),
                List.of(),
                "work",
                new SnapshotProvenance(lane.workGroupId(),
                        lane.intentId(),
                        lane.authorityLineageId(),
                        lane.participant(),
                        "session-" + lane.participant(),
                        1,
                        List.of(),
                        List.of(),
                        List.of("PATH:src/a.py"),
                        "refs/synesis/snapshots/" + snapshotId,
                        "integrity",
                        "artifacts"),
                reviewRequired);
    }

    @Test
    void onlyTheComputerThatIntroducedTheParticipantWritesItsReMergeSnapshot() throws Exception {
        UUID lane = UUID.randomUUID();
        WorkIntent held = new WorkIntent(lane,
                project,
                "agt-a",
                "codex",
                UUID.randomUUID(),
                "Build a board",
                "CRUD",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                1,
                UUID.randomUUID(),
                WorkIntent.defaultAuthorityLineage(lane),
                WorkIntent.Status.ANNOUNCED);
        PredictionEventStore leaderStore = new PredictionEventStore(leaderRoot, project);
        assertTrue(new WorkIntentService(leaderStore, leader).announce(held).acquired());
        byte[] original = snapshot(held, "snap_original", false).encode();
        leaderStore.append(held.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, leader.nodeId(), original, leader);
        leaderStore.append(held.taskId(),
                PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                leader.nodeId(),
                new IntegrationAttemptPayload("att_original", project, List.of("snap_original"), "base",
                        "integration", "started", "").encode(),
                leader);
        leaderStore.append(held.taskId(), PredictionEventType.TASK_INTEGRATED, leader.nodeId(), original, leader);

        // The member holds the leader's log as a copy, so it knows the original, but not as the lane's introducer.
        List<byte[]> entries = leaderStore.entriesAfter(PredictionEventStore.OWN_LOG, 0, 1000, Integer.MAX_VALUE);
        PredictionEventStore memberStore = new PredictionEventStore(memberRoot, project);
        assertEquals(entries.size(), memberStore.placeCopies(leader.nodeId(), entries));
        long before = memberStore.events().size();

        byte[] remerge = new RemergeSnapshotPayload("remerge-1", "snap_original", "other-head", "own-head", "result",
                snapshot(held, "snap_remerge", true)).encode();
        IOException refusal = assertThrows(IOException.class, () -> memberStore.append(held.taskId(),
                PredictionEventType.REMERGE_SNAPSHOT_CREATED, member.nodeId(), remerge, member));

        assertEquals("PARTICIPANT_NOT_OWNED_BY_SIGNER", refusal.getMessage());
        assertEquals(before, memberStore.events().size());
        assertTrue(memberStore.taskCompletionProjection().findSnapshotById("snap_remerge").isEmpty());

        leaderStore.append(held.taskId(), PredictionEventType.REMERGE_SNAPSHOT_CREATED, leader.nodeId(), remerge,
                leader);
        assertTrue(leaderStore.taskCompletionProjection().findSnapshotById("snap_remerge").isPresent());
    }
}
