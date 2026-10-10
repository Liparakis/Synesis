package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void processThatNeverReachedSynesisLeavesNothingToClose(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        new ProjectApplicationService().init(project);
        ControllerConnections connections = new ControllerConnections(project, new SessionLeaseService());

        String neverUsed = connections.open();
        connections.ended(neverUsed);

        assertTrue(new SessionLeaseStore().load(project, neverUsed).isEmpty(), "ending an unknown connection must not invent a lease");
    }
}
