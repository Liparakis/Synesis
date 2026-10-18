package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.control.MainMeeting;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;

/**
 * The re-merge of two diverged copies of {@code main} (ADR-0098 step 5, designer items 13 to 15): which computer acts,
 * what it builds, who reviews it again, and when it lands. This computer is a real project; the other computer is a
 * second node whose advance reaches this one's view as a copy of its log, as Link would bring it.
 */
class RemergeServiceTest {

    private static final String AUTHOR = "agt-author";
    private static final String REVIEWER = "agt-reviewer";

    @TempDir
    Path temp;

    private Project project;
    private NodeIdentity self;
    private PredictionEventStore store;
    private String branch;
    private String base;
    private String salt;
    private final RemergeService service = new RemergeService(new ProjectApplicationService(), new ProjectProcessExecutor());

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("project");
        Files.createDirectories(root);
        project = new Project(root);
        self = project.identity;
        store = project.store();
        base = project.head();
        branch = project.git("rev-parse", "--abbrev-ref", "HEAD");
        // Commits are named by content and time; a salt keeps one test's re-merge from being another's in the process-wide stopped set.
        salt = UUID.randomUUID().toString();
    }

    private NodeIdentity lowerNode() throws Exception {
        return otherNode(true);
    }

    private NodeIdentity higherNode() throws Exception {
        return otherNode(false);
    }

    private NodeIdentity otherNode(boolean lower) throws Exception {
        NodeIdentity other = NodeIdentity.generate();
        while (lower != (other.nodeId().compareTo(self.nodeId()) < 0)) {
            other = NodeIdentity.generate();
        }
        return other;
    }

    /** One commit adding {@code path} on {@code parent}, kept alive by a ref; the checkout is left on its own branch. */
    private String commit(String name, String parent, String path, String content) throws IOException {
        project.git("checkout", "-q", "-b", "tmp-" + name, parent);
        Files.writeString(project.root.resolve(path), content + salt + "\n");
        project.git("add", path);
        project.git("commit", "-q", "-m", name);
        String commit = project.head();
        project.git("update-ref", "refs/test/" + name, commit);
        project.git("checkout", "-q", branch);
        project.git("branch", "-q", "-D", "tmp-" + name);
        return commit;
    }

    /** This copy's main moves to a commit adding {@code path}. */
    private String ownMain(String path, String content) throws IOException {
        String commit = commit("own", base, path, content);
        project.git("merge", "-q", "--ff-only", commit);
        return commit;
    }

    private record Lane(String snapshotId, UUID task, UUID lane, UUID group) {
    }

    /** An integrated lane of AUTHOR on this computer's log, accepted by REVIEWER when review is required. */
    private Lane lane(String snapshotId, String path, boolean reviewRequired) throws Exception {
        UUID lane = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(lane, project.location.projectId(), AUTHOR, "codex", task, "implement", "acceptance", base,
                List.of(ResourceSelector.pathExact(path)), 1, group, WorkIntent.defaultAuthorityLineage(lane), WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, self).announce(intent).acquired());
        SnapshotProvenance provenance = new SnapshotProvenance(group, lane, intent.authorityLineageId(), AUTHOR, "session-" + AUTHOR, 1,
                List.of(), List.of(), List.of("PATH:" + path), "refs/synesis/snapshots/" + snapshotId, "integrity", "artifacts");
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, self.nodeId(),
                new TaskSnapshotPayload(task, snapshotId, self.nodeId(), "supervisor", "worker", "session-" + AUTHOR, base, "commit-" + snapshotId,
                        List.of(path), List.of(), "work", provenance, reviewRequired).encode(), self);
        Lane made = new Lane(snapshotId, task, lane, group);
        if (reviewRequired) {
            LaneGrant grant = new LaneGrant(UUID.randomUUID(), group, lane, REVIEWER, 1, true);
            store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, self.nodeId(), CollaborationCodec.encodeLaneGrant(grant), self);
            verdict(made, snapshotId, grant, "ACCEPTED");
        }
        // The lane was merged on this computer; a re-merge starts from an integrated snapshot.
        store.append(UUID.randomUUID(), PredictionEventType.TASK_INTEGRATED, self.nodeId(),
                new TaskSnapshotPayload(task, snapshotId, self.nodeId(), "supervisor", "worker", "session-" + AUTHOR, base, "commit-" + snapshotId,
                        List.of(path), List.of(), "work").encode(), self);
        return made;
    }

    /** A verdict on the snapshot through an issued grant, consumed first as the rules require. */
    private void verdict(Lane lane, String snapshotId, LaneGrant grant, String result) throws Exception {
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, self.nodeId(), CollaborationCodec.encodeLaneGrant(grant), self);
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, self.nodeId(),
                new ReviewValidationPayload(grant.grantId(), lane.group(), lane.lane(), grant.targetParticipant(), 1, lane.task(), snapshotId,
                        result, "REJECTED".equals(result) ? "not good enough" : null, AUTHOR).encode(), self);
    }

    private LaneGrant grantFor(String snapshotId) throws Exception {
        return grants().stream().filter(grant -> snapshotId.equals(grant.snapshotId())).findFirst().orElseThrow();
    }

    private List<LaneGrant> grants() throws Exception {
        return events(PredictionEventType.LANE_GRANT_ISSUED).stream().map(event -> {
            try {
                return CollaborationCodec.decodeLaneGrant(event.payload());
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }).toList();
    }

    private List<PredictionEvent> events(PredictionEventType type) throws Exception {
        return project.store().events().stream().filter(event -> event.type() == type).toList();
    }

    private void ownAdvance(String from, String to, String... snapshotIds) throws Exception {
        store.append(UUID.randomUUID(), PredictionEventType.CONTROL_BRANCH_ADVANCED, self.nodeId(),
                new IntegrationAttemptPayload("att_" + to.substring(0, 8), project.location.projectId(), List.of(snapshotIds), from, to, "advanced", "")
                        .encode(), self);
    }

    /** The other computer advanced its main from the base to {@code head}; its entry arrives here as a copy of its log. */
    private void otherAdvance(NodeIdentity other, String head) throws Exception {
        PredictionEventStore theirs = new PredictionEventStore(temp.resolve("other-" + other.nodeId()), project.location.projectId());
        theirs.append(UUID.randomUUID(), PredictionEventType.CONTROL_BRANCH_ADVANCED, other.nodeId(),
                new IntegrationAttemptPayload("att_" + head.substring(0, 8), project.location.projectId(), List.of(), base, head, "advanced", "")
                        .encode(), other);
        project.store().placeCopies(other.nodeId(), theirs.entriesAfter(0, 10_000, Integer.MAX_VALUE));
    }

    private void run() {
        try {
            service.run(project.root, project.store(), self);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private RemergeSnapshotPayload built(String other, String own) throws Exception {
        List<RemergeSnapshotPayload> built = project.store().taskCompletionProjection().remerge(RemergeService.remergeId(other, own));
        assertEquals(1, built.size(), "one re-merge snapshot for the one lane that needed review");
        return built.getFirst();
    }

    private IntegrationAttemptPayload landedFrom(String otherHead) throws Exception {
        return events(PredictionEventType.CONTROL_BRANCH_ADVANCED).stream().filter(event -> event.actorNodeId().equals(self.nodeId()))
                .map(event -> decode(event.payload())).filter(payload -> payload.expectedControlHead().equals(otherHead)).findFirst().orElseThrow();
    }

    private static IntegrationAttemptPayload decode(byte[] payload) {
        try {
            return IntegrationAttemptPayload.decode(payload);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void aHigherNodeBuildsTheReMergeAndOffersItsSnapshotToTheReviewerWhoAcceptedTheOriginal() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        TaskCompletionState before = project.state("snap_a");
        long advancesBefore = events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size();

        run();

        RemergeSnapshotPayload remerge = built(otherHead, own);
        String rms = remerge.snapshot().snapshotId();
        assertTrue(rms.startsWith("rms-"), rms);
        assertEquals("snap_a", remerge.originalSnapshotId());
        assertEquals(otherHead, remerge.otherHead());
        assertEquals(own, remerge.ownHead());
        String refCommit = project.git("rev-parse", "refs/synesis/snapshots/" + rms);
        assertEquals(remerge.snapshot().commitSha(), refCommit);
        assertTrue(project.git("rev-list", "--parents", "-n", "1", refCommit).contains(otherHead), "the merge commit has the other head as a parent");
        assertEquals(refCommit, remerge.result(), "one lane: its merge commit is the result");
        LaneGrant grant = grantFor(rms);
        assertEquals(REVIEWER, grant.targetParticipant(), "the participant that accepted the original, not its author");
        assertNotEquals(AUTHOR, grant.targetParticipant());
        assertEquals(own, project.head(), "main has not moved yet");
        assertEquals(before, project.state("snap_a"), "the original task's state is unchanged");
        assertTrue(project.store().taskCompletionProjection().allSnapshots().stream().noneMatch(snapshot -> snapshot.snapshotId().equals(rms)),
                "a re-merge snapshot is not an ordinary snapshot");
        assertEquals(advancesBefore, events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size(), "no advance recorded yet");

        run();

        assertEquals(1, events(PredictionEventType.REMERGE_SNAPSHOT_CREATED).size(), "a second pass before the review builds nothing again");
        assertEquals(1, grants().stream().filter(value -> rms.equals(value.snapshotId())).count());
        assertEquals(own, project.head());
    }

    @Test
    void onceTheReMergeSnapshotIsAcceptedTheNextPassMovesMainForwardAndTheOtherComputersAuditTakesIt() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        run();
        String rms = built(otherHead, own).snapshot().snapshotId();
        verdict(lane, rms, grantFor(rms), "ACCEPTED");
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, project.state(rms));

        run();

        String result = project.head();
        assertNotEquals(own, result);
        assertTrue(project.isAncestorOfHead(own), "main only moves forward: the old head is in the result");
        assertTrue(project.isAncestorOfHead(otherHead), "and so is the other computer's head");
        assertTrue(project.headHas("own.txt"));
        assertTrue(project.headHas("other.txt"));
        assertEquals(result, built(otherHead, own).result());
        IntegrationAttemptPayload landed = landedFrom(otherHead);
        assertEquals(result, landed.integrationCommitSha());
        assertEquals(List.of(rms), landed.taskSnapshotIds());
        assertEquals(List.of(), MainMeeting.audit(project.root, project.store(), otherHead, result), "the other computer's view takes it");

        run();

        assertEquals(result, project.head(), "nothing left to do once main holds the result");
    }

    @Test
    void aLowerNodeLeavesTheReMergeToTheOtherComputer() throws Exception {
        NodeIdentity other = higherNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        int entries = project.store().events().size();

        run();

        assertEquals(own, project.head());
        assertEquals(entries, project.store().events().size(), "nothing was written");
        assertEquals(0, events(PredictionEventType.REMERGE_SNAPSHOT_CREATED).size());
    }

    @Test
    void aMergeGitCannotMakeWritesNothingAndLeavesTheBranch() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "shared.txt", true);
        String own = ownMain("shared.txt", "own side");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "shared.txt", "other side");
        otherAdvance(other, otherHead);
        int entries = project.store().events().size();

        run();
        run();

        assertEquals(own, project.head());
        assertEquals(entries, project.store().events().size(), "no re-merge entry, grant or advance");
        assertEquals("", project.git("status", "--porcelain", "--untracked-files=no"));
    }

    @Test
    void aFailingValidationCommandWritesNothingAndLeavesTheBranch() throws Exception {
        NodeIdentity other = lowerNode();
        project.declare(List.of("git", "rev-parse", "--verify", "refs/heads/branch-that-does-not-exist"));
        base = project.head();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        int entries = project.store().events().size();

        run();

        assertEquals(own, project.head());
        assertEquals(entries, project.store().events().size());
    }

    @Test
    void aLaneThatNeededNoReviewLandsOnTheFirstPassWithNoReMergeEntry() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", false);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);

        run();

        String result = project.head();
        assertNotEquals(own, result);
        assertTrue(project.isAncestorOfHead(own));
        assertTrue(project.isAncestorOfHead(otherHead));
        assertEquals(0, events(PredictionEventType.REMERGE_SNAPSHOT_CREATED).size());
        IntegrationAttemptPayload landed = landedFrom(otherHead);
        assertEquals(result, landed.integrationCommitSha());
        assertEquals(List.of("snap_a"), landed.taskSnapshotIds());
        assertEquals(List.of(), MainMeeting.audit(project.root, project.store(), otherHead, result));
    }

    @Test
    void aRejectedReMergeSnapshotNeverLands() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        run();
        String rms = built(otherHead, own).snapshot().snapshotId();
        verdict(lane, rms, grantFor(rms), "REJECTED");
        assertEquals(TaskCompletionState.REVIEW_REJECTED, project.state(rms));
        int entries = project.store().events().size();

        run();
        run();

        assertEquals(own, project.head());
        assertEquals(entries, project.store().events().size(), "no advance was recorded");
        assertFalse(project.isAncestorOfHead(otherHead));
    }
}
