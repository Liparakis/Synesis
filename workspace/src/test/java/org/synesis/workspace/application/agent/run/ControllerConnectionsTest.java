package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;

/**
 * Verifies that the controller closes exactly the connection of the process it saw exit.
 */
final class ControllerConnectionsTest {

    private static SessionLeaseRecord activeLease(String connectionInstanceId) {
        long now = System.currentTimeMillis();
        return new SessionLeaseRecord(1, "project", "codex", connectionInstanceId, "worker", "session-" + connectionInstanceId,
                new SessionProcessIdentity(999_999L, "java", "java -jar synesis.jar", now, "nonce"), "0.1.0-SNAPSHOT", now - 1_000L, now, SessionLeaseState.ACTIVE);
    }

    @Test
    void endedConnectionIsClosedAndNoOtherLeaseIsTouched(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        SessionLeaseStore store = new SessionLeaseStore();
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());

        String killed = connections.open();
        String stillRunning = connections.open();
        assertNotEquals(killed, stillRunning, "every process start is its own connection");
        assertTrue(new HeadlessOptions(null, null).withConnection(killed).connectionInstanceId().equals(killed), "the identity must be usable as a provider argument");
        store.save(project, activeLease(killed));
        store.save(project, activeLease(stillRunning));

        connections.ended(killed);

        assertEquals(SessionLeaseState.CLOSED_CLEANLY, store.load(project, killed).orElseThrow().leaseState(),
                "a lease whose server was killed with its provider is closed like one that reached end of input");
        assertEquals(SessionLeaseState.ACTIVE, store.load(project, stillRunning).orElseThrow().leaseState());
    }

    @Test
    void endIsRecordedAlthoughTheProjectLockWasHeldPastOneWait(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        SessionLeaseStore store = new SessionLeaseStore();
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());
        String ended = connections.open();
        store.save(project, activeLease(ended));

        CountDownLatch held = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try (ProjectAppendLock _ = ProjectAppendLock.acquire(project.resolve(".synesis").resolve("coordination"))) {
                held.countDown();
                // Longer than the two seconds one attempt waits for the lock.
                Thread.sleep(3_000L);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        held.await();
        connections.ended(ended);
        holder.join();

        assertEquals(SessionLeaseState.CLOSED_CLEANLY, store.load(project, ended).orElseThrow().leaseState(),
                "an end met by a busy project lock must still be recorded");
    }

    /**
     * A busy project lock decides nothing: ending the session over it would send a waiting agent's
     * lane to recovery although the controller will resume it.
     */
    @Test
    void aSessionIsNotGivenUpBecauseTheProjectLockWasBusy(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        SessionLeaseStore store = new SessionLeaseStore();
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());
        String waiting = connections.open();
        SessionLeaseRecord lease = activeLease(waiting);
        store.save(project, lease);

        CountDownLatch held = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try (ProjectAppendLock _ = ProjectAppendLock.acquire(project.resolve(".synesis").resolve("coordination"))) {
                held.countDown();
                Thread.sleep(3_000L);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        held.await();
        assertTrue(connections.keep(waiting), "nothing was decided, so the session is not given up");
        holder.join();

        assertEquals(lease, store.load(project, waiting).orElseThrow(), "nothing was written either");
        assertFalse(connections.keep(waiting), "once decided, a lease naming a process the controller did not start is not kept");
    }

    @Test
    void processThatNeverReachedSynesisLeavesNothingToClose(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());

        String neverUsed = connections.open();
        connections.ended(neverUsed);

        assertTrue(new SessionLeaseStore().load(project, neverUsed).isEmpty(), "ending an unknown connection must not invent a lease");
    }
}
