package org.synesis.workspace.application.workspace;

import java.nio.file.Path;
import java.util.Objects;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.agent.AgentWorkspaceGuidance;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

/**
 * Provides the single authoritative readiness predicate shared by session, read, mutation, command,
 * and next-action operations.
 *
 * <p>The predicate resolves the exact provider connection binding before
 * inspecting its worktree. It deliberately never selects a newest binding, because concurrent
 * provider connections may own different workers.
 *
 * @since 1.0
 */
public final class WorkspaceReadinessService {

    private final ProviderSessionBindingService bindingService;
    private final ProviderApplicationService providerService;

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
        this.providerService = new ProviderApplicationService();
    }

    /**
     * Builds the response for a project or session that is not ready to be inspected at all.
     *
     * @return retry response asking the caller to ensure its session
     */
    public static AgentResponse workspaceNotReady() {
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
    }

    /**
     * Builds the response for a target that exists in the control checkout but not in the assigned
     * worktree.
     *
     * @param controlRoot      control checkout
     * @param assignedWorktree assigned worktree
     * @return blocked response with relaunch guidance
     */
    public static AgentResponse workspaceMismatch(Path controlRoot, Path assignedWorktree) {
        return new AgentResponse(AgentStatus.BLOCKED,
                AgentReason.WORKSPACE_MISMATCH,
                null,
                new AgentWorkspaceGuidance(controlRoot.toString(),
                        assignedWorktree.toString(),
                        "The target exists in the control checkout, not this assigned worktree. " + "Stop native mutations and relaunch the provider from assignedWorktree."));
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
     * A worktree that could not be allocated for a reason a person must fix, such as a code remote this computer cannot
     * fetch from without a sign-in (ADR-0098). The next ensure retries the allocation, but retrying alone cannot
     * succeed, so the agent is told to stop and pass the detail on.
     */
    private static ReadinessResult allocationNeedsPerson(ProviderSessionBindingService.Binding binding) {
        String code = Objects.requireNonNullElse(binding.lastSeenState(), "WORKSPACE_ALLOCATION_FAILED");
        AgentResponse response = new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.REQUEST_HUMAN_HELP,
                java.util.Map.of("diagnostic", code, "detail", binding.lastSeenDetail()));
        return new ReadinessResult(false, null, null, response, code);
    }

    private static ReadinessResult unavailableProvider(String provider, String status) {
        AgentResponse response = new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROVIDER_INTEGRATION_REQUIRED, AgentNextAction.REQUEST_HUMAN_HELP, java.util.Map.of("provider", provider, "status", status));
        return new ReadinessResult(false, null, null, response, "PROVIDER_INTEGRATION_REQUIRED");
    }

    /**
     * Resolves and verifies the workspace for one provider connection.
     *
     * @param location             initialized project location
     * @param provider             stable provider identifier
     * @param connectionInstanceId provider connection identity
     * @return readiness result containing the exact binding when ready
     */
    public ReadinessResult assess(ProjectLocation location, String provider, String connectionInstanceId) {
        return assess(location, provider, connectionInstanceId, false, false, null);
    }

    /**
     * Resolves a mutation workspace, permitting a stale control base only when the exact target is
     * still owned by this binding's active collaboration claim.
     *
     * @param location             initialized project location
     * @param provider             stable provider identifier
     * @param connectionInstanceId provider connection identity
     * @param relativePath         repository-relative mutation target
     * @return readiness result containing the exact binding when ready
     */
    public ReadinessResult assessMutation(ProjectLocation location, String provider, String connectionInstanceId, String relativePath) {
        return assess(location, provider, connectionInstanceId, false, false, Objects.requireNonNull(relativePath, "relativePath"));
    }

    /**
     * Resolves a no-change lane while allowing a clean control-base advance.
     *
     * <p>The ordinary readiness predicate remains fail-closed for mutation and
     * command operations. A no-change completion does not mutate the repository, so a clean assigned
     * worktree can remain usable after another lane advances the control checkout.</p>
     *
     * @param location             initialized project location
     * @param provider             stable provider identifier
     * @param connectionInstanceId provider connection identity
     * @return readiness result containing the exact binding when ready
     */
    public ReadinessResult assessNoChange(ProjectLocation location, String provider, String connectionInstanceId) {
        return assess(location, provider, connectionInstanceId, true, false, null);
    }

    /**
     * Resolves a completion-only lane whose clean worker HEAD may contain a committed descendant of
     * the admission base.
     *
     * <p>This path is intentionally narrower than ordinary readiness: it is
     * used to project completion and publish an immutable snapshot, while ordinary reads, patches,
     * and commands remain generation-strict.</p>
     *
     * @param location             initialized project location
     * @param provider             stable provider identifier
     * @param connectionInstanceId provider connection identity
     * @return readiness result containing the exact binding when ready
     */
    public ReadinessResult assessCompletion(ProjectLocation location, String provider, String connectionInstanceId) {
        return assess(location, provider, connectionInstanceId, true, true, null);
    }

    private ReadinessResult assess(ProjectLocation location, String provider, String connectionInstanceId, boolean allowControlBaseAdvance, boolean allowCommittedGeneration, String mutationPath) {
        try {
            ProviderApplicationService.ProviderWorkAdmission providerAdmission = providerService.assessWorkAdmission(location, provider);
            if (!providerAdmission.admitted()) {
                return unavailableProvider(provider, providerAdmission.status());
            }
            var bindingOptional = bindingService.find(location, provider, connectionInstanceId);
            if (bindingOptional.isEmpty()) {
                return unavailable(AgentReason.SESSION_NOT_READY, "SESSION_NOT_READY");
            }
            ProviderSessionBindingService.Binding binding = bindingOptional.get();
            if ("PROVIDER_CONFIGURATION_CONFLICT".equals(binding.lastSeenState())) {
                return unavailable(AgentReason.PROVIDER_CONFIGURATION_CONFLICT, "PROVIDER_CONFIGURATION_CONFLICT");
            }
            if (binding.worktreePath() == null && binding.lastSeenDetail() != null) {
                return allocationNeedsPerson(binding);
            }
            if (!"BOUND".equals(binding.status()) || binding.worktreePath() == null) {
                return unavailable(AgentReason.WORKSPACE_STALE, "SESSION_NOT_ACTIVE");
            }
            Path worktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            ProviderSessionBindingService.WorkspaceCheck workspaceCheck = allowCommittedGeneration ? bindingService.verifyCompletionWorkspace(location, binding, worktree)
                    : allowControlBaseAdvance ? bindingService.verifyNoChangeWorkspace(location, binding, worktree) : bindingService.verifyWorkspace(location, binding, worktree);
            // Unclaimed reads and commands remain subject to the current control-base check.
            workspaceCheck = bindingService.continueAfterControlBaseAdvance(location, binding, worktree, workspaceCheck, mutationPath);
            if (!workspaceCheck.verified()) {
                return unavailable(AgentReason.WORKSPACE_STALE, workspaceCheck.code());
            }
            if (!"VERIFIED".equals(binding.providerTrustState())) {
                ProviderSessionBindingService.WorkspaceVerificationResult trust = bindingService.verifyWorkspaceTrust(location, provider, binding.sessionId(), worktree);
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

}
