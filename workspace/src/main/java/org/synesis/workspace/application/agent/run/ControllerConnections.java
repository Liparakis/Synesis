package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationService;

/**
 * Gives each agent the controller starts its connection identity, keeps the session of a connection
 * while the controller can still start a process on it, and records in the project's session leases
 * that the connection ended when it will not.
 *
 * <p>An MCP server that reaches end of input closes its own lease, and one that is killed with its
 * provider leaves an abrupt loss that Synesis only accepts after a grace period. Neither is true of
 * an agent that ended its turn to wait and will be resumed on the same connection, so the controller
 * keeps that session: the lease names the controller process, which renews it (ADR-0099, amendment).
 * When the controller ends a connection it has seen its process exit, which is proof of the same
 * kind as end of input, so it closes the lease the same way. It can only keep or end connections of
 * processes it started.</p>
 */
public final class ControllerConnections implements AgentRunController.Connections {

    /**
     * How often an end is offered to the lease store when the run is over. Each attempt waits two
     * seconds for the project lock, which a session being admitted or a snapshot being taken can hold
     * for longer. While the run lives an end is offered once per observation instead.
     */
    private static final int END_RECORD_ATTEMPTS = 15;

    private final Path projectRoot;
    private final SessionLeaseService leases;
    private final SessionLeasePolicy policy = new SessionLeasePolicy();

    /**
     * Creates the connections of one project.
     *
     * @param projectRoot control project root
     */
    public ControllerConnections(Path projectRoot) {
        this(projectRoot, new SessionLeaseService());
    }

    ControllerConnections(Path projectRoot, SessionLeaseService leases) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Override
    public String open() {
        String identity = "conn-instance-" + UUID.randomUUID();
        try {
            // Its process may open its lease and reach end of input before the controller's first
            // look; the reservation makes that lease name the controller from the start.
            leases.reserveKeeper(projectRoot, identity);
        } catch (IOException unwritable) {
            // The session is then kept from the controller's first look, as before.
        }
        return identity;
    }

    @Override
    public void ended(String connectionInstanceId) {
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        for (int attempt = 0; attempt < END_RECORD_ATTEMPTS; attempt++) {
            if (end(connectionInstanceId)) {
                return;
            }
        }
    }

    @Override
    public boolean end(String connectionInstanceId) {
        // A process that never reached Synesis, or a provider that did not pass the identity on,
        // left no lease under this name; there is then nothing to close. An end that is not recorded
        // leaves a kept lease naming the controller, so the lane looks held by a live session for as
        // long as the controller lives, and the agent resumed to continue it is offered nothing.
        if (!leases.markClosedCleanly(projectRoot, Objects.requireNonNull(connectionInstanceId, "connectionInstanceId"))) {
            return false;
        }
        try {
            leases.releaseKeeper(projectRoot, connectionInstanceId);
        } catch (IOException left) {
            // No process is given this identity again, so a reservation left behind is never read.
        }
        // The MCP server's own close recovered the lane at once; a kept session's server no longer
        // closes it, and with every agent stopped nobody else would.
        try {
            new ReconciliationService().reconcileEligible(projectRoot);
        } catch (Exception unavailable) {
            // The next admission or inbox read reconciles it.
        }
        return true;
    }

    @Override
    public boolean keep(String connectionInstanceId) {
        try {
            return leases.keep(projectRoot, Objects.requireNonNull(connectionInstanceId, "connectionInstanceId"), policy);
        } catch (IOException busy) {
            // Nothing was decided. The last renewal holds for minutes, and the next observation asks
            // again; ending the session over a busy lock would send its lane to recovery.
            return true;
        }
    }
}
