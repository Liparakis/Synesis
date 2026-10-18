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
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
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

    @Test
    void aMembersLaneTravelsOverLinkAndTheNextLaneStartsOnTheIntegratedHead() throws Exception {
        replicate();
        String branchBefore = TestGit.output(remote, "rev-parse", "refs/heads/" + branch);
        startLane("m-1", "src/a.txt");

        AgentResponse finished = finish("m-1");

        assertTrue(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED), finished.toJson());
        assertTrue(memberAppended().contains(PredictionEventType.TASK_SNAPSHOT_CREATED), finished.toJson());
        assertEquals("", remoteRefs(), "the member pushed nothing: its refs stay in its clone");
        assertEquals(0, leader.count(PredictionEventType.COMPLETION_PREPARED), "nothing reached the leader before the logs were exchanged");
        exchangeLogs();
        String snapshotId = leader.store().taskCompletionProjection().allSnapshots().getFirst().snapshotId();
        String snapshotCommit = leader.store().taskCompletionProjection().allSnapshots().getFirst().commitSha();
        assertEquals(snapshotCommit, TestGit.output(memberRoot, "rev-parse", "refs/synesis/snapshots/" + snapshotId));
        assertFalse(commitInLeaderClone(snapshotCommit), "the leader's clone does not hold the member's commit yet");

        // After integrating, the leader releases the member's lane: its own entry, which may act for any participant.
        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.COMPLETED, pass.status(), pass.toJson());
        assertEquals(TaskCompletionState.INTEGRATED, leader.state(snapshotId));
        assertTrue(leader.headHas("src/a.txt"));
        assertEquals(branchBefore, TestGit.output(remote, "rev-parse", "refs/heads/" + branch), "with no mirror nothing is pushed");

        // The merged lane was the group's only one, so the group completed; new work starts a new group.
        exchangeLogs();
        Path next = startLane("m-2", "src/b.txt", java.util.UUID.randomUUID());

        assertEquals(leader.head(), TestGit.output(memberRoot, "rev-parse", "HEAD"), "the member's checkout followed the leader");
        assertEquals(leader.head(), bindings.find(member, "codex", "m-2").orElseThrow().baseCommit(), "the new lane is based on the integrated head");
        assertTrue(Files.exists(next.resolve("src/a.txt")), "the new lane sees the integrated work");
    }

    @Test
    void aMembersCheckoutFollowsTheIntegratedHeadAsSoonAsItsLogNamesIt() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");
        finish("m-1");
        exchangeLogs();
        assertEquals(AgentStatus.COMPLETED, leader.pump().status());
        exchangeLogs();

        link.member.mainMayHaveMoved();

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (!leader.head().equals(TestGit.output(memberRoot, "rev-parse", "HEAD")) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(leader.head(), TestGit.output(memberRoot, "rev-parse", "HEAD"), "main is equal on both computers without a new lane");
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
    void aMemberThatCannotFetchTellsTheAgentAndTheInstallWhyAndStartsOnceItCan() throws Exception {
        String url = TestGit.output(memberRoot, "remote", "get-url", "origin");
        TestGit.run(memberRoot, "remote", "set-url", "origin", temp.resolve("gone.git").toString());
        // Neither the leader over Link nor the mirror can be reached; the mirror's reason is the one shown.
        replicate(true, false);

        Map<String, String> status = new ProviderApplicationService().status(member, "codex").values();
        assertEquals("FAILED", status.get("WORKTREE_BINDING_STATUS"), status.toString());
        assertEquals("CODE_REMOTE_UNAVAILABLE", status.get("WORKTREE_BINDING_DIAGNOSTIC"), status.toString());
        assertTrue(status.get("WORKTREE_BINDING_DETAIL").contains("git fetch origin"), status.toString());

        AgentResponse ensured = new AgentSessionService().ensureSession(new SessionResolutionRequest(memberRoot, "codex", "m-1", null, false));
        Map<String, Object> answer = ensured.toMap();
        assertEquals(AgentStatus.BLOCKED, ensured.status(), ensured.toJson());
        assertEquals("workspace_not_ready", answer.get("reason"));
        assertEquals("request_human_help", answer.get("nextAction"));
        Map<?, ?> result = (Map<?, ?>) answer.get("result");
        assertEquals("CODE_REMOTE_UNAVAILABLE", result.get("diagnostic"));
        assertTrue(String.valueOf(result.get("detail")).contains("does not appear to be a git repository"), ensured.toJson());
        assertTrue(String.valueOf(result.get("detail")).contains("git fetch origin"), ensured.toJson());
        assertEquals(answer, new WorkspaceReadinessService().assess(member, "codex", "m-1").response().toMap(), "every operation gives the same answer");

        TestGit.run(memberRoot, "remote", "set-url", "origin", url);
        link.reconnect();
        AgentResponse recovered = new AgentSessionService().ensureSession(new SessionResolutionRequest(memberRoot, "codex", "m-1", null, false));

        assertEquals(AgentStatus.READY, recovered.status(), recovered.toJson());
        assertNull(bindings.find(member, "codex", "m-1").orElseThrow().lastSeenDetail(), "the explanation goes with the failure");
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
