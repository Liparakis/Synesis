package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.exception.CoordinationException;

/**
 * The project lock keeps excluding other processes while a second thread of the holder's own process is refused.
 */
class ProjectAppendLockTest {

    private static final int TAKEN = 0;
    private static final int TIMED_OUT = 3;
    private static final String TIMEOUT = "event append lock timeout";

    /** Child process: tries the lock once and reports whether it was taken. */
    public static final class Contender {

        private Contender() {
        }

        /**
         * Exits {@value #TAKEN} when the lock was taken, {@value #TIMED_OUT} when it timed out.
         *
         * @param args the coordination directory
         */
        public static void main(String[] args) {
            int exit = 4;
            try (ProjectAppendLock taken = ProjectAppendLock.acquire(Path.of(args[0]))) {
                exit = taken.isHeld() ? TAKEN : 4;
            } catch (IOException failure) {
                exit = TIMEOUT.equals(failure.getMessage()) ? TIMED_OUT : 4;
            }
            System.exit(exit);
        }
    }

    private static int otherProcessTries(Path directory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Contender.class.getName(), directory.toString())
                .redirectErrorStream(true).start();
        child.getInputStream().readAllBytes();
        return child.waitFor();
    }

    @Test
    void aSecondThreadOfTheHoldersProcessTimesOutAndLeavesTheHoldersLockStandingAgainstAnotherProcess(@TempDir Path directory) throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try (ProjectAppendLock holder = ProjectAppendLock.acquire(directory)) {
            Callable<Long> loser = () -> {
                long start = System.nanoTime();
                try (ProjectAppendLock second = ProjectAppendLock.acquire(directory)) {
                    throw new AssertionError("the lock was taken twice: " + second.isHeld());
                } catch (CoordinationException timeout) {
                    assertEquals(TIMEOUT, timeout.getMessage());
                }
                return (System.nanoTime() - start) / 1_000_000;
            };

            long waitedMillis = other.submit(loser).get();

            assertTrue(waitedMillis >= 1_500, "gave up after " + waitedMillis + " ms");
            assertTrue(holder.isHeld());
            assertEquals(TIMED_OUT, otherProcessTries(directory), "another process took a lock the holder still holds");
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void theLockIsFreeAtOnceAfterTheHolderClosesAndForAnotherProcessToo(@TempDir Path directory) throws Exception {
        ProjectAppendLock holder = ProjectAppendLock.acquire(directory);
        assertThrows(CoordinationException.class, () -> ProjectAppendLock.acquire(directory).close());
        holder.close();

        long start = System.nanoTime();
        try (ProjectAppendLock next = ProjectAppendLock.acquire(directory)) {
            assertTrue(next.isHeld());
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, "the next acquire waited");

        assertEquals(TAKEN, otherProcessTries(directory));
    }
}
