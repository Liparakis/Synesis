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

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CombinePayload;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.control.MainMeeting;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;

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
        verdict(lane, snapshotId, grant, result, AUTHOR);
    }

    private void verdict(Lane lane, String snapshotId, LaneGrant grant, String result, String producer) throws Exception {
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, self.nodeId(), CollaborationCodec.encodeLaneGrant(grant), self);
        store.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, self.nodeId(),
                new ReviewValidationPayload(grant.grantId(), lane.group(), lane.lane(), grant.targetParticipant(), 1, lane.task(), snapshotId,
                        result, "REJECTED".equals(result) ? "not good enough" : null, producer).encode(), self);
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

    /**
     * A producer of this computer, its participant named by its session, with a lane open and, when {@code live}, a
     * live session lease here.
     */
    private String producerHere(String session, String path, boolean live) throws Exception {
        String participant = WorkspaceCollaborationService.participantHandle(session);
        UUID lane = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(lane, project.location.projectId(), participant, "codex", UUID.randomUUID(), "implement", "acceptance", base,
                List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(lane), WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, self).announce(intent).acquired());
        if (live) {
            new SessionLeaseService().createOrRenewLease(project.root, project.location.projectId().toString(), "codex", "conn-" + session, self.nodeId(), session,
                    new SessionLeasePolicy());
        }
        return participant;
    }

    /**
     * Designer item 2: the reviewer who accepted the original cannot be reached, so its re-merge grant was released.
     * The next pass offers the re-merge to another agent of this computer that is live and not the author, once.
     */
    @Test
    void aReMergeWhoseReviewerWasReleasedIsOfferedToAnotherLiveNonAuthorHere() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        run();
        String rms = built(otherHead, own).snapshot().snapshotId();
        LaneGrant first = grantFor(rms);
        assertEquals(REVIEWER, first.targetParticipant());
        String gone = producerHere("session-gone-producer", "gone.txt", false);
        String live = producerHere("session-live-producer", "live.txt", true);
        run();
        assertEquals(1, grants().stream().filter(grant -> rms.equals(grant.snapshotId())).count(), "a held re-merge review is offered to nobody else");

        assertTrue(new WorkGroupService(project.store(), self).revokeStalledReview(first.identity()));
        run();
        run();

        List<LaneGrant> offered = grants().stream().filter(grant -> rms.equals(grant.snapshotId())).toList();
        assertEquals(2, offered.size(), "one more grant, written once: " + offered);
        assertEquals(live, offered.get(1).targetParticipant(), "the live agent here, not " + gone + " whose session is gone, nor the author");
        assertNotEquals(first.grantId(), offered.get(1).grantId());
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

    /** Both copies added {@code shared.txt}: Git cannot merge this copy's one advance onto the other head. */
    private record Conflict(Lane lane, String own, String otherHead) {
    }

    private Conflict conflict() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "shared.txt", true);
        releaseAuthorLane(lane);
        String own = ownMain("shared.txt", "own side");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "shared.txt", "other side");
        otherAdvance(other, otherHead);
        return new Conflict(lane, own, otherHead);
    }

    /** The merge pass released the author's lane when it integrated it, which frees the paths it claimed. */
    private void releaseAuthorLane(Lane lane) throws Exception {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(project.root.resolve(".synesis/coordination"))) {
            new WorkIntentService(project.store(), self).releaseAfterIntegration(lock,
                    project.store().taskCompletionProjection().findSnapshotById(lane.snapshotId()).orElseThrow());
        }
    }

    private CombinePayload combine() throws Exception {
        List<CombinePayload> combines = project.store().taskCompletionProjection().combines();
        assertEquals(1, combines.size(), "one combine");
        return combines.getFirst();
    }

    /** What a combine agent does: takes the combine lane as a participant this computer introduced, who is not the author. */
    private Lane combineLane(CombinePayload combine) throws Exception {
        UUID lane = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(lane, project.location.projectId(), "agt-combiner", "codex", combine.taskId(), "combine", "both versions",
                combine.base(), List.of(ResourceSelector.pathExact("shared.txt")), 1, combine.workGroupId(), WorkIntent.defaultAuthorityLineage(lane),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, self).announce(intent).acquired(), "the combine lane is granted");
        return new Lane(null, combine.taskId(), lane, combine.workGroupId());
    }

    /** The combine lane's snapshot: a commit on the combine's base that keeps both sides' lines, recorded on the log. */
    private String combineSnapshot(CombinePayload combine, Lane lane, String snapshotId, long epoch, boolean reviewRequired) throws Exception {
        String commit = commit("combine-" + snapshotId, combine.base(), "shared.txt", "own side\nother side");
        project.git("update-ref", "refs/synesis/snapshots/" + snapshotId, commit);
        SnapshotProvenance provenance = new SnapshotProvenance(lane.group(), lane.lane(), WorkIntent.defaultAuthorityLineage(lane.lane()), "agt-combiner",
                "session-agt-combiner", epoch, List.of(), List.of(), List.of("PATH:shared.txt"), "refs/synesis/snapshots/" + snapshotId, "integrity",
                "artifacts");
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, self.nodeId(),
                new TaskSnapshotPayload(lane.task(), snapshotId, self.nodeId(), "supervisor", "worker", "session-agt-combiner", combine.base(), commit,
                        List.of("shared.txt"), List.of(), "combined", provenance, reviewRequired).encode(), self);
        return commit;
    }

    @Test
    void aMergeGitCannotMakeOpensOneCombineAndLeavesTheBranch() throws Exception {
        Conflict conflict = conflict();
        long groups = events(PredictionEventType.WORK_GROUP_CREATED).size();
        long combines = events(PredictionEventType.COMBINE_REQUIRED).size();
        int entries = project.store().events().size();

        run();

        assertEquals(conflict.own(), project.head(), "the branch is unchanged");
        assertEquals("", project.git("status", "--porcelain", "--untracked-files=no"));
        assertEquals(combines + 1, events(PredictionEventType.COMBINE_REQUIRED).size(), "exactly one COMBINE_REQUIRED");
        assertEquals(groups + 1, events(PredictionEventType.WORK_GROUP_CREATED).size(), "exactly one work group, the combine's");
        assertEquals(entries + 2, project.store().events().size(), "nothing else was written: no re-merge snapshot, grant or advance");
        CombinePayload combine = combine();
        assertEquals(RemergeService.remergeId(conflict.otherHead(), conflict.own()), combine.remergeId());
        assertEquals(conflict.otherHead(), combine.base(), "the first advance conflicted, so it was to be merged onto the other head");
        assertEquals(conflict.otherHead(), combine.otherHead());
        assertEquals(conflict.own(), combine.ownHead());
        assertEquals(conflict.own(), combine.theirs(), "the advance's head");
        assertEquals(List.of(conflict.lane().snapshotId()), combine.snapshotIds());
        WorkGroup group = project.store().workGroupProjection().group(combine.workGroupId()).orElseThrow();
        assertEquals(WorkGroup.Status.ACTIVE, group.status());
        assertEquals(conflict.otherHead(), project.git("rev-parse", "refs/synesis/remerge-base/" + combine.combineId()),
                "the commit to combine onto is kept reachable");

        run();
        run();

        assertEquals(entries + 2, project.store().events().size(), "later runs write nothing more");
        assertEquals(conflict.own(), project.head());
    }

    @Test
    void aCombineLanesResultIsMergedReviewedAgainLandedAndTheCombineLaneIsClosed() throws Exception {
        Conflict conflict = conflict();
        run();
        CombinePayload combine = combine();
        Lane combiner = combineLane(combine);
        String combined = combineSnapshot(combine, combiner, "snap_combine", 1, false);

        run();

        RemergeSnapshotPayload remerge = built(conflict.otherHead(), conflict.own());
        String result = project.git("rev-parse", "refs/synesis/remerge/" + RemergeService.remergeId(conflict.otherHead(), conflict.own()));
        assertEquals(result, remerge.result());
        assertEquals(List.of(result, combine.base(), combine.theirs()), List.of(project.git("rev-list", "--parents", "-n", "1", result).split("\\s+")),
                "the merge commit has the combine's base and the advance's head as parents");
        assertEquals(project.git("rev-parse", combined + "^{tree}"), project.git("rev-parse", result + "^{tree}"), "and the combine lane's tree");
        assertEquals(REVIEWER, grantFor(remerge.snapshot().snapshotId()).targetParticipant(), "the original reviewer reviews the re-merge snapshot");
        assertEquals(conflict.own(), project.head(), "main has not moved before the review");

        verdict(conflict.lane(), remerge.snapshot().snapshotId(), grantFor(remerge.snapshot().snapshotId()), "ACCEPTED");
        run();

        String landed = project.head();
        assertEquals(result, landed);
        assertTrue(project.isAncestorOfHead(conflict.own()));
        assertTrue(project.isAncestorOfHead(conflict.otherHead()));
        assertTrue(project.git("show", "HEAD:shared.txt").contains("own side"));
        assertTrue(project.git("show", "HEAD:shared.txt").contains("other side"));
        assertEquals(result, landedFrom(conflict.otherHead()).integrationCommitSha());
        assertEquals(List.of(), MainMeeting.audit(project.root, project.store(), conflict.otherHead(), landed), "the other computer's view takes it");

        run();

        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_combine"), "the combine lane's snapshot is recorded integrated");
        assertTrue(project.store().collaborationProjection().activeIntents().stream().noneMatch(intent -> intent.intentId().equals(combiner.lane())),
                "and its lane is released");
        assertEquals(WorkGroup.Status.COMPLETED, project.store().workGroupProjection().group(combine.workGroupId()).orElseThrow().status(),
                "so the combine's work group closes");
        assertEquals(landed, project.head());
    }

    @Test
    void withoutACombineSnapshotRepeatedRunsNeitherLandNorWriteAnything() throws Exception {
        Conflict conflict = conflict();
        run();
        int entries = project.store().events().size();

        run();
        run();

        assertEquals(entries, project.store().events().size());
        assertEquals(conflict.own(), project.head());
        assertEquals("", project.git("for-each-ref", "refs/synesis/remerge/"), "no result was built");
        assertEquals(0, events(PredictionEventType.REMERGE_SNAPSHOT_CREATED).size());
    }

    @Test
    void aCombineSnapshotTheReviewerRejectedIsNotUsedButALaterOneIs() throws Exception {
        Conflict conflict = conflict();
        run();
        CombinePayload combine = combine();
        Lane combiner = combineLane(combine);
        combineSnapshot(combine, combiner, "snap_combine_1", 1, true);
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), combiner.group(), combiner.lane(), REVIEWER, 1, true);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, self.nodeId(), CollaborationCodec.encodeLaneGrant(grant), self);
        verdict(combiner, "snap_combine_1", grant, "REJECTED", "agt-combiner");
        assertEquals(TaskCompletionState.REVIEW_REJECTED, project.state("snap_combine_1"));
        int entries = project.store().events().size();

        run();

        assertEquals(entries, project.store().events().size(), "the refused result is not merged");
        assertEquals("", project.git("for-each-ref", "refs/synesis/remerge/"));

        String second = combineSnapshot(combine, combiner, "snap_combine_2", 2, false);
        run();

        assertEquals(project.git("rev-parse", second + "^{tree}"),
                project.git("rev-parse", built(conflict.otherHead(), conflict.own()).result() + "^{tree}"), "the later snapshot is the one used");
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

    @Test
    void runningTheServiceRepeatedlyBeforeAnyReviewWritesEachSnapshotAndGrantOnce() throws Exception {
        NodeIdentity other = lowerNode();
        Lane laneA = lane("snap_a", "own-a.txt", true);
        String ownA = ownMain("own-a.txt", "own a");
        ownAdvance(base, ownA, laneA.snapshotId());
        Lane laneB = lane("snap_b", "own-b.txt", true);
        String ownB = commit("own-b", ownA, "own-b.txt", "own b");
        project.git("merge", "-q", "--ff-only", ownB);
        ownAdvance(ownA, ownB, laneB.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);

        run();
        run();
        run();

        List<RemergeSnapshotPayload> built = project.store().taskCompletionProjection().remerge(RemergeService.remergeId(otherHead, ownB));
        assertEquals(2, built.size());
        assertEquals(2, events(PredictionEventType.REMERGE_SNAPSHOT_CREATED).size());
        for (RemergeSnapshotPayload remerge : built) {
            String rms = remerge.snapshot().snapshotId();
            assertEquals(1, grants().stream().filter(grant -> rms.equals(grant.snapshotId())).count(), "one grant for " + rms);
        }
        assertEquals(ownB, project.head());
    }

    @Test
    void aResultKeptUnderItsRefWithNoEntriesGetsItsSnapshotAndGrantOnTheNextPass() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        // What a pass that died after build() but before writing anything leaves behind: the result under its ref.
        String tree = project.git("merge-tree", "--write-tree", otherHead, own).lines().findFirst().orElseThrow().trim();
        String result = project.git("commit-tree", tree, "-p", otherHead, "-p", own, "-m", "built, then died");
        project.git("update-ref", "refs/synesis/remerge/" + RemergeService.remergeId(otherHead, own), result);
        assertEquals(0, events(PredictionEventType.REMERGE_SNAPSHOT_CREATED).size());

        run();

        RemergeSnapshotPayload remerge = built(otherHead, own);
        assertEquals(result, remerge.result(), "the kept result is used, not built again");
        assertEquals(result, remerge.snapshot().commitSha());
        assertEquals(REVIEWER, grantFor(remerge.snapshot().snapshotId()).targetParticipant());
        assertEquals(own, project.head());
    }

    @Test
    void anAdvanceRecordedBeforeTheBranchMovedOnlyMovesTheBranch() throws Exception {
        NodeIdentity other = lowerNode();
        Lane lane = lane("snap_a", "own.txt", true);
        String own = ownMain("own.txt", "own");
        ownAdvance(base, own, lane.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        run();
        RemergeSnapshotPayload remerge = built(otherHead, own);
        String rms = remerge.snapshot().snapshotId();
        verdict(lane, rms, grantFor(rms), "ACCEPTED");
        String remergeId = RemergeService.remergeId(otherHead, own);
        // The pass appended the advance and died before the branch moved.
        store.append(UUID.nameUUIDFromBytes(("remerge-advanced:" + remergeId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                PredictionEventType.CONTROL_BRANCH_ADVANCED, self.nodeId(),
                new IntegrationAttemptPayload(remergeId, project.location.projectId(), List.of(rms), otherHead, remerge.result(), "advanced", "")
                        .encode(), self);
        assertEquals(own, project.head());
        long advances = events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size();

        run();

        assertEquals(remerge.result(), project.head(), "the branch moved to the recorded result");
        assertEquals(advances, events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size(), "no second advance entry");
        assertEquals("", project.git("for-each-ref", "refs/synesis/remerge/"), "the kept result is dropped once it landed");
        run();
        assertEquals(remerge.result(), project.head());
        assertEquals(advances, events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size());
    }

    @Test
    void aReviewedLaneAndAnUnreviewedLaneReMergedTogetherPassTheOtherComputersAudit() throws Exception {
        NodeIdentity other = lowerNode();
        Lane laneA = lane("snap_a", "own-a.txt", true);
        String ownA = ownMain("own-a.txt", "own a");
        ownAdvance(base, ownA, laneA.snapshotId());
        Lane laneB = lane("snap_b", "own-b.txt", false);
        String ownB = commit("own-b", ownA, "own-b.txt", "own b");
        project.git("merge", "-q", "--ff-only", ownB);
        ownAdvance(ownA, ownB, laneB.snapshotId());
        String otherHead = commit("other", base, "other.txt", "other");
        otherAdvance(other, otherHead);
        run();
        String rms = project.store().taskCompletionProjection().remerge(RemergeService.remergeId(otherHead, ownB)).getFirst().snapshot().snapshotId();
        verdict(laneA, rms, grantFor(rms), "ACCEPTED");

        run();

        String result = project.head();
        assertNotEquals(ownB, result);
        assertEquals(List.of(), MainMeeting.audit(project.root, project.store(), otherHead, result));
    }
}
