package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspacePatchService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
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
        var laneA = bindings.ensure(location, "codex", "chat-a").binding();
        var laneB = bindings.ensure(location, "codex", "chat-b").binding();
        assertNotEquals(laneA.worktreePath(), laneB.worktreePath());

        UUID group = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        assertTrue(collaboration.announce(root, "codex", "chat-a", "group", "tests",
                List.of(ResourceSelector.pathExact("src/a.py")), group).acquired());
        assertTrue(collaboration.announce(root, "codex", "chat-b", "group", "tests",
                List.of(ResourceSelector.pathExact("src/b.py")), group).acquired());
        assertFalse(collaboration.announce(root, "codex", "chat-b", "overlap", "blocked",
                List.of(ResourceSelector.pathExact("src/a.py")), group).acquired());

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

    private static String gitOutput(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1]; command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1]; command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }
}
