package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A member's entry makes the leader run one integration pass shortly after, with no agent calling in, and never two
 * at once.
 */
final class MergeDriverTest {

    @Test
    void aBurstOfMemberEntriesRunsOnePassAfterTheDelay() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        try (MergeDriver driver = new MergeDriver(() -> {
            passes.incrementAndGet();
            ran.countDown();
        }, 150)) {
            long begin = System.nanoTime();
            driver.onMemberEntry();
            driver.onMemberEntry();
            driver.onMemberEntry();
            assertEquals(0, passes.get(), "the pass waits for the delay");

            assertTrue(ran.await(10, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) >= 100);
            Thread.sleep(400);

            assertEquals(1, passes.get());
        }
    }

    @Test
    void anEntryDuringAPassRunsOneMorePassAfterItAndNeverTwoAtOnce() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger overlap = new AtomicInteger();
        CountDownLatch inFirstPass = new CountDownLatch(1);
        CountDownLatch finishFirstPass = new CountDownLatch(1);
        CountDownLatch secondPass = new CountDownLatch(1);
        try (MergeDriver driver = new MergeDriver(() -> {
            if (running.incrementAndGet() > 1) {
                overlap.incrementAndGet();
            }
            try {
                if (passes.incrementAndGet() == 1) {
                    inFirstPass.countDown();
                    finishFirstPass.await(10, TimeUnit.SECONDS);
                } else {
                    secondPass.countDown();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }, 20)) {
            driver.onMemberEntry();
            assertTrue(inFirstPass.await(10, TimeUnit.SECONDS));

            driver.onMemberEntry();
            driver.onMemberEntry();
            finishFirstPass.countDown();

            assertTrue(secondPass.await(10, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertEquals(2, passes.get());
            assertEquals(0, overlap.get());
        }
    }

    @Test
    void aRepeatingDriverRunsPassesWithNoMemberEntry() throws Exception {
        CountDownLatch twoPasses = new CountDownLatch(2);
        try (MergeDriver _ = new MergeDriver(twoPasses::countDown, 10).repeatEvery(50)) {
            assertTrue(twoPasses.await(10, TimeUnit.SECONDS), "accepted member work is merged although no member entry follows it");
        }
    }

    @Test
    void closeReturnsOnlyAfterAPassThatIsRunningHasEnded() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger ended = new AtomicInteger();
        MergeDriver driver = new MergeDriver(() -> {
            started.countDown();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < until) {
                Thread.onSpinWait(); // a pass that does not stop at an interrupt, as one inside a Git call may not
            }
            ended.incrementAndGet();
        }, 1);
        driver.onMemberEntry();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        driver.close();

        assertEquals(1, ended.get(), "a pass still writing to the project after close races whoever removes or reuses the folder");
    }
}
