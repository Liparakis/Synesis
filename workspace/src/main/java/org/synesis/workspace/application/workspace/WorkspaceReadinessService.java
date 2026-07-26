package org.synesis.workspace.application.workspace;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

import org.synesis.workspace.application.ProjectApplicationService;

import java.nio.file.Path;
import java.util.Objects;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Provides the single authoritative readiness predicate shared by session,
 * read, mutation, command, and next-action operations.
 *
 * <p>The predicate resolves the exact provider connection binding before
 * inspecting its worktree. It deliberately never selects a newest binding,
 * because concurrent provider connections may own different workers.
 *
 * @since 1.0
 */
public final class WorkspaceReadinessService {

    private final ProviderSessionBindingService bindingService;

    /**
     * Creates a readiness service with the default binding service.
     */
    public WorkspaceReadinessService() {
        this(new ProviderSessionBindingService());
    }

    /**
     * Creates a readiness service with an explicit binding service.
     *
     * @param bindingService binding and worktree service
     */
    public WorkspaceReadinessService(ProviderSessionBindingService bindingService) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
    }

    /**
     * Resolves and verifies the workspace for one provider connection.
     *
     * @param location initialized project location
     * @param provider stable provider identifier
     * @param connectionInstanceId provider connection identity
     * @return readiness result containing the exact binding when ready
     */
    public ReadinessResult assess(ProjectApplicationService.ProjectLocation location,
            String provider, String connectionInstanceId) {
        try {
            var bindingOptional = bindingService.find(location, provider, connectionInstanceId);
            if (bindingOptional.isEmpty()) {
                return unavailable(AgentReason.SESSION_NOT_READY, "SESSION_NOT_READY");
            }
            ProviderSessionBindingService.Binding binding = bindingOptional.get();
            if (!"BOUND".equals(binding.status()) || binding.worktreePath() == null) {
                return unavailable(AgentReason.WORKSPACE_STALE, "SESSION_NOT_ACTIVE");
            }
            Path worktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            ProviderSessionBindingService.WorkspaceCheck workspaceCheck =
                    bindingService.verifyWorkspace(location, binding, worktree);
            if (!workspaceCheck.verified()) {
                return unavailable(AgentReason.WORKSPACE_STALE, workspaceCheck.code());
            }
            if (!"VERIFIED".equals(binding.providerTrustState())) {
                ProviderSessionBindingService.WorkspaceVerificationResult trust =
                        bindingService.verifyWorkspaceTrust(location, provider, binding.sessionId(), worktree);
                if (!trust.verified()) {
                    return unavailable(AgentReason.WORKSPACE_STALE, trust.code());
                }
                bindingOptional = bindingService.find(location, provider, connectionInstanceId);
                if (bindingOptional.isEmpty()) {
                    return unavailable(AgentReason.WORKSPACE_STALE, "BINDING_RELOAD_FAILED");
                }
                binding = bindingOptional.get();
                worktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            }
            return new ReadinessResult(true, binding, worktree, null, "WORKSPACE_VERIFIED");
        } catch (Exception failure) {
            return unavailable(AgentReason.WORKSPACE_NOT_READY, "WORKSPACE_UNVERIFIED");
        }
    }

    private static ReadinessResult unavailable(AgentReason reason, String internalReason) {
        if ("WORKSPACE_GENERATION_MISMATCH".equals(internalReason)) {
            reason = AgentReason.WORKSPACE_GENERATION_CHANGED;
        }
        AgentNextAction action = AgentNextAction.ENSURE_SESSION;
        AgentResponse response = new AgentResponse(AgentStatus.RETRY_REQUIRED, reason, action, null);
        return new ReadinessResult(false, null, null, response, internalReason);
    }

    /**
     * Result of the shared workspace readiness predicate.
     *
     * @param ready          whether the exact connection workspace is ready
     * @param binding        verified binding when ready
     * @param worktree       verified worker worktree when ready
     * @param response       bounded response when not ready
     * @param internalReason bounded internal classification for diagnostics
     */
    public record ReadinessResult(boolean ready,
            ProviderSessionBindingService.Binding binding,
            Path worktree,
            AgentResponse response,
            String internalReason) {
        /**
         * Validates readiness result consistency.
         */
        public ReadinessResult {
            Objects.requireNonNull(internalReason, "internalReason");
            if (ready && (binding == null || worktree == null || response != null)) {
                throw new IllegalArgumentException("ready result must contain binding and worktree only");
            }
            if (!ready && (binding != null || worktree != null || response == null)) {
                throw new IllegalArgumentException("unready result must contain response only");
            }
        }
    }
}
