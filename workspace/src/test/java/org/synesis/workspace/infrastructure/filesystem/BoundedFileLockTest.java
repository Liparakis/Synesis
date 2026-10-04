package org.synesis.workspace.infrastructure.filesystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies bounded cross-thread contention for process-safe file locks.
 */
final class BoundedFileLockTest {

    @TempDir
    Path tempDirectory;

    @Test
    void secondThreadWaitsUntilCurrentOwnerReleasesTheLock() throws Exception {
        Path lockFile = tempDirectory.resolve("workspace.lock");
        BoundedFileLock first = BoundedFileLock.acquire(lockFile, Duration.ofSeconds(2));
        var workers = Executors.newSingleThreadExecutor();
        CountDownLatch attempting = new CountDownLatch(1);
        try {
            var second = workers.submit(() -> {
                attempting.countDown();
                try (BoundedFileLock lock = BoundedFileLock.acquire(lockFile, Duration.ofSeconds(2))) {
                    return lock.isHeld();
                }
            });
            assertTrue(attempting.await(1, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertFalse(second.isDone());
            first.close();
            assertTrue(second.get(2, TimeUnit.SECONDS));
        } finally {
            first.close();
            workers.shutdownNow();
        }
    }

    @Test
    void heldLockFailsAfterTheConfiguredBound() throws Exception {
        Path lockFile = tempDirectory.resolve("workspace.lock");
        try (BoundedFileLock lock = BoundedFileLock.acquire(lockFile, Duration.ofSeconds(1))) {
            assertTrue(lock.isHeld());
            assertThrows(IOException.class, () -> BoundedFileLock.acquire(lockFile, Duration.ofMillis(100)));
        }
    }
}
