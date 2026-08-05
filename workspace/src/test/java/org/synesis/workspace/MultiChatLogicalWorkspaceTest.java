package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.test.TestGit;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.workspace.WorkspacePatchService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.workspace.agent.AgentStatus;

/** Deterministic acceptance of two isolated same-provider mutation lanes. */
final class MultiChatLogicalWorkspaceTest {
    @Test
    void disjointLanesMutateIndependentlyAndOverlapHasOneWinner() throws Exception {
        Path root = Files.createTempDirectory("synesis-workgroup-acceptance-");
        git(root, "init");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "base\n");
        Files.createDirectories(root.resolve("tests"));
        Files.writeString(root.resolve("tests/test_lanes.py"),
                "def test_lane_outputs():\n    assert open('src/a.py').read().strip() == 'lane-a'\n    assert open('src/b.py').read().strip() == 'lane-b'\n");
        git(root, "add", "."); git(root, "commit", "-m", "base");
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        new ProviderManualService().install("codex");
        var laneA = bindings.ensure(location, "codex", "chat-a").binding();
        var laneB = bindings.ensure(location, "codex", "chat-b").binding();
        assertNotEquals(laneA.worktreePath(), laneB.worktreePath());

        UUID group = UUID.nameUUIDFromBytes(("default-work-group:" + location.projectId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        assertTrue(collaboration.announce(root, "codex", "chat-a", "group", "tests",
                List.of(ResourceSelector.pathExact("src/a.py"))).acquired());
        assertTrue(collaboration.announce(root, "codex", "chat-b", "group", "tests",
                List.of(ResourceSelector.pathExact("src/b.py"))).acquired());
        assertFalse(collaboration.announce(root, "codex", "chat-b", "overlap", "blocked",
                List.of(ResourceSelector.pathExact("src/a.py"))).acquired());
        assertEquals(1, collaboration.status(root).groups().stream()
                .filter(candidate -> candidate.workGroupId().equals(group)).count());

        WorkspacePatchService patches = new WorkspacePatchService();
        assertTrue(patches.applyPatch(new WorkspacePatchService.PatchRequest(root, "codex", "chat-a",
                "src/a.py", true, "a\n", null, List.of())).status() == AgentStatus.COMPLETED);
        assertTrue(patches.applyPatch(new WorkspacePatchService.PatchRequest(root, "codex", "chat-b",
                "src/b.py", true, "b\n", null, List.of())).status() == AgentStatus.COMPLETED);
        assertTrue(Files.exists(Path.of(laneA.worktreePath()).resolve("src/a.py")));
        assertTrue(Files.exists(Path.of(laneB.worktreePath()).resolve("src/b.py")));

        Files.writeString(Path.of(laneA.worktreePath()).resolve("src/a.py"), "lane-a\n");
        Files.writeString(Path.of(laneB.worktreePath()).resolve("src/b.py"), "lane-b\n");
        String base = gitOutput(root, "rev-parse", "HEAD");
        TaskSnapshotService snapshots = new TaskSnapshotService();
        TaskSnapshotRecord snapshotA = snapshots.createSnapshot(UUID.randomUUID(), "node-a", "sup-a", "worker-a", "chat-a",
                Path.of(laneA.worktreePath()), root, "lane A", java.util.Optional.empty(), List.of(),
                List.of(ResourceSelector.pathExact("src/a.py")), group, UUID.randomUUID(),
                WorkspaceCollaborationService.participantHandle(laneA.sessionId()), laneA.sessionId(), 1, List.of());
        TaskSnapshotRecord snapshotB = snapshots.createSnapshot(UUID.randomUUID(), "node-b", "sup-b", "worker-b", "chat-b",
                Path.of(laneB.worktreePath()), root, "lane B", java.util.Optional.empty(), List.of(),
                List.of(ResourceSelector.pathExact("src/b.py")), group, UUID.randomUUID(),
                WorkspaceCollaborationService.participantHandle(laneB.sessionId()), laneB.sessionId(), 1, List.of());
        IntegrationWorkspaceService integration = new IntegrationWorkspaceService();
        IntegrationWorkspaceService.IntegrationWorktreeResult integrated = integration.prepareIntegrationWorktree(
                root, "acceptance-" + UUID.randomUUID(), base, List.of(snapshotA, snapshotB));
        try {
            assertTrue(integrated.success(), integrated.failureReason());
            assertEquals("lane-a\n", Files.readString(integrated.worktreePath().resolve("src/a.py")).replace("\r\n", "\n"));
            assertEquals("lane-b\n", Files.readString(integrated.worktreePath().resolve("src/b.py")).replace("\r\n", "\n"));
            Process pytest = new ProcessBuilder("python", "-m", "pytest", "-q")
                    .directory(integrated.worktreePath().toFile()).redirectErrorStream(true).start();
            String pytestOutput = new String(pytest.getInputStream().readAllBytes());
            assertTrue(pytest.waitFor() == 0, pytestOutput);
        } finally {
            integration.removeIntegrationWorktree(integrated.worktreePath());
        }
        collaboration.release(root, "codex", "chat-a");
        assertTrue(collaboration.status(root).intents().stream().anyMatch(i ->
                i.selectors().contains(ResourceSelector.pathExact("src/b.py"))));
    }

    @Test
    void repairJoinMaterializesImmutableConflictIntoNewProviderLane() throws Exception {
        Path root = Files.createTempDirectory("synesis-repair-join-");
        git(root, "init");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "base\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/conflict.py"), "control-base\n");
        git(root, "add", "."); git(root, "commit", "-m", "base");
        new ProviderManualService().install("codex");
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var sourceBinding = bindings.ensure(location, "codex", "repair-source").binding();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        List<ResourceSelector> selectors = List.of(ResourceSelector.pathExact("src/conflict.py"));
        assertTrue(collaboration.announce(root, "codex", "repair-source", "source", "repair",
                selectors).acquired());
        UUID sourceIntentId = UUID.nameUUIDFromBytes(("codex:" + sourceBinding.sessionId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID group = UUID.nameUUIDFromBytes(("default-work-group:" + location.projectId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(Path.of(sourceBinding.worktreePath()).resolve("src"));
        Files.writeString(Path.of(sourceBinding.worktreePath()).resolve("src/conflict.py"), "original\n");
        TaskSnapshotRecord snapshot = new TaskSnapshotService().createSnapshot(UUID.randomUUID(), "node", "sup",
                "worker", sourceBinding.sessionId(), Path.of(sourceBinding.worktreePath()), root, "conflict",
                java.util.Optional.empty(), List.of(), selectors, group, sourceIntentId,
                WorkspaceCollaborationService.participantHandle(sourceBinding.sessionId()), sourceBinding.sessionId(), 1,
                List.of());
        IdentityBootstrap bootstrap = new IdentityBootstrap(location.profile().resolve("link"));
        var identity = bootstrap.loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        TaskSnapshotPayload payload = new TaskSnapshotPayload(snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(),
                snapshot.supervisorId(), snapshot.workerId(), snapshot.providerSessionId(), snapshot.baseCommit(),
                snapshot.commitSha(), snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(),
                snapshot.provenance());
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), payload.encode(), identity);

        // Advance the control checkout independently.  The repair lane must
        // start here, not at the source lane's stale base commit.
        Files.writeString(root.resolve("src/conflict.py"), "control-change\n");
        git(root, "add", "src/conflict.py"); git(root, "commit", "-m", "control change");
        String currentControlHead = gitOutput(root, "rev-parse", "HEAD");
        var targetBinding = bindings.ensure(location, "codex", "repair-target").binding();

        String attemptId = "repair-attempt-" + UUID.randomUUID();
        IntegrationAttemptPayload started = new IntegrationAttemptPayload(attemptId, location.projectId(),
                List.of(snapshot.snapshotId()), currentControlHead, "", "started", "");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                identity.nodeId(), started.encode(), identity);
        IntegrationAttemptPayload conflict = new IntegrationAttemptPayload(attemptId, location.projectId(),
                List.of(snapshot.snapshotId()), currentControlHead, "", "conflict", "merge conflict");
        store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_CONFLICTED,
                identity.nodeId(), conflict.encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.REPAIR_REQUIRED,
                identity.nodeId(), conflict.encode(), identity);

