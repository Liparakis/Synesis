package org.synesis.workspace.application.agent.run;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import org.synesis.workspace.lifecycle.lease.SessionLeaseService;

/**
 * Gives each provider process the controller starts its own connection identity, and records in
 * the project's session leases that the connection ended when the process exits.
 *
 * <p>An MCP server that reaches end of input closes its own lease. One that is killed with its
 * provider cannot, and its lease then looks like an abrupt loss that Synesis only accepts after a
 * grace period. The controller watched its child exit, which is proof of the same kind as end of
 * input, so it closes the lease the same way. It can only name connections it opened itself.</p>
 */
public final class ControllerConnections implements AgentRunController.Connections {

    private final Path projectRoot;
    private final SessionLeaseService leases;

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
        return "conn-instance-" + UUID.randomUUID();
    }

    @Override
    public void ended(String connectionInstanceId) {
        // A process that never reached Synesis, or a provider that did not pass the identity on,
        // left no lease under this name; there is then nothing to close.
        leases.markClosedCleanly(projectRoot, Objects.requireNonNull(connectionInstanceId, "connectionInstanceId"));
    }
}
