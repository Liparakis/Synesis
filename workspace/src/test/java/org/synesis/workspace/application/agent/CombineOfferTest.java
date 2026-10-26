package org.synesis.workspace.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.persistence.ProjectIntegrationLock;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.collaboration.CombineFixture;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.test.TestGit;

/**
 * How an agent is offered a combine lane and what finishing it does (ADR-0098 step 5, designer item 22): a lane-less
 * agent that wrote none of the combined work is told to take it, and the combine lane's snapshot is the re-merge's to
 * use, never the ordinary pass's to merge.
 */
final class CombineOfferTest {

    private static final String COMBINER = "combine-combiner";

    @TempDir
    Path temp;

    private CombineFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = CombineFixture.create(temp);
        fixture.session(COMBINER);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private List<PredictionEvent> events(PredictionEventType type) throws Exception {
        return fixture.store().events().stream().filter(event -> event.type() == type).toList();
    }

    @Test
    void aLanelessSessionThatWroteNoneOfItIsToldToTakeTheCombineFirst() throws Exception {
        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER));

        Map<String, Object> result = map(response.result());
        List<?> candidates = (List<?>) result.get("combineCandidates");
        assertNotNull(candidates, response.toJson());
        assertEquals(1, candidates.size());
        Map<String, Object> candidate = map(candidates.getFirst());
        assertEquals(fixture.combine.combineId(), candidate.get("combineId"));
        assertEquals(List.of(fixture.authorSnapshot.snapshotId()), candidate.get("snapshotIds"));
        assertEquals(List.of(fixture.participant(CombineFixture.AUTHOR)), candidate.get("authors"));
        Map<String, Object> task = map(map(candidate.get("ensureSession")).get("task"));
        assertEquals(fixture.authorLane.intentId().toString(), task.get("repairIntentId"));
        assertEquals(fixture.authorSnapshot.snapshotId(), task.get("repairSnapshotId"));
        String instruction = String.valueOf(result.get("instruction"));
        assertTrue(instruction.contains("combine lane") && instruction.contains("combineCandidates[0].ensureSession"), instruction);
    }

    private void assertOfferedTheCombine(AgentResponse response) {
        List<?> candidates = (List<?>) map(response.result()).get("combineCandidates");
        assertNotNull(candidates, response.toJson());
        assertEquals(fixture.combine.combineId(), map(candidates.getFirst()).get("combineId"), response.toJson());
    }

    /** s5-run6: the second fresh agent was told to wait while another call's pass held the integration lock. */
    @Test
    void aLanelessSessionIsOfferedTheCombineWhileAnotherPassHoldsTheIntegrationLock() throws Exception {
        try (ProjectIntegrationLock held = ProjectIntegrationLock.tryAcquire(fixture.store().rootDirectory())) {
            assertNotNull(held);
            assertOfferedTheCombine(new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER)));
        }
    }

    /** s5-run6: resumed for the combine, it was told to wait for the review of another lane's snapshot instead. */
    @Test
    void aLanelessSessionIsOfferedTheCombineWhileAnotherLanesSnapshotAwaitsReview() throws Exception {
        fixture.awaitingReview("combine-other", "src/g.txt", "combine-reviewer");

        assertOfferedTheCombine(new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER)));
    }

    /** A combine's group never had a lane, so with every other group closed the project looked finished. */
    @Test
    void aLanelessSessionIsOfferedTheCombineWhenEveryOtherGroupIsClosed() throws Exception {
        PredictionEventStore store = fixture.store();
        WorkGroup group = store.workGroupProjection().group(fixture.authorLane.workGroupId()).orElseThrow();
        WorkGroup completed = new WorkGroup(group.workGroupId(), group.projectId(), group.goal(), group.acceptance(), group.version() + 1,
                WorkGroup.Status.COMPLETED, group.reviewPolicy());
        store.append(group.workGroupId(), PredictionEventType.WORK_GROUP_STATUS_CHANGED, fixture.identity.nodeId(), CollaborationCodec.encodeWorkGroup(completed),
                fixture.identity);

        assertOfferedTheCombine(new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER)));
    }

    /**
     * s5-run7: with every other group closed, an agent that announced ordinary work joined the combine's group, the
     * only active one. The combine then read as taken, so the agent shown it was refused, and every later lane and
     * snapshot landed in the group whose snapshots wait for the re-merge.
     */
    @Test
    void anOrdinaryLaneOpensOutsideTheCombinesGroupAndTheCombineIsStillTaken() throws Exception {
        PredictionEventStore store = fixture.store();
        WorkGroup group = store.workGroupProjection().group(fixture.authorLane.workGroupId()).orElseThrow();
        WorkGroup completed = new WorkGroup(group.workGroupId(), group.projectId(), group.goal(), group.acceptance(), group.version() + 1,
                WorkGroup.Status.COMPLETED, group.reviewPolicy());
        store.append(group.workGroupId(), PredictionEventType.WORK_GROUP_STATUS_CHANGED, fixture.identity.nodeId(), CollaborationCodec.encodeWorkGroup(completed),
                fixture.identity);
        assertOfferedTheCombine(new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER)));
        fixture.session("combine-other");

        WorkIntent ordinary = fixture.collaboration.announce(fixture.root, CombineFixture.PROVIDER, "combine-other", "change g", "g changed",
                List.of(ResourceSelector.pathExact("src/g.txt"))).intent();

        assertNotEquals(fixture.combine.workGroupId(), ordinary.workGroupId());
        assertOfferedTheCombine(new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER)));
        WorkIntent combineLane = fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, COMBINER, fixture.authorLane.intentId(),
                fixture.authorSnapshot.snapshotId()).intent();
        assertEquals(fixture.combine.workGroupId(), combineLane.workGroupId());
        assertEquals(fixture.combine.taskId(), combineLane.taskId());
    }

    @Test
    void theAuthorIsOfferedItsOwnWorkToCombineBecauseItsResultIsReviewedByAnotherAgent() throws Exception {
        fixture.authorLaneIntegrated();
        assertOfferedTheCombine(new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, CombineFixture.AUTHOR)));
    }

    @Test
    void theRunControllerIsOfferedTheCombineIdForEveryAgentOfThisComputerAndTheNextActionShowsTheSameCombine() throws Exception {
        var observation = new EventLogObserver(fixture.location).observe();

        assertEquals(List.of(fixture.combine.combineId()), observation.reviewsOffered(CombineFixture.PROVIDER, List.of(COMBINER)));
        assertEquals(List.of(fixture.combine.combineId()), observation.reviewsOffered(CombineFixture.PROVIDER, List.of(CombineFixture.AUTHOR)));
        assertEquals(List.of(), observation.reviewsOffered(CombineFixture.PROVIDER, List.of("never-connected")));
    }

    @Test
    void nothingIsOfferedOnceTheLaneIsTaken() throws Exception {
        fixture.session("combine-late");
        fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, COMBINER, fixture.authorLane.intentId(), fixture.authorSnapshot.snapshotId());

        var observation = new EventLogObserver(fixture.location).observe();
        assertEquals(List.of(), observation.reviewsOffered(CombineFixture.PROVIDER, List.of("combine-late")));
        AgentResponse late = new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, "combine-late"));
        assertFalse(late.toJson().contains("combineCandidates"), late.toJson());
    }

    @Test
    void theAgentResolvesTheMarkersAndFinishesTheLaneAndTheOrdinaryPassNeverMergesIt() throws Exception {
        WorkIntent lane = fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, COMBINER, fixture.authorLane.intentId(),
                fixture.authorSnapshot.snapshotId()).intent();
        Path worktree = Path.of(fixture.binding(COMBINER).worktreePath());
        Files.writeString(worktree.resolve(CombineFixture.FILE), "both\n");
        long advancesBefore = events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size();

        AgentResponse finished = new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(fixture.root, CombineFixture.PROVIDER, COMBINER, "combined both versions"));

        PredictionEventStore store = fixture.store();
        List<TaskSnapshotRecord> made = store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(fixture.combine.taskId())).toList();
        assertEquals(1, made.size(), "finishing created the combine's snapshot; the response was " + finished.toJson());
        TaskSnapshotRecord snapshot = made.getFirst();
        assertEquals(fixture.combine.base(), snapshot.baseCommit());
        assertEquals(lane.intentId(), snapshot.provenance().laneId());
        assertEquals(fixture.combine.workGroupId(), snapshot.provenance().workGroupId());
        assertEquals(List.of(CombineFixture.FILE), snapshot.changedPaths());
        assertEquals("both", TestGit.output(fixture.root, "show", snapshot.commitSha() + ":" + CombineFixture.FILE).trim());

        // The ordinary pass, run again, still leaves it for the re-merge.
        AgentResponse pass = new IntegrationOrchestrationService().orchestrateIntegration(fixture.root, fixture.store(), fixture.identity);
        assertFalse(pass.toJson().contains("CONTROL_BRANCH_ADVANCED"), pass.toJson());
        // Told to retry, the combining agent would only poll: it is told to wait for the re-merge instead.
        assertTrue(pass.toJson().contains("\"combinePending\":true"), pass.toJson());
        assertTrue(pass.toJson().contains("\"wait\""), pass.toJson());
        PredictionEventStore after = fixture.store();
        TaskCompletionState state = after.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(null);
        assertFalse(state == TaskCompletionState.INTEGRATED, "the ordinary pass integrated the combine's snapshot");
        assertEquals(advancesBefore, events(PredictionEventType.CONTROL_BRANCH_ADVANCED).size(), "no advance was recorded");
        assertTrue(events(PredictionEventType.CONTROL_BRANCH_ADVANCED).stream().noneMatch(event -> names(event, snapshot.snapshotId())));
        assertEquals(fixture.base, TestGit.output(fixture.root, "rev-parse", "HEAD"), "main did not move");
        assertEquals("other\n", Files.readString(fixture.root.resolve(CombineFixture.FILE)).replace("\r\n", "\n"));
    }

    @Test
    void anAgentCombinesAndFinishesWhileThisComputersMainIsTheOtherCopy() throws Exception {
        String ownMain = fixture.divergeThisCopyOfMain();
        String apart = "combine-apart";
        fixture.session(apart);
        fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, apart, fixture.authorLane.intentId(), fixture.authorSnapshot.snapshotId());
        AgentResponse ready = new AgentSessionService().ensureSession(new SessionResolutionRequest(fixture.root, CombineFixture.PROVIDER, apart, null, false));
        assertEquals(AgentStatus.READY, ready.status(), "s5-run8 answered WORKSPACE_STALE_DIRTY: " + ready.toJson());
        Path worktree = Path.of(fixture.binding(apart).worktreePath());
        Files.writeString(worktree.resolve(CombineFixture.FILE), "both\n");

        AgentResponse finished = new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(fixture.root, CombineFixture.PROVIDER, apart, "combined both versions"));

        List<TaskSnapshotRecord> made = fixture.store().taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(fixture.combine.taskId())).toList();
        assertEquals(1, made.size(), "finishing created the combine's snapshot; the response was " + finished.toJson());
        assertEquals(fixture.combine.base(), made.getFirst().baseCommit());
        assertEquals(List.of(CombineFixture.FILE), made.getFirst().changedPaths());
        assertEquals("both", TestGit.output(fixture.root, "show", made.getFirst().commitSha() + ":" + CombineFixture.FILE).trim());
        assertEquals(ownMain, TestGit.output(fixture.root, "rev-parse", "HEAD"), "this computer's main did not move");
    }

    private static boolean names(PredictionEvent event, String snapshotId) {
        try {
            return IntegrationAttemptPayload.decode(event.payload()).taskSnapshotIds().contains(snapshotId);
        } catch (IOException malformed) {
            return false;
        }
    }

    @Test
    void theAuthorsSnapshotRemainsTheOnlyIntegratedOneAndTheCombinesGroupStaysOpenForTheRemerge() throws Exception {
        fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, COMBINER, fixture.authorLane.intentId(), fixture.authorSnapshot.snapshotId());
        Path worktree = Path.of(fixture.binding(COMBINER).worktreePath());
        Files.writeString(worktree.resolve(CombineFixture.FILE), "both\n");
        new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(fixture.root, CombineFixture.PROVIDER, COMBINER, "combined both versions"));
        new IntegrationOrchestrationService().orchestrateIntegration(fixture.root, fixture.store(), fixture.identity);

        var projection = fixture.store().taskCompletionProjection();
        List<String> integrated = projection.allSnapshots().stream().filter(snapshot -> projection.snapshotState(snapshot.snapshotId()).orElse(null) == TaskCompletionState.INTEGRATED)
                .map(TaskSnapshotRecord::snapshotId).collect(Collectors.toList());
        assertEquals(List.of(fixture.authorSnapshot.snapshotId()), integrated);
    }
}
