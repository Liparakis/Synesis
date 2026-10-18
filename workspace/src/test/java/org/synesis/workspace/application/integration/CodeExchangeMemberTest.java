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
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Code moves through a shared Git remote between a member's clone and the leader's (ADR-0098, step 2, slice 3b),
 * driven through the real session, completion and integration services. The log is exchanged in process; the code is
 * exchanged through a bare repository.
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
    }

    /** Both computers get their replication settings; the member is a member of the leader's computer. */
    private void replicate() throws Exception {
        ReplicationSettings.leader(Set.of(memberIdentity.nodeId())).withCodeRemote("origin").write(leader.root.resolve(".synesis/coordination"));
        ReplicationSettings.member(leader.identity.nodeId()).withCodeRemote("origin").write(memberCoordination);
        ReplicationRole.register(memberCoordination, ReplicationRole.memberOf(leader.identity.nodeId()));
        ProviderTestSupport.install(member, "codex");
    }

    private PredictionEventStore memberStore() throws Exception {
        return new PredictionEventStore(memberCoordination, leader.location.projectId());
    }

    /** What the member appended to its own log, in order. */
    private List<PredictionEventType> memberAppended() throws Exception {
        List<PredictionEventType> types = new java.util.ArrayList<>();
        for (byte[] encoded : memberStore().entriesAfter(0, 10_000, Integer.MAX_VALUE)) {
            types.add(PredictionEvent.decode(encoded).type());
        }
        return types;
    }

    /** Each computer receives byte-identical copies of the other's log, until neither has anything new. */
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
    void aMembersLaneTravelsThroughTheRemoteAndTheNextLaneStartsOnTheIntegratedHead() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");

        AgentResponse finished = finish("m-1");

        assertTrue(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED), finished.toJson());
        assertTrue(memberAppended().contains(PredictionEventType.TASK_SNAPSHOT_CREATED), finished.toJson());
        assertEquals(0, leader.count(PredictionEventType.COMPLETION_PREPARED), "nothing reached the leader before the logs were exchanged");
        String refs = remoteRefs();
        assertTrue(refs.contains("refs/synesis/prepared/"), "the prepared commit is on the remote the leader fetches from");
        assertTrue(refs.contains("refs/synesis/snapshots/"), "the snapshot commit is on the remote the leader fetches from");

        exchangeLogs();

        assertEquals(1, leader.count(PredictionEventType.COMPLETION_PREPARED), "the leader received the member's entry in a byte-identical copy");
        String snapshotId = leader.store().taskCompletionProjection().allSnapshots().getFirst().snapshotId();
        String snapshotCommit = leader.store().taskCompletionProjection().allSnapshots().getFirst().commitSha();
        assertFalse(commitInLeaderClone(snapshotCommit), "the leader's clone does not hold the member's commit yet");

        // After integrating, the leader releases the member's lane: its own entry, which may act for any participant.
        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.COMPLETED, pass.status(), pass.toJson());
        assertEquals(TaskCompletionState.INTEGRATED, leader.state(snapshotId));
        assertTrue(leader.headHas("src/a.txt"));
        assertEquals(leader.head(), TestGit.output(remote, "rev-parse", "refs/heads/" + branch), "the leader published the control branch");

        // The merged lane was the group's only one, so the group completed; new work starts a new group.
        exchangeLogs();
        Path next = startLane("m-2", "src/b.txt", java.util.UUID.randomUUID());

        assertEquals(leader.head(), TestGit.output(memberRoot, "rev-parse", "HEAD"), "the member's checkout followed the leader");
        assertEquals(leader.head(), bindings.find(member, "codex", "m-2").orElseThrow().baseCommit(), "the new lane is based on the integrated head");
        assertTrue(Files.exists(next.resolve("src/a.txt")), "the new lane sees the integrated work");
    }

    @Test
    void aRemoteThatRefusesThePushStopsTheCompletionBeforeAnythingIsAppended() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");
        TestGit.run(memberRoot, "remote", "set-url", "origin", temp.resolve("gone.git").toString());
        int appendedBefore = memberAppended().size();

        AgentResponse finished = finish("m-1");

        assertEquals(AgentStatus.BLOCKED, finished.status(), finished.toJson());
        assertTrue(finished.toJson().contains("CODE_REMOTE_UNAVAILABLE"), finished.toJson());
        assertFalse(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED));
        assertEquals(appendedBefore, memberAppended().size(), "no entry was appended");
        exchangeLogs();
        assertEquals(0, leader.count(PredictionEventType.COMPLETION_PREPARED));
    }

    @Test
    void aMemberThatCannotFetchTellsTheAgentAndTheInstallWhyAndStartsOnceItCan() throws Exception {
        String url = TestGit.output(memberRoot, "remote", "get-url", "origin");
        TestGit.run(memberRoot, "remote", "set-url", "origin", temp.resolve("gone.git").toString());
        replicate();

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
        AgentResponse recovered = new AgentSessionService().ensureSession(new SessionResolutionRequest(memberRoot, "codex", "m-1", null, false));

        assertEquals(AgentStatus.READY, recovered.status(), recovered.toJson());
        assertNull(bindings.find(member, "codex", "m-1").orElseThrow().lastSeenDetail(), "the explanation goes with the failure");
    }

    @Test
    void aMemberWithNoCodeRemoteSettingRefusesAndPushesNothing() throws Exception {
        replicate();
        startLane("m-1", "src/a.txt");
        ReplicationSettings.member(leader.identity.nodeId()).write(memberCoordination);

        AgentResponse finished = finish("m-1");

        assertEquals(AgentStatus.BLOCKED, finished.status(), finished.toJson());
        assertTrue(finished.toJson().contains("CODE_REMOTE_NOT_CONFIGURED"), finished.toJson());
        assertFalse(memberAppended().contains(PredictionEventType.COMPLETION_PREPARED));
        assertEquals("", remoteRefs());
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
        try {
            TestGit.output(leader.root, "cat-file", "-e", commit + "^{commit}");
            return true;
        } catch (IOException absent) {
            return false;
        }
    }
}
