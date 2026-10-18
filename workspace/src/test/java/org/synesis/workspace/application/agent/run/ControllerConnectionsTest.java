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

    /** While the run lives an end is tried once, so a busy lock costs one look and the end is offered again. */
    @Test
    void anEndTriedWhileTheLockIsBusyIsNotRecordedAndIsRecordedWhenOfferedAgain(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        SessionLeaseStore store = new SessionLeaseStore();
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());
        String ended = connections.open();
        store.save(project, activeLease(ended));

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try (ProjectAppendLock _ = ProjectAppendLock.acquire(project.resolve(".synesis").resolve("coordination"))) {
                held.countDown();
                release.await();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        held.await();
        assertFalse(connections.end(ended), "a busy project records nothing");
        assertEquals(SessionLeaseState.ACTIVE, store.load(project, ended).orElseThrow().leaseState());
        release.countDown();
        holder.join();

        assertTrue(connections.end(ended));
        assertEquals(SessionLeaseState.CLOSED_CLEANLY, store.load(project, ended).orElseThrow().leaseState());
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

    /**
     * A first process that opened its lease and reached end of input before the controller's first
     * look (2 s) closed it, and its agent lost the session the controller meant to keep. The lease of
     * a connection the controller opened names the controller from the start.
     */
    @Test
    void aProcessThatOpensAndClosesItsLeaseBeforeTheControllerLooksLeavesTheSessionKept(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());
        String quick = connections.open();

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process server = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), QuickServer.class.getName(), project.toString(), quick).redirectErrorStream(true).start();
        String output = new String(server.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(server.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, server.exitValue(), output);

        assertTrue(connections.keep(quick), "the controller keeps the session of the process that has already closed: " + output);
        SessionLeaseRecord kept = new SessionLeaseStore().load(project, quick).orElseThrow();
        assertEquals(SessionLeaseState.ACTIVE, kept.leaseState());
        assertEquals(ProcessHandle.current().pid(), kept.processIdentity().pid());

        connections.ended(quick);
        assertEquals(SessionLeaseState.CLOSED_CLEANLY, new SessionLeaseStore().load(project, quick).orElseThrow().leaseState());
    }

    /** An MCP server that opens its lease and closes it at end of input at once, as a quick first process does. */
    public static final class QuickServer {

        private QuickServer() {
        }

        /**
         * Opens and closes the lease of one connection.
         *
         * @param arguments the project root and the connection identity
         * @throws Exception when the lease cannot be written
         */
        public static void main(String[] arguments) throws Exception {
            Path project = Path.of(arguments[0]);
            SessionLeaseService leases = new SessionLeaseService();
            leases.createOrRenewLease(project, "project", "codex", arguments[1], "node", "session-quick", new org.synesis.workspace.lifecycle.lease.SessionLeasePolicy());
            System.out.println("CLOSED=" + leases.markClosedCleanly(project, arguments[1], ProcessHandle.current().pid()));
        }
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
