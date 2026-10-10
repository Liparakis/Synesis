package org.synesis.mcp.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;


import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Renews this connection's session lease and collaboration heartbeat after verified MCP activity.
 *
 * <p>The collaboration heartbeat is the authority fence and is checked before the external lease is
 * renewed, so a recovery-held session cannot make its lease look active again. It is separate from
 * the handler because both the tool gate and the command path renew through the same exact-binding
 * sequence, and it needs the connection's services but none of its mutable state.</p>
 */
final class McpSessionLease {

    /**
     * Service that reads and mutates durable collaboration state.
     */
    private final WorkspaceCollaborationService collaborationService;
    /**
     * Resolves the exact provider and connection binding used for authority checks.
     */
    private final SessionAuthorityResolver authorityResolver;
    /**
     * Persists and closes this connection's session lease.
     */
    private final SessionLeaseService leaseService;
    /**
     * Policy used to bound session-lease behavior.
     */
    private final SessionLeasePolicy leasePolicy;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;

    /**
     * Creates the lease renewal path for one connection.
     *
     * @param authorityResolver    resolver for the exact provider and connection binding
     * @param collaborationService service that records the heartbeat on the collaboration lane
     * @param leaseService         service that persists this connection's session lease
     * @param leasePolicy          policy used to bound session-lease behavior
     * @param provider             stable provider ID associated with this connection's binding
     * @param connectionInstanceId connection incarnation used to prevent cross-connection authority reuse
     */
    McpSessionLease(SessionAuthorityResolver authorityResolver,
            WorkspaceCollaborationService collaborationService,
            SessionLeaseService leaseService,
            SessionLeasePolicy leasePolicy,
            String provider,
            String connectionInstanceId) {
        this.authorityResolver = authorityResolver;
        this.collaborationService = collaborationService;
        this.leaseService = leaseService;
        this.leasePolicy = leasePolicy;
        this.provider = provider;
        this.connectionInstanceId = connectionInstanceId;
    }

    /**
     * Captures stable process evidence and a unique incarnation for this MCP connection.
     *
     * @param connectionInstanceId caller-selected connection identifier
     * @return immutable process identity used by lease and shutdown fencing
     */
    static SessionProcessIdentity captureProcessIdentity(String connectionInstanceId) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElse("unknown");
        String commandLine = info.commandLine().orElse(executable);
        long start = info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        return new SessionProcessIdentity(ProcessHandle.current().pid(), executable, commandLine, start, connectionInstanceId + ":" + UUID.randomUUID());
    }

    /**
     * Renews the exact session lease after verified MCP activity.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     * @return {@code true} when the lease was renewed, {@code false} for an unbound connection
     */
    boolean renewLease(Path activeProjectRoot) {
        try {
            renewLeaseAndHeartbeat(activeProjectRoot);
            return true;
        } catch (Exception ignored) {
            // Unbound requests are handled by the session and workspace policy paths.
            return false;
        }
    }

    /**
     * Resolves the exact bound session, renews its lease, and heartbeats its collaboration lane.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the located project
     * @throws Exception when the project, binding, identity, or lease cannot be resolved or persisted
     */
    ProjectLocation renewLeaseAndHeartbeat(Path activeProjectRoot) throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
        var binding = authorityResolver.resolve(location, provider, connectionInstanceId);
        String nodeId = location.loadOrCreateIdentity().nodeId();
        // The collaboration event is the authority fence. Check it before renewing the external
        // lease so a stale recovery-held session cannot make its lease appear active again.
        collaborationService.heartbeatIfPresent(activeProjectRoot, provider, connectionInstanceId);
        leaseService.createOrRenewLease(activeProjectRoot, location.projectId().toString(), provider, connectionInstanceId, nodeId, binding.sessionId(), leasePolicy);
        return location;
    }
}
