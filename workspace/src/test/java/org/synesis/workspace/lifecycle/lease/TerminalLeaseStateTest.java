package org.synesis.workspace.lifecycle.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.process.ProcessInspector;

/**
 * Verifies monotonic terminal transport finalization and ordinary lease behavior.
 */
class TerminalLeaseStateTest {

    private static SessionLeaseRecord record(SessionLeaseState state, long pid) {
        long now = 1_700_000_000_000L;
        return new SessionLeaseRecord(1, "project", "codex", "connection", "worker", "session",
                new SessionProcessIdentity(pid, "java", "java -jar synesis.jar", now, "nonce"),
                "0.1.0-SNAPSHOT", now - 1000L, now, state);
    }

    private static Path initializedProject(Path root) throws Exception {
        Files.createDirectories(root);
        new ProjectApplicationService().init(root);
        return root;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }

    @Test
    void terminalAuthorityWithMissingProcessIsDurablyDisconnectedOnCleanClose(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("missing-process"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 99999L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);

        SessionLeaseService service = new SessionLeaseService(store, _ -> Optional.empty());
        assertTrue(service.markTerminalDisconnected(controlRoot, original.connectionInstanceId(), 99999L));
        service.markClosedCleanly(controlRoot, original.connectionInstanceId());

        SessionLeaseRecord result = store.load(controlRoot, original.connectionInstanceId())
                .orElseThrow();
        assertEquals(SessionLeaseState.TERMINAL_DISCONNECTED, result.leaseState());
        assertEquals(original.processIdentity(), result.processIdentity());
        assertEquals(original.createdAtEpochMillis(), result.createdAtEpochMillis());
        assertEquals(original.lastHeartbeatEpochMillis(), result.lastHeartbeatEpochMillis());
    }

    @Test
    void terminalAuthorityWithLiveProcessAllowsCleanClose(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("clean-terminal"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 1234L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);
        ProcessInspector live = pid -> Optional.of(
                new ProcessInspector.ProcessDetails(pid, "java", "java -jar synesis.jar", true));

        new SessionLeaseService(store, live).markClosedCleanly(controlRoot, original.connectionInstanceId());

        assertEquals(SessionLeaseState.CLOSED_CLEANLY,
                store.load(controlRoot, original.connectionInstanceId())
                        .orElseThrow()
                        .leaseState());
    }

    @Test
    void foreignCloseAfterTrackedTerminalProcessDiesPersistsDisconnect(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("foreign-dead"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 1234L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);

        SessionLeaseService service = new SessionLeaseService(store, _ -> Optional.empty());
        assertFalse(service.markClosedCleanly(controlRoot, original.connectionInstanceId(), 5678L));

        SessionLeaseRecord result = store.load(controlRoot, original.connectionInstanceId())
                .orElseThrow();
        assertEquals(SessionLeaseState.TERMINAL_DISCONNECTED, result.leaseState());
        assertEquals(original.processIdentity(), result.processIdentity());
        assertEquals(original.lastHeartbeatEpochMillis(), result.lastHeartbeatEpochMillis());
    }

    @Test
    void foreignCloseDoesNotClassifyLiveTrackedTerminalProcess(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("foreign-live"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 1234L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);
        ProcessInspector live = pid -> Optional.of(
                new ProcessInspector.ProcessDetails(pid, "java", "java -jar synesis.jar", true));

        SessionLeaseService service = new SessionLeaseService(store, live);
        assertFalse(service.markClosedCleanly(controlRoot, original.connectionInstanceId(), 5678L));
        assertEquals(original,
                store.load(controlRoot, original.connectionInstanceId())
                        .orElseThrow());
    }

    @Test
    void wrongConnectionCloseDoesNotMutateTerminalLease(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("wrong-connection"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 1234L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);

        SessionLeaseService service = new SessionLeaseService(store, _ -> Optional.empty());
        assertFalse(service.markClosedCleanly(controlRoot, "different-connection", 1234L));
        assertEquals(original,
                store.load(controlRoot, original.connectionInstanceId())
                        .orElseThrow());
    }

    @Test
    void terminalDisconnectedCleanClosePreservesPersistedHistory(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("preserve-history"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_DISCONNECTED, 5678L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);

        new SessionLeaseService(store, pid -> Optional.of(
                new ProcessInspector.ProcessDetails(pid, "java", "java -jar synesis.jar", true)))
                .markClosedCleanly(controlRoot, original.connectionInstanceId());

        assertEquals(original,
                store.load(controlRoot, original.connectionInstanceId())
                        .orElseThrow());
    }

