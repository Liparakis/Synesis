package org.synesis.workspace.cleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockAndConcurrencyTest {

    @Test
    @SuppressWarnings("try")
    void locksExecutionAndFailsConcurrentAcquisition(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        try (CleanupExecutionLock lock1 = CleanupExecutionLock.acquire(controlRoot, "plan-1")) {
            // Concurrent acquire attempt must fail closed
            assertThrows(IOException.class, () -> CleanupExecutionLock.acquire(controlRoot, "plan-2"));
        }

        // After lock1 closes, lock file should be released
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        Path lockFile = workspaceRoot.resolve("admin/cleanup-execution.lock");
        assertTrue(!Files.exists(lockFile));
    }
}
