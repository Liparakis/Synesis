package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
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
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.test.TestGit;

/**
 * How a computer's copy of {@code main} meets another computer's head (ADR-0098 step 5, "Getting onto
 * {@code main}"): the relation of two heads, and the audit that follows the other head back advance by advance
 * through the logs before it may be taken.
 */
class MainMeetingTest {

    @TempDir
    Path temp;

    private Path repo;
    private UUID project;
    private NodeIdentity identity;
    private PredictionEventStore store;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        repo = temp.resolve("repo");
        Files.createDirectories(repo);
        TestGit.run(repo, "init");
        TestGit.run(repo, "config", "user.email", "test@example.invalid");
        TestGit.run(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "base\n");
        TestGit.run(repo, "add", "README.md");
        TestGit.run(repo, "commit", "-m", "base");
        base = TestGit.output(repo, "rev-parse", "HEAD");
        project = UUID.randomUUID();
        identity = NodeIdentity.generate();
        store = new PredictionEventStore(temp.resolve("coordination"), project);
    }

    private String commitOn(String parent, String message) throws IOException {
        return TestGit.output(repo, "commit-tree", base + "^{tree}", "-p", parent, "-m", message);
    }

    /** A lane of {@code participant} on this computer's log, with its snapshot awaiting review. */
    private record Snapshot(String id, String participant, UUID task, UUID lane, UUID group, UUID lineage) {
    }

    private Snapshot snapshot(String id, String participant, String path) throws Exception {
        UUID lane = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(lane, project, participant, "codex", task, "implement", "acceptance", base,
                List.of(ResourceSelector.pathExact(path)), 1, group, WorkIntent.defaultAuthorityLineage(lane), WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        SnapshotProvenance provenance = new SnapshotProvenance(group, lane, intent.authorityLineageId(), participant, "session-" + participant, 1,
                List.of(), List.of(), List.of("PATH:" + path), "refs/synesis/snapshots/" + id, "integrity", "artifacts");
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(),
                new TaskSnapshotPayload(task, id, "node", "supervisor", "worker", "session-" + participant, base, "commit-" + id,
                        List.of(path), List.of(), "work", provenance, true).encode(), identity);
        return new Snapshot(id, participant, task, lane, group, intent.authorityLineageId());
    }

    private void review(Snapshot snapshot, String reviewer, String result) throws Exception {
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), snapshot.group(), snapshot.lane(), reviewer, 1, true);
        for (PredictionEventType type : List.of(PredictionEventType.LANE_GRANT_ISSUED, PredictionEventType.LANE_GRANT_CONSUMED)) {
            store.append(grant.grantId(), type, identity.nodeId(), CollaborationCodec.encodeLaneGrant(grant), identity);
        }
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, identity.nodeId(),
                new ReviewValidationPayload(grant.grantId(), snapshot.group(), snapshot.lane(), reviewer, 1, snapshot.task(), snapshot.id(),
                        result, "REJECTED".equals(result) ? "not good enough" : null, snapshot.participant()).encode(), identity);
    }

    private static void advance(PredictionEventStore into, NodeIdentity writer, UUID project, String from, String to, String... snapshotIds)
            throws Exception {
        into.append(UUID.randomUUID(), PredictionEventType.CONTROL_BRANCH_ADVANCED, writer.nodeId(),
                new IntegrationAttemptPayload("att_" + to.substring(0, 8), project, List.of(snapshotIds), from, to, "advanced", "").encode(), writer);
    }

    private void advance(String from, String to, String... snapshotIds) throws Exception {
        advance(store, identity, project, from, to, snapshotIds);
    }

    @Test
    void aHeadIsHeldBehindOrDivergedByWhatEachCopyHolds() throws Exception {
        String first = commitOn(base, "first");
        String second = commitOn(first, "second");
        String other = commitOn(base, "other");
        TestGit.run(repo, "update-ref", "refs/test/second", second);
        TestGit.run(repo, "update-ref", "refs/test/other", other);

        assertEquals(MainMeeting.Relation.HELD, MainMeeting.relate(repo, second, second), "the same head");
        assertEquals(MainMeeting.Relation.HELD, MainMeeting.relate(repo, second, first), "the other head is an ancestor of this one");
        assertEquals(MainMeeting.Relation.BEHIND, MainMeeting.relate(repo, first, second), "this copy may fast-forward");
        assertEquals(MainMeeting.Relation.DIVERGED, MainMeeting.relate(repo, second, other), "each holds work the other lacks");
        assertEquals(MainMeeting.Relation.DIVERGED, MainMeeting.relate(repo, other, second));
    }

    @Test
    void theAdvancesAndLastHeadsAreWhatTheEntriesName() throws Exception {
        String first = commitOn(base, "first");
        String second = commitOn(first, "second");
        advance(base, first, "snap_1");
        advance(first, second, "snap_2");

        Map<String, MainMeeting.Advance> byHead = MainMeeting.advances(store.events());
        assertEquals(new MainMeeting.Advance(identity.nodeId(), base, first, List.of("snap_1")), byHead.get(first));
        assertEquals(new MainMeeting.Advance(identity.nodeId(), first, second, List.of("snap_2")), byHead.get(second));
        assertEquals(Map.of(identity.nodeId(), second), MainMeeting.lastHeads(store.events()), "the writer's last advanced head");
    }

    @Test
    void aChainOfAdvancesWithReviewsByNonAuthorsPassesTheAudit() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        Snapshot b = snapshot("snap_b", "agt-b", "src/b.txt");
        review(a, "agt-reviewer", "ACCEPTED");
        review(b, "agt-reviewer", "ACCEPTED");
        String first = commitOn(base, "first");
        String second = commitOn(first, "second");
        advance(base, first, "snap_a");
        advance(first, second, "snap_b");

        assertEquals(List.of(), MainMeeting.audit(repo, store, base, second));
        assertEquals(List.of(), MainMeeting.audit(repo, store, first, second), "only the advances this copy lacks are followed");
        assertEquals(List.of(), MainMeeting.audit(repo, store, second, second), "nothing to take");
    }

    @Test
    void aCommitNoAdvanceNamesStopsTheAudit() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        review(a, "agt-reviewer", "ACCEPTED");
        String first = commitOn(base, "first");
        String second = commitOn(first, "second, written by hand");
        advance(base, first, "snap_a");

        assertEquals(List.of("UNNAMED_COMMIT:" + second), MainMeeting.audit(repo, store, base, second));
    }

    @Test
    void aSnapshotThisComputerHasNotSeenIsReported() throws Exception {
        String first = commitOn(base, "first");
        advance(base, first, "snap_unknown");

        assertEquals(List.of("SNAPSHOT_NOT_SEEN:snap_unknown"), MainMeeting.audit(repo, store, base, first));
    }

    @Test
    void aSnapshotThatNoOneReviewedIsMissingItsReview() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        String first = commitOn(base, "first");
        advance(base, first, a.id());

        assertEquals(List.of("REVIEW_MISSING:snap_a"), MainMeeting.audit(repo, store, base, first));
    }

    @Test
    void aSnapshotOnlyRejectedIsMissingItsReview() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        review(a, "agt-reviewer", "REJECTED");
        String first = commitOn(base, "first");
        advance(base, first, a.id());

        assertEquals(List.of("REVIEW_MISSING:snap_a"), MainMeeting.audit(repo, store, base, first));
    }

    @Test
    void anAdvanceCopiedFromAComputerThatDidNotIntroduceTheLaneIsNotTaken() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        review(a, "agt-reviewer", "ACCEPTED");
        // Another computer's log names the snapshot, which it never introduced. Its own log cannot tell (it does not
        // hold the lane); the computer that holds the lane receives the copy.
        NodeIdentity stranger = NodeIdentity.generate();
        PredictionEventStore strangerStore = new PredictionEventStore(temp.resolve("stranger"), project);
        String first = commitOn(base, "first");
        advance(strangerStore, stranger, project, base, first, a.id());

        store.placeCopies(stranger.nodeId(), strangerStore.entriesAfter(0, 10_000, Integer.MAX_VALUE));

        // The arriving entry is refused by the lane's introducer rule before the audit sees it (ParticipantAttribution),
        // so the head is named by no advance here, and the audit stops there.
        assertEquals(List.of("UNNAMED_COMMIT:" + first), MainMeeting.audit(repo, store, base, first));
    }

    @Test
    void anAdvanceWithoutAHeadItMovedFromIsAnInvalidChain() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        review(a, "agt-reviewer", "ACCEPTED");
        String first = commitOn(base, "first");
        advance("", first, a.id());

        assertEquals(List.of("ADVANCE_CHAIN_INVALID:" + first), MainMeeting.audit(repo, store, base, first));
    }

    @Test
    void anAdvanceChainThatLoopsBackOnItselfIsAnInvalidChain() throws Exception {
        Snapshot a = snapshot("snap_a", "agt-a", "src/a.txt");
        review(a, "agt-reviewer", "ACCEPTED");
        String first = commitOn(base, "first");
        String second = commitOn(first, "second");
        // Each names the other as the head it moved from: no real history does.
        advance(second, first, a.id());
        advance(first, second, a.id());

        List<String> problems = MainMeeting.audit(repo, store, base, second);

        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("ADVANCE_CHAIN_INVALID:")), problems.toString());
    }
}
