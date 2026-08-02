package org.synesis.workspace;

import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for task completion and integration application services.
 */
class TaskIntegrationServiceTest {

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
                record.provenance().snapshotRef());
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
        service.promotePreparedRef(root, prepared, first.provenance().snapshotRef(), first.commitSha());

        TaskSnapshotRecord retry = service.createSnapshot(taskId, "node", "supervisor", "worker", "lane",
                root, root, "snapshot", java.util.Optional.of(first), List.of(), List.of(),
                UUID.randomUUID(), laneId, "agt_test", "lane", 1, List.of());
        assertEquals(first.snapshotId(), retry.snapshotId());
        assertEquals(first.commitSha(), gitOutput(root, "rev-parse", first.provenance().snapshotRef()));
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
        assertNotEquals("UNRECORDED", record.provenance().artifactManifestDigest());
        assertEquals("", gitOutput(root, "ls-tree", "-r", "--name-only", record.commitSha(), ".codex"));
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
        assertTrue(failure.getMessage().startsWith("SNAPSHOT_ARTIFACT_POLICY:"));
    }

    private static void git(Path root, String... args) throws Exception {
        Process process = new ProcessBuilder(withGit(args)).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }

    private static String gitOutput(Path root, String... args) throws Exception {
        Process process = new ProcessBuilder(withGit(args)).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }

    private static String[] withGit(String[] args) {
        String[] command = new String[args.length + 1]; command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length); return command;
    }
}