    @Test
    void abnormalFinalizationRequiresTerminalAuthority(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("active"));
        SessionLeaseRecord original = record(SessionLeaseState.ACTIVE, 2468L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);

        assertFalse(new SessionLeaseService(store, ProcessInspector.system())
                .markTerminalDisconnected(controlRoot,
                        original.connectionInstanceId(),
                        original.processIdentity()
                                .pid()));
        assertEquals(original,
                store.load(controlRoot, original.connectionInstanceId())
                        .orElseThrow());
    }

    @Test
    void terminalAbnormalFinalizationIsReplaySafe(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("replay"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 1357L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);
        SessionLeaseService service = new SessionLeaseService(store, ProcessInspector.system());

        assertTrue(service.markTerminalDisconnected(controlRoot, original.connectionInstanceId(), 1357L));
        SessionLeaseRecord disconnected = store.load(controlRoot, original.connectionInstanceId())
                .orElseThrow();
        assertTrue(service.markTerminalDisconnected(controlRoot, original.connectionInstanceId(), 1357L));
        service.markClosedCleanly(controlRoot, original.connectionInstanceId());

        assertEquals(disconnected,
                store.load(controlRoot, original.connectionInstanceId())
                        .orElseThrow());
    }

    @Test
    void concurrentCleanAndAbnormalFinalizationHasOneDurableWinner(@TempDir Path tempDir) throws Exception {
        Path controlRoot = initializedProject(tempDir.resolve("race"));
        SessionLeaseRecord original = record(SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED, 8642L);
        SessionLeaseStore store = new SessionLeaseStore();
        store.save(controlRoot, original);
        SessionLeaseService clean = new SessionLeaseService(store, pid -> Optional.of(
                new ProcessInspector.ProcessDetails(pid, "java", "java -jar synesis.jar", true)));
        SessionLeaseService abnormal = new SessionLeaseService(store, ProcessInspector.system());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanFuture = executor.submit(() -> {
                ready.countDown();
                await(start);
                clean.markClosedCleanly(controlRoot, original.connectionInstanceId(), 8642L);
            });
            Future<?> abnormalFuture = executor.submit(() -> {
                ready.countDown();
                await(start);
                abnormal.markTerminalDisconnected(controlRoot, original.connectionInstanceId(), 8642L);
            });
            ready.await();
            start.countDown();
            cleanFuture.get();
            abnormalFuture.get();
        } finally {
            executor.shutdownNow();
        }

        SessionLeaseState finalState = store.load(controlRoot, original.connectionInstanceId())
                .orElseThrow()
                .leaseState();
        assertTrue(finalState == SessionLeaseState.CLOSED_CLEANLY
                || finalState == SessionLeaseState.TERMINAL_DISCONNECTED);
        if (finalState == SessionLeaseState.CLOSED_CLEANLY) {
            assertFalse(abnormal.markTerminalDisconnected(controlRoot, original.connectionInstanceId(), 8642L));
        } else {
            clean.markClosedCleanly(controlRoot, original.connectionInstanceId(), 8642L);
            assertEquals(SessionLeaseState.TERMINAL_DISCONNECTED,
                    store.load(controlRoot, original.connectionInstanceId())
                            .orElseThrow()
                            .leaseState());
        }
    }

    @Test
    void terminalAuthorityWithMissingProcessRemainsNonRecoverable() {
        long start = System.currentTimeMillis();
        SessionLeaseRecord record = new SessionLeaseRecord(1, "project", "codex", "connection", "worker",
                "session", new SessionProcessIdentity(99999L, "java", "java", start, "nonce"),
                "0.1.0-SNAPSHOT", start, start, SessionLeaseState.TERMINAL_AUTHORITY_CONFIRMED);
        SessionLeasePolicy policy = new SessionLeasePolicy(
                Clock.fixed(Instant.ofEpochMilli(start + Duration.ofMinutes(10)
                        .toMillis()), ZoneId.of("UTC")),
                Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5));

        SessionLeaseState state = new SessionLeaseService(new SessionLeaseStore(),
                _ -> Optional.empty()).evaluateLiveness(record, policy);

        assertEquals(SessionLeaseState.TERMINAL_DISCONNECTED, state);
    }
}
