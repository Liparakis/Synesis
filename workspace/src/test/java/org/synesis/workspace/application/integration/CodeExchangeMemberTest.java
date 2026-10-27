package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationRole;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.test.CodeLinkPair;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Code moves over Link between a member's clone and the leader's (ADR-0098 step 3), driven through the real session,
 * completion and integration services. The log is exchanged in process, and so is the code, between the two computers'
 * code sides ({@link CodeLinkPair}) as their Link session would carry it. A bare repository stands for a Git remote,
 * which only the tests of a mirror name in the settings.
 */
class CodeExchangeMemberTest {

    @TempDir
    Path temp;

    private Project leader;
    private Path remote;
    private String branch;
    private Path memberRoot;
    private ProjectLocation member;
    private NodeIdentity memberIdentity;
    private Path memberCoordination;
    private CodeLinkPair link;
    private final ProviderSessionBindingService bindings = new ProviderSessionBindingService();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(temp.resolve("leader"));
        leader = new Project(temp.resolve("leader"));
        remote = temp.resolve("code.git");
        TestGit.output(temp, "init", "--bare", remote.toString());
        leader.git("remote", "add", "origin", remote.toString());
        branch = leader.git("rev-parse", "--abbrev-ref", "HEAD");
        leader.git("push", "origin", "HEAD:refs/heads/" + branch);
        TestGit.run(remote, "symbolic-ref", "HEAD", "refs/heads/" + branch);

