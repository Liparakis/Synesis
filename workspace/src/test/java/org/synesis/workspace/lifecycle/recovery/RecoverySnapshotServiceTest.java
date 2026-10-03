package org.synesis.workspace.lifecycle.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.TestGit;

/**
 * Verifies that recovery artifacts cannot replace successor Git or Synesis metadata.
 */
class RecoverySnapshotServiceTest {

    @Test
    void materializeAndRestorePreserveSuccessorRepositoryMetadata(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        TestGit.run(project, "init");
        TestGit.run(project, "config", "user.name", "Recovery Test");
        TestGit.run(project, "config", "user.email", "recovery@example.test");
        Files.writeString(project.resolve("README.md"), "recovery\n");
        TestGit.run(project, "add", ".");
        TestGit.run(project, "commit", "-m", "initial");
        new ProjectApplicationService().init(project);

        ProjectLocation location = new ProjectApplicationService().locate(project);
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        ProviderSessionBindingService.Binding source = bindings.ensure(location, "codex", "recovery-source").binding();
        Path sourceWorktree = Path.of(source.worktreePath());
        Files.writeString(sourceWorktree.resolve("partial.txt"), "unfinished\n");

        RecoverySnapshotService service = new RecoverySnapshotService();
        RecoverySnapshotService.Snapshot snapshot = service.materialize(location, source.sessionId());
        assertFalse(snapshot.paths().contains(".git"));
        assertTrue(snapshot.paths().stream().noneMatch(path -> path.equals(".synesis") || path.startsWith(".synesis/")));

        ProviderSessionBindingService.Binding successor = bindings.ensure(location, "codex", "recovery-successor").binding();
        Path successorWorktree = Path.of(successor.worktreePath());
        String gitPointer = Files.readString(successorWorktree.resolve(".git"));

        service.restoreToLane(snapshot.root() + "#" + snapshot.contentHash(), successorWorktree);

        assertEquals(gitPointer, Files.readString(successorWorktree.resolve(".git")));
        assertEquals(successor.branch(), TestGit.output(successorWorktree, "symbolic-ref", "--short", "HEAD"));
        assertEquals("unfinished\n", Files.readString(successorWorktree.resolve("partial.txt")));
    }
}
