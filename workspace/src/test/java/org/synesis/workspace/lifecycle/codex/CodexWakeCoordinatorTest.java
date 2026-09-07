package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies bounded event-driven wake relay lifetime and durable-event detection.
 */
class CodexWakeCoordinatorTest {

    @TempDir
    Path temp;

    private static void awaitAtLeast(AtomicInteger scans, int expected) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (scans.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(scans.get() >= expected, "timed out waiting for relay scan");
    }

    @Test
    void durableEventTriggersOneScanAndCloseStopsRelay() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        Path events = Files.createDirectories(temp.resolve("events"));
        CodexWakeCoordinator coordinator = new CodexWakeCoordinator(temp, events, scans::incrementAndGet);
        coordinator.start();
        try {
            awaitAtLeast(scans, 1);
            Files.writeString(events.resolve("00000000000000000001.sce"), "event");
            awaitAtLeast(scans, 2);
            assertTrue(coordinator.isAlive());
        } finally {
            coordinator.close();
        }
        assertFalse(coordinator.isAlive());
    }
}
