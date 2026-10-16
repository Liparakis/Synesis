package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A dropped link is dialed again with a delay that doubles to a ceiling, for as long as the peer is wanted and not a
 * moment longer (ADR-0098).
 */
@Timeout(60)
final class RedialerTest {

    private static final Duration FIRST = Duration.ofMillis(20);
    private static final Duration CAP = Duration.ofMillis(80);

    private final List<Long> attemptTimes = new CopyOnWriteArrayList<>();
    private final List<String> journal = new CopyOnWriteArrayList<>();

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    private Redialer redialer(BooleanSupplier wanted, Redialer.Dial dial) {
        return new Redialer(wanted, () -> {
            attemptTimes.add(System.nanoTime());
            dial.dial();
        }, FIRST, CAP, journal::add);
    }

    @Test
    void theDelayDoublesFromTheFirstUpToTheCeilingAndStaysThereWhileThePeerIsWanted() throws Exception {
        try (Redialer redialer = redialer(() -> true, () -> {
            throw new IllegalStateException("no route");
        })) {
            redialer.start();
            await(() -> attemptTimes.size() >= 7);
        }

        long[] gapsMillis = new long[attemptTimes.size() - 1];
        for (int index = 0; index < gapsMillis.length; index++) {
            gapsMillis[index] = TimeUnit.NANOSECONDS.toMillis(attemptTimes.get(index + 1) - attemptTimes.get(index));
        }
        // 20 ms, then 40, then 80 and 80 again: never shorter than the delay set, never much longer than the ceiling.
        assertTrue(gapsMillis[0] >= 35, "second delay doubled: " + gapsMillis[0]);
        assertTrue(gapsMillis[1] >= 75, "third delay doubled again: " + gapsMillis[1]);
        for (int index = 2; index < gapsMillis.length; index++) {
            assertTrue(gapsMillis[index] >= 75 && gapsMillis[index] < 1_000, "capped delay: " + gapsMillis[index]);
        }
        assertEquals(1, journal.stream().filter(line -> line.startsWith("REDIAL_FAILED")).count(), "only the first failure is written");
    }

    @Test
    void itStopsAtOnceWhenThePeerIsNoLongerWantedAndDialsNothingMore() throws Exception {
        AtomicBoolean wanted = new AtomicBoolean(true);
        try (Redialer redialer = redialer(wanted::get, () -> {
            throw new IllegalStateException("no route");
        })) {
            redialer.start();
            await(() -> attemptTimes.size() >= 2);
            wanted.set(false);
            await(() -> journal.stream().anyMatch(line -> line.startsWith("REDIAL_STOPPED")));
            int dialed = attemptTimes.size();
            Thread.sleep(300);

            assertEquals(dialed, attemptTimes.size());
        }
    }

    @Test
    void aPeerNotWantedFromTheStartIsNeverDialed() throws Exception {
        try (Redialer redialer = redialer(() -> false, () -> {
            throw new AssertionError("must not dial");
        })) {
            redialer.start();
            await(() -> !journal.isEmpty());
            Thread.sleep(100);

            assertEquals(0, attemptTimes.size());
        }
    }

    @Test
    void aSuccessfulDialEndsTheLoopAndAStartWhileItRunsFollowsItWithAFreshOne() throws Exception {
        AtomicInteger dials = new AtomicInteger();
        Redialer[] holder = new Redialer[1];
        try (Redialer redialer = redialer(() -> true, () -> {
            if (dials.incrementAndGet() == 1) {
                // The new session ended while its own dial was still being handed over.
                holder[0].start();
            }
        })) {
            holder[0] = redialer;
            redialer.start();
            await(() -> dials.get() == 2);
            Thread.sleep(300);

            assertEquals(2, dials.get(), "one dial per request, none after");
        }
    }

    @Test
    void closingStopsAnythingStillScheduled() throws Exception {
        Redialer redialer = redialer(() -> true, () -> {
            throw new IllegalStateException("no route");
        });
        redialer.start();
        await(() -> attemptTimes.size() >= 1);
        redialer.close();
        int dialed = attemptTimes.size();
        Thread.sleep(300);

        assertTrue(attemptTimes.size() <= dialed + 1);
    }
}
