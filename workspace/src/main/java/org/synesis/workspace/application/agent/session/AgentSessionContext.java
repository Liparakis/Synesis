package org.synesis.workspace.application.agent.session;

import java.nio.file.Path;

import java.util.Objects;

import org.synesis.workspace.application.provider.ProviderSessionBindingService;

/**
 * Internal diagnostic context for one active, verified agent session.
 *
 * <p>Contains internal IDs, the assigned worktree, and base commit. Do not return this object in
 * normal agent-facing responses.</p>
 *
 * @param projectId           durable project ID
 * @param nodeId              stable node ID
 * @param sessionId           durable session ID
 * @param supervisorId        durable supervisor ID
 * @param workerId            durable worker ID
 * @param worktreePath        allocated Git worktree path
 * @param branch              allocated Git branch
 * @param baseCommit          base Git commit SHA
 * @param providerTrustState  workspace trust state
 * @param pendingCount        number of pending coordination items
 * @param isIsolatedWorkspace whether an isolated worktree is assigned
 * @param binding             exact durable provider session binding
 */
public record AgentSessionContext(String projectId, String nodeId, String sessionId, String supervisorId, String workerId, Path worktreePath, String branch, String baseCommit, String providerTrustState, int pendingCount,
                                  boolean isIsolatedWorkspace, ProviderSessionBindingService.Binding binding) {

    /**
     * Validates the authoritative session identifiers and binding.
     */
    public AgentSessionContext {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerTrustState, "providerTrustState");
        Objects.requireNonNull(binding, "binding");
    }
}
