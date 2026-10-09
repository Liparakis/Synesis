package org.synesis.workspace.lifecycle.reconciliation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

/**
 * Verifies that a recovery interrupted by the death of its process cannot block later recoveries,
 * while a recovery that is still running keeps its exclusion.
 */
final class ReconciliationExecutionLockTest {

    @Test
    void lockLeftByAProcessThatNoLongerExistsIsReclaimed(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir);
        Path lockFile = lockFile(controlRoot);
        Process finished = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version").redirectErrorStream(true).start();
        finished.getInputStream().readAllBytes();
        finished.waitFor();
        Files.writeString(lockFile, lockJson(finished.pid(), System.currentTimeMillis(), "recplan-dead"));

        try (var _ = ReconciliationExecutionLock.acquire(controlRoot, "recplan-next")) {
            assertTrue(Files.readString(lockFile).contains("recplan-next"), "the new execution owns the lock");
        }
        assertFalse(Files.exists(lockFile), "a completed execution releases what it reclaimed");
    }

    @Test
    void lockHeldByARunningExecutionStillExcludesAnother(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir);
        try (var _ = ReconciliationExecutionLock.acquire(controlRoot, "recplan-running")) {
            IOException busy = assertThrows(IOException.class, () -> ReconciliationExecutionLock.acquire(controlRoot, "recplan-second"));
            assertTrue(busy.getMessage().contains("busy"), busy.getMessage());
            assertTrue(Files.readString(lockFile(controlRoot)).contains("recplan-running"), "the running execution keeps its lock");
        }
    }

    @Test
    void holderIsGoneOnlyWhenItsProcessIsAbsentOrYoungerThanTheLock(@TempDir Path tempDir) throws Exception {
        Path lock = tempDir.resolve("lock.json");
        ProcessHandle self = ProcessHandle.current();
        long selfStarted = self.info().startInstant().orElseThrow().toEpochMilli();

        Files.writeString(lock, lockJson(4242L, selfStarted, "recplan-a"));
        assertTrue(ReconciliationExecutionLock.abandoned(lock, pid -> Optional.empty()), "no process has the identifier");
        assertFalse(ReconciliationExecutionLock.abandoned(lock, pid -> Optional.of(self)), "a process that predates the lock may be the holder");

        // The identifier now belongs to a process that started after the lock was written.
        Files.writeString(lock, lockJson(4242L, selfStarted - 60_000L, "recplan-a"));
        assertTrue(ReconciliationExecutionLock.abandoned(lock, pid -> Optional.of(self)));
    }

    @Test
    void lockThatCannotBeUnderstoodIsTreatedAsHeld(@TempDir Path tempDir) throws Exception {
        Path lock = tempDir.resolve("lock.json");
        Files.writeString(lock, "not json");
        assertFalse(ReconciliationExecutionLock.abandoned(lock, pid -> Optional.empty()));
        Files.writeString(lock, "{\"pid\":\"seven\",\"acquiredAtEpochMillis\":1}");
        assertFalse(ReconciliationExecutionLock.abandoned(lock, pid -> Optional.empty()));
        assertFalse(ReconciliationExecutionLock.abandoned(tempDir.resolve("missing.json"), pid -> Optional.empty()));

        Path controlRoot = initializedProject(tempDir);
        Files.writeString(lockFile(controlRoot), "not json");
        assertThrows(IOException.class, () -> ReconciliationExecutionLock.acquire(controlRoot, "recplan-next"));
        assertTrue(Files.readString(lockFile(controlRoot)).equals("not json"), "an unreadable lock is left for doctor and repair");
    }

    private static Path initializedProject(Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);
        return controlRoot;
    }

    private static Path lockFile(Path controlRoot) throws Exception {
        Path admin = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot.toAbsolutePath().normalize()).resolve("admin");
        Files.createDirectories(admin);
        return admin.resolve("reconciliation-execution.lock");
    }

    private static String lockJson(long pid, long acquiredAt, String planId) {
        return "{\"pid\":" + pid + ",\"processName\":\"java\",\"processStartTime\":" + acquiredAt + ",\"nonce\":\"n\",\"acquiredAtEpochMillis\":" + acquiredAt + ",\"planId\":\"" + planId + "\"}";
    }
}
