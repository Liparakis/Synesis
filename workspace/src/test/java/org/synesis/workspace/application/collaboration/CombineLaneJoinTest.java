package org.synesis.workspace.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.CombinePayload;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.test.TestGit;

/**
 * Taking a combine lane (ADR-0098 step 5, designer item 22): the worktree holds both versions with Git's markers, who
 * may take the lane, and what the lane announced says.
 */
final class CombineLaneJoinTest {

    private static final String COMBINER = "combine-combiner";

    @TempDir
    Path temp;

    private CombineFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = CombineFixture.create(temp);
    }

    private static String git(Path dir, String... args) throws IOException {
        return TestGit.output(dir, args);
    }

    private void materialize(IntegrationWorkspaceService integration, Path worktree, CombinePayload combine) throws Exception {
        ProviderSessionBindingService.Binding binding = fixture.binding(COMBINER);
        integration.materializeCombineRepresentation(fixture.root, worktree, binding.branch(), binding.baseCommit(), combine);
    }

    private ClaimResult join(String connection) throws Exception {
        return fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, connection, fixture.authorLane.intentId(), fixture.authorSnapshot.snapshotId());
    }

    @Test
    void theCombineWaitsAndIsOfferedToAnAgentThatWroteNoneOfIt() throws Exception {
        var store = fixture.store();
        String node = fixture.identity.nodeId();
        assertTrue(CombineLaneJoin.waiting(store, fixture.combine));
        fixture.session(COMBINER);
        assertEquals(List.of(fixture.combine.combineId()), CombineLaneJoin.offeredTo(store, List.of(fixture.participant(COMBINER)), node).stream()
                .map(CombinePayload::combineId).toList());
        assertEquals(List.of(), CombineLaneJoin.offeredTo(store, List.of(fixture.participant(CombineFixture.AUTHOR)), node));
        assertEquals(List.of(), CombineLaneJoin.offeredTo(store, List.of(), node));
        assertEquals(List.of(fixture.participant(CombineFixture.AUTHOR)), CombineLaneJoin.authors(store, fixture.combine));
        var described = CombineLaneJoin.describe(store, fixture.combine);
        assertEquals(fixture.combine.combineId(), described.get("combineId"));
        assertEquals(List.of(CombineFixture.FILE), described.get("files"));
        @SuppressWarnings("unchecked")
        var task = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) described.get("ensureSession")).get("task");
        assertEquals(fixture.authorLane.intentId().toString(), task.get("repairIntentId"));
        assertEquals(fixture.authorSnapshot.snapshotId(), task.get("repairSnapshotId"));
    }

    @Test
    void theMaterializedWorktreeHoldsBothVersionsAtTheBaseWithAnIndexThatIsCleanAgainstIt() throws Exception {
        Path worktree = Path.of(fixture.session(COMBINER).worktreePath());
        IntegrationWorkspaceService integration = new IntegrationWorkspaceService();

        materialize(integration, worktree, fixture.combine);

        assertEquals(fixture.base, git(worktree, "rev-parse", "HEAD"));
        String content = Files.readString(worktree.resolve(CombineFixture.FILE)).replace("\r\n", "\n");
        assertTrue(content.contains("<<<<<<<") && content.contains("=======") && content.contains(">>>>>>>"), content);
        assertTrue(content.contains("other") && content.contains("author"), content);
        assertEquals("", git(worktree, "diff", "--cached", "--name-only"), "the index is the base's");
        assertTrue(git(worktree, "status", "--porcelain", "--untracked-files=no").contains(CombineFixture.FILE), "the file is modified in the working tree");
        assertThrows(IOException.class, () -> git(worktree, "rev-parse", "-q", "--verify", "MERGE_HEAD"), "no merge is left in progress");
        Path marker = worktree.resolve(".synesis/local/combine-materialization.txt");
        assertEquals(fixture.combine.combineId() + "\n" + fixture.combine.base() + "\n" + fixture.combine.theirs() + "\n", Files.readString(marker));
    }

    @Test
    void materializingAgainLeavesTheAgentsEditsAlone() throws Exception {
        Path worktree = Path.of(fixture.session(COMBINER).worktreePath());
        IntegrationWorkspaceService integration = new IntegrationWorkspaceService();
        materialize(integration, worktree, fixture.combine);
        Files.writeString(worktree.resolve(CombineFixture.FILE), "resolved\n");

        materialize(integration, worktree, fixture.combine);

        assertEquals("resolved\n", Files.readString(worktree.resolve(CombineFixture.FILE)).replace("\r\n", "\n"));
        assertEquals(fixture.base, git(worktree, "rev-parse", "HEAD"));
    }

    @Test
    void aDirtyWorktreeIsRefusedAndNothingIsMoved() throws Exception {
        ProviderSessionBindingService.Binding binding = fixture.session(COMBINER);
        Path worktree = Path.of(binding.worktreePath());
        Files.writeString(worktree.resolve("unowned.txt"), "must not be adopted\n");
        String before = git(worktree, "rev-parse", "HEAD");

        IOException dirty = assertThrows(IOException.class, () -> materialize(new IntegrationWorkspaceService(), worktree, fixture.combine));

        assertTrue(dirty.getMessage().startsWith("COMBINE_TARGET_DIRTY"), dirty.getMessage());
        assertEquals(before, git(worktree, "rev-parse", "HEAD"));
        assertFalse(Files.exists(worktree.resolve(".synesis/local/combine-materialization.txt")));
        assertEquals("must not be adopted\n", Files.readString(worktree.resolve("unowned.txt")).replace("\r\n", "\n"));
    }

    @Test
    void aWorktreeMaterializedForAnotherCombineIsRefused() throws Exception {
        Path worktree = Path.of(fixture.session(COMBINER).worktreePath());
        IntegrationWorkspaceService integration = new IntegrationWorkspaceService();
        materialize(integration, worktree, fixture.combine);
        CombinePayload other = new CombinePayload("cb-another", fixture.combine.remergeId(), fixture.combine.otherHead(), fixture.combine.ownHead(),
                fixture.combine.base(), fixture.combine.theirs(), fixture.combine.snapshotIds());

        IOException mismatch = assertThrows(IOException.class, () -> materialize(integration, worktree, other));

        assertEquals("COMBINE_MATERIALIZATION_MARKER_MISMATCH", mismatch.getMessage());
    }

    @Test
    void theControlCheckoutIsNeverMaterialized() {
        IOException refused = assertThrows(IOException.class,
                () -> new IntegrationWorkspaceService().materializeCombineRepresentation(fixture.root, fixture.root, "main", fixture.base, fixture.combine));
        assertEquals("COMBINE_WORKTREE_INVALID", refused.getMessage());
    }

    @Test
    void theAuthorIsRefusedAndNothingIsAnnounced() throws Exception {
        int intentsBefore = fixture.store().collaborationProjection().activeIntents().size();

        IOException refused = assertThrows(IOException.class, () -> join(CombineFixture.AUTHOR));

        assertEquals("COMBINE_BY_AUTHOR_NOT_ALLOWED", refused.getMessage());
        assertEquals(intentsBefore, fixture.store().collaborationProjection().activeIntents().size());
        assertTrue(CombineLaneJoin.waiting(fixture.store(), fixture.combine), "the combine still waits");
        assertEquals(Optional.of("COMBINE_BY_AUTHOR_NOT_ALLOWED"),
                CombineLaneJoin.refusal(fixture.store(), fixture.combine, fixture.participant(CombineFixture.AUTHOR), fixture.identity.nodeId()));
    }

    @Test
    void aCombineOfLanesAnotherComputerIntroducedIsNotThisComputers() throws Exception {
        fixture.session(COMBINER);

        assertEquals(Optional.of("COMBINE_NOT_THIS_COMPUTERS"),
                CombineLaneJoin.refusal(fixture.store(), fixture.combine, fixture.participant(COMBINER), "node-that-did-not-introduce-the-authors"));
        assertEquals(List.of(), CombineLaneJoin.offeredTo(fixture.store(), List.of(fixture.participant(COMBINER)), "node-that-did-not-introduce-the-authors"));
        assertEquals(Optional.empty(), CombineLaneJoin.refusal(fixture.store(), fixture.combine, fixture.participant(COMBINER), fixture.identity.nodeId()));
    }

    @Test
    void aCombineNamingAnUnknownSnapshotIsRefused() throws Exception {
        fixture.session(COMBINER);
        CombinePayload unknown = new CombinePayload("cb-unknown", "r", "o", "w", fixture.base, fixture.combine.theirs(), List.of("snap-never-seen"));

        assertEquals(Optional.of("COMBINE_SNAPSHOT_NOT_FOUND"), CombineLaneJoin.refusal(fixture.store(), unknown, fixture.participant(COMBINER), fixture.identity.nodeId()));
    }

    @Test
    void joiningThroughTheRepairJoinAnnouncesTheCombineLaneInThePrescribedGroupAndTaskWithBothVersionsInTheWorktree() throws Exception {
        ProviderSessionBindingService.Binding binding = fixture.session(COMBINER);

        ClaimResult joined = join(COMBINER);

        assertTrue(joined.acquired(), "conflicts: " + joined.conflicts() + " active: " + fixture.store().collaborationProjection().activeIntents());
        WorkIntent lane = joined.intent();
        assertEquals(fixture.combine.taskId(), lane.taskId());
        assertEquals(fixture.combine.workGroupId(), lane.workGroupId());
        assertEquals(fixture.base, lane.baseCommit());
        assertEquals(WorkIntent.Role.PRODUCER, lane.role());
        assertEquals(WorkIntent.Status.ANNOUNCED, lane.status());
        assertEquals(fixture.participant(COMBINER), lane.participant());
        assertEquals(List.of(ResourceSelector.pathExact(CombineFixture.FILE)), lane.selectors());
        Path worktree = Path.of(binding.worktreePath());
        assertEquals(fixture.base, git(worktree, "rev-parse", "HEAD"));
        assertTrue(Files.readString(worktree.resolve(CombineFixture.FILE)).contains("<<<<<<<"));
        var store = fixture.store();
        assertTrue(store.collaborationProjection().intent(lane.intentId()).isPresent());
        assertFalse(CombineLaneJoin.waiting(store, fixture.combine), "the combine's group now holds a lane");
    }

    @Test
    void aRepeatedJoinReturnsTheSameLaneAndAnnouncesNothingMore() throws Exception {
        fixture.session(COMBINER);
        WorkIntent first = join(COMBINER).intent();
        int events = fixture.store().events().size();

        ClaimResult again = join(COMBINER);

        assertTrue(again.acquired());
        assertEquals(first.intentId(), again.intent().intentId());
        assertEquals(events, fixture.store().events().size());
    }

    @Test
    void aSecondAgentIsRefusedOnceAnotherLaneWorksInTheGroup() throws Exception {
        fixture.session(COMBINER);
        fixture.session("combine-late");
        join(COMBINER);

        assertEquals(Optional.empty(), CombineLaneJoin.openFor(fixture.store(), fixture.authorSnapshot.snapshotId(), fixture.binding("combine-late").sessionId()),
                "the late session is shown no open combine");
        IOException taken = assertThrows(IOException.class, () -> join("combine-late"));
        assertEquals("COMBINE_TAKEN_OR_CLOSED", taken.getMessage(), "s5-run7: the refusal named the combined lane's intent as not found");
        assertEquals(List.of(), CombineLaneJoin.offeredTo(fixture.store(), List.of(fixture.participant("combine-late")), fixture.identity.nodeId()));
        assertNotEquals(Optional.empty(), CombineLaneJoin.openFor(fixture.store(), fixture.authorSnapshot.snapshotId(), fixture.binding(COMBINER).sessionId()),
                "the taker still finds its own lane");
    }

    @Test
    void anOrdinaryLaneNamingTheCombinesGroupIsRefusedAndTheCombineStillWaits() throws Exception {
        fixture.session("combine-other");
        int eventsBefore = fixture.store().events().size();

        IOException refused = assertThrows(IOException.class, () -> fixture.collaboration.announce(fixture.root, CombineFixture.PROVIDER, "combine-other", "change g",
                "g changed", List.of(ResourceSelector.pathExact("src/g.txt")), fixture.combine.workGroupId()));

        assertEquals("COMBINE_GROUP_HOLDS_ONLY_ITS_COMBINE_LANE", refused.getMessage());
        assertEquals(eventsBefore, fixture.store().events().size());
        assertTrue(CombineLaneJoin.waiting(fixture.store(), fixture.combine));
    }

    @Test
    void theCombineLanesWorktreeIsReadyAtTheCombinesBaseWhileThisComputersMainIsElsewhere() throws Exception {
        String ownMain = fixture.divergeThisCopyOfMain();
        ProviderSessionBindingService.Binding before = fixture.session(COMBINER);
        assertEquals(ownMain, before.baseCommit());
        assertTrue(join(COMBINER).acquired());

        AgentResponse again = new AgentSessionService().ensureSession(new SessionResolutionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER, null, false));

        assertEquals(AgentStatus.READY, again.status(), "s5-run8 answered WORKSPACE_STALE_DIRTY: " + again.toJson());
        ProviderSessionBindingService.Binding after = fixture.binding(COMBINER);
        assertEquals(before.sessionId(), after.sessionId());
        assertEquals(before.worktreePath(), after.worktreePath());
        assertEquals(before.branch(), after.branch());
        assertEquals(fixture.base, after.baseCommit(), "the worktree is judged against the combine's base");
        Path worktree = Path.of(after.worktreePath());
        assertEquals("refs/heads/" + before.branch(), git(worktree, "symbolic-ref", "HEAD"), "the worktree stays on its session's branch");
        assertEquals(fixture.base, git(worktree, "rev-parse", "HEAD"));
        assertTrue(Files.readString(worktree.resolve(CombineFixture.FILE)).contains("<<<<<<<"), "the markers stay for the agent to resolve");
        WorkspaceReadinessService readiness = new WorkspaceReadinessService();
        ReadinessResult reads = readiness.assess(fixture.location, CombineFixture.PROVIDER, COMBINER);
        assertTrue(reads.ready(), "reads and commands: " + reads.internalReason());
        ReadinessResult edits = readiness.assessMutation(fixture.location, CombineFixture.PROVIDER, COMBINER, CombineFixture.FILE);
        assertTrue(edits.ready(), "edits of the claimed file: " + edits.internalReason());
        assertEquals(ownMain, git(fixture.root, "rev-parse", "HEAD"), "this computer's main did not move");
    }

    @Test
    void aWorktreeWithCommitsOfItsOwnIsNotMovedAndNothingIsAnnounced() throws Exception {
        Path worktree = Path.of(fixture.session(COMBINER).worktreePath());
        Files.writeString(worktree.resolve("src/mine.txt"), "mine\n");
        git(worktree, "add", "src/mine.txt");
        git(worktree, "commit", "-q", "-m", "a commit of the session's own");
        String head = git(worktree, "rev-parse", "HEAD");
        int eventsBefore = fixture.store().events().size();

        IOException refused = assertThrows(IOException.class, () -> join(COMBINER));

        assertEquals("COMBINE_WORKTREE_HAS_COMMITS", refused.getMessage());
        assertEquals(head, git(worktree, "rev-parse", "HEAD"));
        assertEquals("refs/heads/" + fixture.binding(COMBINER).branch(), git(worktree, "symbolic-ref", "HEAD"));
        assertEquals(eventsBefore, fixture.store().events().size());
        assertTrue(CombineLaneJoin.waiting(fixture.store(), fixture.combine));
    }

    @Test
    void aJoinRefusedForADirtyWorktreeLeavesTheSessionAsItWas() throws Exception {
        String ownMain = fixture.divergeThisCopyOfMain();
        Path worktree = Path.of(fixture.session(COMBINER).worktreePath());
        Files.writeString(worktree.resolve("unowned.txt"), "must not be adopted\n");

        IOException refused = assertThrows(IOException.class, () -> join(COMBINER));

        assertTrue(refused.getMessage().startsWith("COMBINE_TARGET_DIRTY"), refused.getMessage());
        assertEquals(ownMain, fixture.binding(COMBINER).baseCommit(), "the binding still names this computer's main");
        AgentResponse again = new AgentSessionService().ensureSession(new SessionResolutionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER, null, false));
        assertEquals(AgentStatus.READY, again.status(), again.toJson());
        assertEquals(worktree.toString(), fixture.binding(COMBINER).worktreePath());
        assertTrue(CombineLaneJoin.waiting(fixture.store(), fixture.combine));
    }

    @Test
    void aSessionWhoseCombineLaneEndedIsJudgedAgainstThisComputersMainAgain() throws Exception {
        String ownMain = fixture.divergeThisCopyOfMain();
        fixture.session(COMBINER);
        join(COMBINER);
        fixture.collaboration.release(fixture.root, CombineFixture.PROVIDER, COMBINER);

        AgentResponse again = new AgentSessionService().ensureSession(new SessionResolutionRequest(fixture.root, CombineFixture.PROVIDER, COMBINER, null, false));

        assertEquals(AgentStatus.READY, again.status(), again.toJson());
        assertEquals(ownMain, fixture.binding(COMBINER).baseCommit());
        assertEquals(ownMain, git(Path.of(fixture.binding(COMBINER).worktreePath()), "rev-parse", "HEAD"));
    }

    @Test
    void theJoinInDirectFormNamesTakenOrClosedWhenAnotherLaneWorksInTheGroup() throws Exception {
        ProviderSessionBindingService.Binding late = fixture.session("combine-late");
        fixture.session(COMBINER);
        join(COMBINER);

        IOException taken = assertThrows(IOException.class, () -> CombineLaneJoin.join(fixture.store(), fixture.identity, fixture.location, late,
                fixture.participant("combine-late"), fixture.combine));

        assertEquals("COMBINE_TAKEN_OR_CLOSED", taken.getMessage());
    }
}
