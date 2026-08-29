package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;
import org.synesis.workspace.application.task.TaskSnapshotService;

/**
 * Unit tests for task completion and integration application services.
 */
class TaskIntegrationServiceTest {

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        return org.synesis.workspace.test.TestGit.output(root, args);
    }

    @Test
    void taskSnapshotRecordInvariants() {
        UUID taskId = UUID.randomUUID();
        TaskSnapshotRecord rec = new TaskSnapshotRecord(
                taskId, "snap_test", "node-1", "sup-1", "worker-1", "sess-1",
                "base", "commit", List.of("src/App.java"), List.of(), "Completed work", System.currentTimeMillis());

        assertEquals(taskId, rec.taskId());
        assertEquals("snap_test", rec.snapshotId());
        assertEquals("node-1", rec.nodeId());
        assertEquals("sup-1", rec.supervisorId());
        assertEquals("worker-1", rec.workerId());
        assertEquals("base", rec.baseCommit());
        assertEquals("commit", rec.commitSha());
        assertEquals(List.of("src/App.java"), rec.changedPaths());
        assertEquals("Completed work", rec.summary());
    }

    @Test
    void taskSnapshotServiceInstantiates() {
        TaskSnapshotService service = new TaskSnapshotService();
        assertNotNull(service);
    }

    @Test
    void dirtyLaneProducesImmutableSnapshotIncludingUntrackedChanges(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        String head = gitOutput(root, "rev-parse", "HEAD");
        Files.writeString(root.resolve("README.md"), "changed\n");
        Files.writeString(root.resolve("new.txt"), "new\n");

        TaskSnapshotRecord record = new TaskSnapshotService().createSnapshot(
                UUID.randomUUID(), "node", "supervisor", "worker", "lane",
                root, root, "snapshot", java.util.Optional.empty(), List.of());
        assertNotNull(record.commitSha());
        org.junit.jupiter.api.Assertions.assertNotEquals(head, record.commitSha());
        assertEquals(List.of("README.md", "new.txt"), record.changedPaths());
        assertEquals("refs/synesis/snapshots/" + record.snapshotId(),
                record.provenance()
                        .snapshotRef());
    }

    @Test
    void inheritedSiblingSourceChangeCannotAuthorizeAnotherLanePublication(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("todo.py"), "pass\n");
        Files.writeString(root.resolve("test_todo.py"), "def test_todo(): pass\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "base");

        Files.writeString(root.resolve("test_todo.py"), "def test_todo(): assert True\n");
        git(root, "add", "test_todo.py");
        git(root, "commit", "-m", "integrated sibling snapshot");

        TaskSnapshotService service = new TaskSnapshotService();
        assertFalse(service.hasPublishableChanges(root,
                List.of(ResourceSelector.pathExact("todo.py"))));
        assertTrue(service.hasPublishableChanges(root,
                List.of(ResourceSelector.pathExact("test_todo.py"))));
    }

    @Test
    void claimedPublicationRejectsUncoveredManagedChanges(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        Files.writeString(root.resolve("README.md"), "unclaimed\n");
        TaskSnapshotService service = new TaskSnapshotService();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.createSnapshot(
                UUID.randomUUID(), "node", "supervisor", "worker", "lane", root, root, "snapshot",
                java.util.Optional.empty(), List.of(), List.of(ResourceSelector.pathExact("src/claimed.py")),
                UUID.randomUUID(), UUID.randomUUID(), "agt_test", "lane", 1, List.of()));
    }

    @Test
    void preparedPublicationIsIdempotentAndUsesThePinnedCommit(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        Files.writeString(root.resolve("README.md"), "changed\n");
        TaskSnapshotService service = new TaskSnapshotService();
        UUID taskId = UUID.randomUUID();
        UUID laneId = UUID.randomUUID();
        TaskSnapshotRecord first = service.createSnapshot(taskId, "node", "supervisor", "worker", "lane",
                root, root, "snapshot", java.util.Optional.empty(), List.of(), List.of(),
                UUID.randomUUID(), laneId, "agt_test", "lane", 1, List.of());
        String prepared = service.pinPreparedRef(root, first, "cmp_test");
        service.verifyPreparedRef(root, prepared, first.commitSha());
        service.promotePreparedRef(root,
                prepared,
                first.provenance()
                        .snapshotRef(),
                first.commitSha());

        TaskSnapshotRecord retry = service.createSnapshot(taskId, "node", "supervisor", "worker", "lane",
                root, root, "snapshot", java.util.Optional.of(first), List.of(), List.of(),
                UUID.randomUUID(), laneId, "agt_test", "lane", 1, List.of());
        assertEquals(first.snapshotId(), retry.snapshotId());
        assertEquals(first.commitSha(),
                gitOutput(root,
                        "rev-parse",
                        first.provenance()
                                .snapshotRef()));
        assertEquals(first.commitSha(), gitOutput(root, "rev-parse", prepared));
    }

    @Test
    void providerArtifactsAreRecordedAndExcludedFromTheSourceSnapshot(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        Files.writeString(root.resolve("README.md"), "changed\n");
        Files.createDirectories(root.resolve(".codex"));
        Files.writeString(root.resolve(".codex/session.json"), "runtime\n");

        TaskSnapshotRecord record = new TaskSnapshotService().createSnapshot(
                UUID.randomUUID(), "node", "supervisor", "worker", "lane", root, root,
                "snapshot", java.util.Optional.empty(), List.of());

        assertEquals(List.of("README.md"), record.changedPaths());
        assertNotEquals("UNRECORDED",
                record.provenance()
                        .artifactManifestDigest());
        assertEquals("", gitOutput(root, "ls-tree", "-r", "--name-only", record.commitSha(), ".codex"));
    }

    @Test
    void generatedPythonBytecodeDoesNotConflictWhenDisjointSnapshotsIntegrate(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        String base = gitOutput(root, "rev-parse", "HEAD");
        String fixtureId = UUID.randomUUID()
                .toString();
        Path laneA = root.resolveSibling(root.getFileName() + "-" + fixtureId + "-lane-a");
        Path laneB = root.resolveSibling(root.getFileName() + "-" + fixtureId + "-lane-b");
        git(root, "worktree", "add", "--detach", laneA.toString(), base);
        git(root, "worktree", "add", "--detach", laneB.toString(), base);
        try {
            Files.createDirectories(laneA.resolve("src"));
            Files.writeString(laneA.resolve("src/a.py"), "lane-a\n");
            Files.createDirectories(laneA.resolve("__pycache__"));
            Files.write(laneA.resolve("__pycache__/a.cpython-313.pyc"), new byte[]{1, 2, 3});

            Files.createDirectories(laneB.resolve("src/__pycache__"));
            Files.writeString(laneB.resolve("src/b.py"), "lane-b\n");
            Files.write(laneB.resolve("src/__pycache__/b.cpython-313.pyc"), new byte[]{4, 5, 6});

            TaskSnapshotService snapshots = new TaskSnapshotService();
            UUID group = UUID.randomUUID();
            TaskSnapshotRecord snapshotA = snapshots.createSnapshot(
                    UUID.randomUUID(), "node-a", "sup-a", "worker-a", "lane-a", laneA, root,
                    "lane A", java.util.Optional.empty(), List.of(),
                    List.of(ResourceSelector.pathExact("src/a.py")), group, UUID.randomUUID(),
                    "participant-a", "binding-a", 1, List.of());
            TaskSnapshotRecord snapshotB = snapshots.createSnapshot(
                    UUID.randomUUID(), "node-b", "sup-b", "worker-b", "lane-b", laneB, root,
                    "lane B", java.util.Optional.empty(), List.of(),
                    List.of(ResourceSelector.pathExact("src/b.py")), group, UUID.randomUUID(),
                    "participant-b", "binding-b", 1, List.of());

            assertEquals(List.of("src/a.py"), snapshotA.changedPaths());
            assertEquals(List.of("src/b.py"), snapshotB.changedPaths());
            assertFalse(gitOutput(laneA, "ls-tree", "-r", "--name-only", snapshotA.commitSha())
                    .contains("__pycache__"));
            assertFalse(gitOutput(laneB, "ls-tree", "-r", "--name-only", snapshotB.commitSha())
                    .contains("__pycache__"));

            IntegrationWorkspaceService integration = new IntegrationWorkspaceService();
            IntegrationWorkspaceService.IntegrationWorktreeResult result =
                    integration.prepareIntegrationWorktree(root, "pycache-artifact-" + UUID.randomUUID(), base,
                            List.of(snapshotA, snapshotB));
            try {
                assertTrue(result.success(), result.failureReason());
                assertEquals("lane-a\n",
                        Files.readString(result.worktreePath()
                                        .resolve("src/a.py"))
                                .replace("\r\n", "\n"));
                assertEquals("lane-b\n",
                        Files.readString(result.worktreePath()
                                        .resolve("src/b.py"))
                                .replace("\r\n", "\n"));
                assertFalse(Files.exists(result.worktreePath()
                        .resolve("__pycache__")));
                assertFalse(Files.exists(result.worktreePath()
                        .resolve("src/__pycache__")));
            } finally {
                integration.removeIntegrationWorktree(result.worktreePath());
            }
        } finally {
            git(root, "worktree", "remove", "--force", laneA.toString());
            git(root, "worktree", "remove", "--force", laneB.toString());
        }
    }

    @Test
    void managedContractChangesAreNotSilentlyDropped(@TempDir Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "Test");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        Files.writeString(root.resolve("AGENTS.md"), "changed\n");

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new TaskSnapshotService().createSnapshot(UUID.randomUUID(), "node", "supervisor", "worker",
                        "lane", root, root, "snapshot", java.util.Optional.empty(), List.of()));
        assertTrue(failure.getMessage()
                .startsWith("SNAPSHOT_ARTIFACT_POLICY:"));
    }
}