        Files.writeString(Path.of(targetBinding.worktreePath()).resolve("unowned.txt"), "must-not-be-adopted\n");
        IOException dirtyTarget = assertThrows(IOException.class,
                () -> collaboration.joinRepair(root, "codex", "repair-target", sourceIntentId,
                        snapshot.snapshotId()));
        assertTrue(dirtyTarget.getMessage().startsWith("REPAIR_TARGET_DIRTY"), dirtyTarget.getMessage());
        assertTrue(collaboration.status(root).intents().stream().anyMatch(intent ->
                intent.intentId().equals(sourceIntentId)
                        && intent.selectors().equals(selectors)));
        Files.delete(Path.of(targetBinding.worktreePath()).resolve("unowned.txt"));

        var joined = collaboration.joinRepair(root, "codex", "repair-target", sourceIntentId, snapshot.snapshotId());
        assertTrue(joined.acquired());
        assertEquals(currentControlHead, gitOutput(Path.of(targetBinding.worktreePath()), "rev-parse", "HEAD"));
        assertEquals(snapshot.commitSha(), gitOutput(Path.of(targetBinding.worktreePath()), "rev-parse", "CHERRY_PICK_HEAD"));
        String conflicted = Files.readString(Path.of(targetBinding.worktreePath()).resolve("src/conflict.py"))
                .replace("\r\n", "\n");
        assertTrue(conflicted.contains("<<<<<<<"), conflicted);
        assertEquals(snapshot.commitSha(), gitOutput(root, "rev-parse", snapshot.provenance().snapshotRef()));
        assertTrue(collaboration.status(root).intents().stream().anyMatch(intent ->
                intent.intentId().equals(joined.intent().intentId())
                        && intent.participant().equals(WorkspaceCollaborationService.participantHandle(targetBinding.sessionId()))));

        long repairEvents = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId())
                .events().stream().filter(event -> event.type() == PredictionEventType.REPAIR_LANE_CREATED).count();
        assertTrue(collaboration.joinRepair(root, "codex", "repair-target", sourceIntentId,
                snapshot.snapshotId()).acquired());
        long retriedRepairEvents = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId())
                .events().stream().filter(event -> event.type() == PredictionEventType.REPAIR_LANE_CREATED).count();
        assertEquals(repairEvents, retriedRepairEvents);
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        return TestGit.output(root, args);
    }

    private static void git(Path root, String... args) throws Exception {
        TestGit.run(root, args);
    }
}
