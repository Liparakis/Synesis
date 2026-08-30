package org.synesis.workspace.lifecycle.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises lifecycle path validation and containment guarantees. */
class LifecyclePathVerifierTest {

    @Test
    void rejectsControlCheckoutRootAndSubdirectories(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        LifecyclePathVerifier verifier = new LifecyclePathVerifier();

        var resultSelf = verifier.verifyPath(controlRoot, controlRoot);
        assertFalse(resultSelf.safe());
        assertEquals(CleanupReason.CONTROL_CHECKOUT_PROTECTED.code(), resultSelf.reasonCode());

        Path subDir = controlRoot.resolve("src/main");
        Files.createDirectories(subDir);
        var resultSub = verifier.verifyPath(controlRoot, subDir);
        assertFalse(resultSub.safe());
        assertEquals(CleanupReason.CONTROL_CHECKOUT_PROTECTED.code(), resultSub.reasonCode());
    }

    @Test
    void rejectsDotGitDirectory(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");
        Path gitDir = controlRoot.resolve(".git");
        Files.createDirectories(gitDir);

        LifecyclePathVerifier verifier = new LifecyclePathVerifier();

        var result = verifier.verifyPath(controlRoot, gitDir);
        assertFalse(result.safe());
        assertEquals(CleanupReason.CONTROL_CHECKOUT_PROTECTED.code(), result.reasonCode());
    }

    @Test
    void rejectsPathOutsideWorkspaceRoot(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        Path outsideDir = tempDir.resolve("outside-dir");
        Files.createDirectories(outsideDir);

        LifecyclePathVerifier verifier = new LifecyclePathVerifier();

        var result = verifier.verifyPath(controlRoot, outsideDir);
        assertFalse(result.safe());
        assertEquals(CleanupReason.PATH_OUTSIDE_WORKSPACE_ROOT.code(), result.reasonCode());
    }

    @Test
    void acceptsValidPathUnderWorkspaceRoot(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot.resolve(".synesis"));
        Files.writeString(controlRoot.resolve(".synesis/project.json"), "{\"projectId\":\"test-project\"}");

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path validWorktree = workspaceRoot.resolve("worktrees/session-123");
        Files.createDirectories(validWorktree);

        LifecyclePathVerifier verifier = new LifecyclePathVerifier();

        var result = verifier.verifyPath(controlRoot, validWorktree);
        assertTrue(result.safe());
        assertEquals("path_verified", result.reasonCode());
    }
}