        memberRoot = temp.resolve("member");
        TestGit.run(temp, "clone", remote.toString(), memberRoot.toString());
        TestGit.run(memberRoot, "config", "user.name", "Member");
        TestGit.run(memberRoot, "config", "user.email", "member@example.invalid");
        var initialized = new ProjectApplicationService().initReplica(memberRoot, leader.location.identity(), false);
        member = initialized.location();
        memberIdentity = new IdentityBootstrap(member.profile().resolve("link")).loadOrCreate().identity();
        memberCoordination = member.coordinationDirectory();
    }

    @AfterEach
    void unregister() throws Exception {
        ReplicationRole.register(memberCoordination, ReplicationRole.leader());
        if (link != null) {
            link.close();
        }
    }

    /** Both computers get their replication settings, with no Git remote; the member's log proposes to the leader's in process. */
    private void replicate() throws Exception {
        replicate(false, true);
    }

    /**
     * As {@link #replicate()}; with {@code mirror} both settings also name the bare repository as a mirror. The two
     * computers' code sides are joined as their Link session would join them, and the session is up when
     * {@code connected}.
     */
    private void replicate(boolean mirror, boolean connected) throws Exception {
        ReplicationSettings leaderSettings = ReplicationSettings.leader(Set.of(memberIdentity.nodeId()));
        ReplicationSettings memberSettings = ReplicationSettings.member(leader.identity.nodeId());
        (mirror ? leaderSettings.withCodeRemote("origin") : leaderSettings).write(leader.root.resolve(".synesis/coordination"));
        (mirror ? memberSettings.withCodeRemote("origin") : memberSettings).write(memberCoordination);
        link = new CodeLinkPair(leader.location.projectId(), leader.root, leader.root.resolve(".synesis/coordination"), leader.identity.nodeId(),
                memberRoot, memberCoordination, memberIdentity.nodeId());
        if (!connected) {
            link.disconnect();
        }
        ReplicationRole.register(memberCoordination, ReplicationRole.memberOf(leader.identity.nodeId()));
        ProviderTestSupport.install(member, "codex");
    }

    private PredictionEventStore memberStore() throws Exception {
        return new PredictionEventStore(memberCoordination, leader.location.projectId());
    }

    /** What the member appended to its own log, in order (ADR-0098 step 4: a member appends locally). */
    private List<PredictionEventType> memberAppended() throws Exception {
        List<PredictionEventType> types = new java.util.ArrayList<>();
        for (byte[] encoded : memberStore().entriesAfter(0, 10_000, Integer.MAX_VALUE)) {
            types.add(PredictionEvent.decode(encoded).type());
        }
        return types;
    }

    /** Each computer receives byte-identical copies of the other's log, as their log exchange would bring them. */
    private void exchangeLogs() throws Exception {
        PredictionEventStore leaderStore = leader.store();
        PredictionEventStore memberStore = memberStore();
        for (int round = 0; round < 3; round++) {
            long memberHeld = leaderStore.logHeads().getOrDefault(memberIdentity.nodeId(), 0L);
            leaderStore.placeCopies(memberIdentity.nodeId(), memberStore.entriesAfter(memberHeld, 10_000, Integer.MAX_VALUE));
            long leaderHeld = memberStore.logHeads().getOrDefault(leader.identity.nodeId(), 0L);
            memberStore.placeCopies(leader.identity.nodeId(), leaderStore.entriesAfter(leaderHeld, 10_000, Integer.MAX_VALUE));
        }
    }

    private Path startLane(String connection, String path) throws Exception {
        return startLane(connection, path, null);
    }

    private Path startLane(String connection, String path, java.util.UUID workGroup) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(memberRoot, "codex", connection, null, false));
        var binding = bindings.find(member, "codex", connection).orElseThrow();
        assertNotNull(binding.worktreePath(), binding.toString());
        bindings.verifyWorkspaceTrust(member, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
        new WorkspaceCollaborationService().announce(memberRoot, "codex", connection, "Write " + path, "The file exists", List.of(ResourceSelector.pathExact(path)), workGroup);
        Path worktree = Path.of(binding.worktreePath());
        Files.createDirectories(worktree.resolve(path).getParent());
        Files.writeString(worktree.resolve(path), "written on the member's computer\n");
        return worktree;
    }

    private AgentResponse finish(String connection) {
        return new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(memberRoot, "codex", connection, "Wrote the file"));
    }

    private String remoteRefs() throws IOException {
        return TestGit.output(remote, "for-each-ref", "--format=%(refname)", "refs/synesis");
    }

    private String memberHead() throws IOException {
        return TestGit.output(memberRoot, "rev-parse", "HEAD");
    }

    @Test
    void aMembersLaneIsMergedByTheMembersOwnPassAndTheNextLaneStartsOnTheIntegratedHead() throws Exception {
        replicate();
        String branchBefore = TestGit.output(remote, "rev-parse", "refs/heads/" + branch);
        String leaderHead = leader.head();
        startLane("m-1", "src/a.txt");

        // Completing a task runs the member's own merge pass on the member's checkout (ADR-0098 step 5).
        AgentResponse finished = finish("m-1");

        assertTrue(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED), finished.toJson());
        assertTrue(memberAppended().contains(PredictionEventType.TASK_SNAPSHOT_CREATED), finished.toJson());
        assertTrue(memberAppended().contains(PredictionEventType.TASK_INTEGRATED), finished.toJson());
        assertEquals("", remoteRefs(), "the member pushed nothing: its refs stay in its clone");
        assertEquals(0, leader.count(PredictionEventType.COMPLETION_PREPARED), "nothing reached the leader before the logs were exchanged");
        String snapshotId = memberStore().taskCompletionProjection().allSnapshots().getFirst().snapshotId();
        String snapshotCommit = memberStore().taskCompletionProjection().allSnapshots().getFirst().commitSha();
        assertEquals(snapshotCommit, TestGit.output(memberRoot, "rev-parse", "refs/synesis/snapshots/" + snapshotId));
        assertEquals(TaskCompletionState.INTEGRATED, memberStore().taskCompletionProjection().snapshotState(snapshotId).orElseThrow());
        TestGit.output(memberRoot, "cat-file", "-e", "HEAD:src/a.txt");
        assertFalse(memberHead().equals(leaderHead), "the member's main moved on its own checkout");
        assertEquals(branchBefore, TestGit.output(remote, "rev-parse", "refs/heads/" + branch), "with no mirror nothing is pushed");

        // The leader's pass leaves the member's lane to the member's computer.
        exchangeLogs();
        assertFalse(commitInLeaderClone(snapshotCommit), "the leader's clone does not hold the member's commit");
        AgentResponse leaderPass = leader.pump();
        assertFalse(leader.headHas("src/a.txt"), leaderPass.toJson());
        assertEquals(leaderHead, leader.head(), "the leader's main did not follow yet");
        assertEquals(0, leader.store().events().stream().filter(event -> event.type() == PredictionEventType.TASK_INTEGRATED
                && event.actorNodeId().equals(leader.identity.nodeId())).count(), "the leader's own log holds no integration of the member's lane");

        // The merged lane was the group's only one, so the group completed; new work starts a new group.
        Path next = startLane("m-2", "src/b.txt", java.util.UUID.randomUUID());

        assertEquals(memberHead(), bindings.find(member, "codex", "m-2").orElseThrow().baseCommit(), "the new lane is based on the integrated head");
        assertTrue(Files.exists(next.resolve("src/a.txt")), "the new lane sees the integrated work");
    }

    @Test
    void theOwnersCopyTakesTheMembersAdvancedMainOverLinkAfterTheLogsAreExchanged() throws Exception {
        replicate();
        String leaderHead = leader.head();
        startLane("m-1", "src/a.txt");
        finish("m-1");
        String memberMain = memberHead();
        assertFalse(memberMain.equals(leaderHead), "the member's main moved on its own checkout");
        assertFalse(commitInLeaderClone(memberMain), "the leader's clone does not hold the member's commit yet");
        java.util.concurrent.CompletableFuture<String> diverged = new java.util.concurrent.CompletableFuture<>();
        link.leader.whenDiverged(diverged::complete);
        exchangeLogs();

        link.leader.mainMayHaveMoved();

        awaitLeaderHead(memberMain);
        assertTrue(leader.headHas("src/a.txt"), "the owner's checkout holds the member's work");
        assertFalse(diverged.isDone(), "a copy that is behind is not diverged");
    }

    /**
     * ADR-0098 step 5 item 46: a pass running when the pull asks for the other computer's main is waited for, not
     * skipped until the next pull; passes that run back to back otherwise starve the meeting for ever.
     */
    @Test
    void theOwnersCopyTakesTheMembersMainOnceTheRunningPassEndsWithNoSecondPull() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");
        finish("m-1");
        String memberMain = memberHead();
        exchangeLogs();
        String before = leader.head();

        try (var runningPass = org.synesis.coordination.persistence.ProjectIntegrationLock.tryAcquire(leader.store().rootDirectory())) {
            assertNotNull(runningPass);
            link.leader.mainMayHaveMoved();
            Thread.sleep(1500);
            assertEquals(before, leader.head(), "the main is not taken while a pass runs");
        }

        awaitLeaderHead(memberMain);
    }

    @Test
    void theOwnersCopyKeepsItsOwnCommitWhenTheMembersAdvancedMainDiverged() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");
        finish("m-1");
        String memberMain = memberHead();
        leader.git("commit", "--allow-empty", "-m", "the owner's own change");
        String ownerHead = leader.head();
        java.util.concurrent.CompletableFuture<String> diverged = new java.util.concurrent.CompletableFuture<>();
        link.leader.whenDiverged(diverged::complete);
        exchangeLogs();

        link.leader.mainMayHaveMoved();

        assertEquals(memberMain, diverged.get(60, java.util.concurrent.TimeUnit.SECONDS), "the member's head is reported as diverged");
        assertEquals(ownerHead, leader.head(), "the owner's main did not move");
        assertFalse(leader.headHas("src/a.txt"), "the member's work was not merged into the owner's checkout");
    }

    private void awaitLeaderHead(String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (!expected.equals(leader.head()) && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertEquals(expected, leader.head(), "the owner's checkout took the member's advanced main");
    }

    @Test
    void aMembersCheckoutKeepsItsOwnIntegratedHeadWhenTheOtherComputersLogIsExchanged() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");
        finish("m-1");
        String integrated = memberHead();
        exchangeLogs();

        link.member.mainMayHaveMoved();
        Thread.sleep(500);

        // Following the other computer's main is a later piece of step 5; the member's own main must not regress.
        assertEquals(integrated, memberHead());
    }

    @Test
    void aMembersPassWithAMirrorGivesTheMirrorTheControlBranch() throws Exception {
        replicate(true, true);
        startLane("m-1", "src/a.txt");

        AgentResponse finished = finish("m-1");

        assertTrue(memberAppended().contains(PredictionEventType.TASK_INTEGRATED), finished.toJson());
        assertEquals(memberHead(), TestGit.output(remote, "rev-parse", "refs/heads/" + branch), "the mirror received the control branch");
    }

    @Test
    void aSnapshotMadeOnTheLeadersComputerReachesTheMembersReviewerOverLink() throws Exception {
        replicate();
        var lane = leader.announce("agt_leader", "src/review.txt");
        String commit = leader.publish(lane, "snap_review", 1, "src/review.txt", "for review\n");
        exchangeLogs();
        assertFalse(commitIn(memberRoot, commit));

        org.synesis.workspace.application.control.CodeExchange.ensureCommit(memberCoordination, member.projectId(), memberRoot, commit);

        assertEquals(commit, TestGit.output(memberRoot, "rev-parse", "refs/synesis/snapshots/snap_review"),
                "the reviewer's checkout holds the commit under the snapshot ref the log names");
    }

    @Test
    void aMirrorThatRefusesThePushStopsTheCompletionBeforeAnythingIsAppended() throws Exception {
        replicate(true, true);
        startLane("m-1", "src/a.txt");
        TestGit.run(memberRoot, "remote", "set-url", "origin", temp.resolve("gone.git").toString());
        int appendedBefore = memberAppended().size();

        AgentResponse finished = finish("m-1");

        assertEquals(AgentStatus.BLOCKED, finished.status(), finished.toJson());
        assertTrue(finished.toJson().contains("CODE_REMOTE_UNAVAILABLE"), finished.toJson());
        assertFalse(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED));
        assertFalse(memberAppended().contains(PredictionEventType.TASK_SNAPSHOT_CREATED));
        assertEquals(appendedBefore, memberAppended().size(), "no entry was appended");
        exchangeLogs();
        assertEquals(0, leader.count(PredictionEventType.COMPLETION_PREPARED));
    }

    @Test
    void aMemberThatCanReachNeitherTheLeaderNorTheMirrorStartsItsLaneFromItsOwnMainAndFollowsOnceItCan() throws Exception {
        TestGit.run(memberRoot, "remote", "set-url", "origin", temp.resolve("gone.git").toString());
        // Neither the leader over Link nor the mirror can be reached, as when the two computers are partitioned.
        replicate(true, false);
        String ownMain = memberHead();
        leader.git("commit", "--allow-empty", "-m", "main moved on the owner's computer");
        String leaderMain = leader.head();

        AgentResponse ensured = new AgentSessionService().ensureSession(new SessionResolutionRequest(memberRoot, "codex", "m-1", null, false));

        assertEquals(AgentStatus.READY, ensured.status(), ensured.toJson());
        ProviderSessionBindingService.Binding cutOff = bindings.find(member, "codex", "m-1").orElseThrow();
        assertEquals(ownMain, cutOff.baseCommit(), "the lane starts from this computer's own main");
        assertEquals(ownMain, TestGit.output(Path.of(cutOff.worktreePath()), "rev-parse", "HEAD"));
        assertTrue(new WorkspaceReadinessService().assess(member, "codex", "m-1").ready(), "every operation finds the workspace ready");
        Map<String, Object> marker = laneMarker(cutOff);
        assertEquals("LOCAL_MAIN", marker.get("baseFrom"), marker.toString());
        assertTrue(String.valueOf(marker.get("baseFromReason")).contains("git fetch origin"), "the mirror's reason is kept: " + marker);

        link.reconnect();
        new AgentSessionService().ensureSession(new SessionResolutionRequest(memberRoot, "codex", "m-2", null, false));

        ProviderSessionBindingService.Binding connected = bindings.find(member, "codex", "m-2").orElseThrow();
        assertEquals(leaderMain, connected.baseCommit(), "once the leader answers, a new lane starts from its main");
        assertNull(laneMarker(connected).get("baseFrom"), "a lane based on the followed main is not marked");
    }

    @Test
    void aMemberCutOffFromTheLeaderRunsItsLaneAndMergesItOntoItsOwnMain() throws Exception {
        replicate(false, false);
        startLane("m-1", "src/a.txt");

        AgentResponse finished = finish("m-1");

        assertTrue(memberAppended().contains(PredictionEventType.TASK_INTEGRATED), finished.toJson());
        TestGit.output(memberRoot, "cat-file", "-e", "HEAD:src/a.txt");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> laneMarker(ProviderSessionBindingService.Binding binding) throws IOException {
        Path marker = Path.of(binding.worktreePath()).resolve(".synesis/local/workspace-binding.json");
        return (Map<String, Object>) ProviderJson.parse(Files.readString(marker));
    }

    @Test
    void aMemberWithAMirrorHandsInThroughItAsWell() throws Exception {
        replicate(true, true);
        startLane("m-1", "src/a.txt");

        AgentResponse finished = finish("m-1");

        // Since step 4 the member appends locally, so the order of push and append is not observed here; the
        // mirror refusing the push stopping the append is the test above.
        assertTrue(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED), finished.toJson());
        assertTrue(memberAppended().contains(PredictionEventType.TASK_SNAPSHOT_CREATED), finished.toJson());
        assertTrue(remoteRefs().contains("refs/synesis/prepared/"), "the prepared commit is on the mirror");
        assertTrue(remoteRefs().contains("refs/synesis/snapshots/"), "the snapshot commit is on the mirror");
    }

    @Test
    void aProjectWithNoReplicationSettingsPushesAndFetchesNothing() throws Exception {
        ProviderTestSupport.install(member, "codex");
        String branchBefore = TestGit.output(remote, "rev-parse", "refs/heads/" + branch);
        startLane("m-1", "src/a.txt");

        AgentResponse finished = finish("m-1");

        assertEquals(AgentStatus.COMPLETED, finished.status(), finished.toJson());
        assertEquals("", remoteRefs(), "no snapshot or prepared ref reached the remote");
        assertEquals(branchBefore, TestGit.output(remote, "rev-parse", "refs/heads/" + branch), "the control branch was not pushed");
    }

    private boolean commitInLeaderClone(String commit) {
        return commitIn(leader.root, commit);
    }

    private static boolean commitIn(Path repository, String commit) {
        try {
            TestGit.output(repository, "cat-file", "-e", commit + "^{commit}");
            return true;
        } catch (IOException absent) {
            return false;
        }
    }
}
