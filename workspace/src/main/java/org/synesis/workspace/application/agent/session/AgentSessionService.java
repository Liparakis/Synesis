package org.synesis.workspace.application.agent.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.lifecycle.RepositoryPortabilityService;

/**
 * Resolves and establishes provider session context ambiently for agent transports.
 *
 * <p>This service translates caller inputs (control project root, provider, connection-instance
 * ID)
 * into a verified, project-scoped provider session binding with an allocated isolated worktree.
 * Internal diagnostic details (IDs, commit SHAs, worktree paths) are retained in the internal
 * {@link AgentSessionContext} and are strictly omitted from the concise {@link AgentResponse}.
 *
 * @since 1.0
 */
public final class AgentSessionService {

    private final ProjectApplicationService projectService;
    private final ProviderApplicationService providerService;
    private final ProviderSessionBindingService bindingService;
    private final WorkspaceReadinessService readinessService;
    private final RepositoryPortabilityService portabilityService;

    /**
     * Creates an agent session service using default application services.
     */
    public AgentSessionService() {
        this(new ProjectApplicationService(), new ProviderSessionBindingService());
    }

    /**
     * Creates an agent session service with explicit application services.
     *
     * @param projectService application service for project location and discovery
     * @param bindingService application service for provider session binding and worktrees
     */
    public AgentSessionService(ProjectApplicationService projectService, ProviderSessionBindingService bindingService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.providerService = new ProviderApplicationService();
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.readinessService = new WorkspaceReadinessService(this.bindingService);
        this.portabilityService = new RepositoryPortabilityService();
    }

    /**
     * Resolves and verifies the ambient session context internally.
     *
     * @param request session resolution parameters
     * @return verified internal session context
     * @throws Exception if project location, session binding, or worktree verification fails
     */
    public AgentSessionContext resolveSessionContext(SessionResolutionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project root path does not exist or is not a directory: " + root);
        }
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            throw new IllegalStateException("Not a Synesis project root: " + root);
        }
        String rootNormalized = root.toString().replace('\\', '/');
        if (rootNormalized.contains("/.synesis/local/worktrees/")) {
            throw new IllegalStateException("Assigned worktree path cannot be used as control project root");
        }
        if (Files.isRegularFile(root.resolve(".git"))) {
            throw new IllegalStateException("Linked Git worktree cannot be used as control project root");
        }

        ProjectLocation location = projectService.locate(root);

        RepositoryPortabilityService.Report portability = portabilityService.preflight(root);
        if (!portability.portable()) {
            throw AgentSessionFailure.of(AgentSessionFailure.Code.REPOSITORY_NOT_PORTABLE);
        }

        ProviderApplicationService.ProviderWorkAdmission providerAdmission = providerService.assessWorkAdmission(location, request.provider());
        if (!providerAdmission.admitted()) {
            throw AgentSessionFailure.of(AgentSessionFailure.Code.PROVIDER_INTEGRATION_REQUIRED);
        }

        if (request.managedAdmission()) {
            ProviderSessionBindingService.Binding managedBinding = bindingService.find(location, request.provider(), request.connectionInstanceId())
                    .orElseThrow(() -> new IllegalStateException("managed_binding_missing"));
            ManagedAttachmentRecord managed = ManagedAttachmentService.storeFor(location, managedBinding.sessionId()).read().orElseThrow(() -> new IllegalStateException("managed_attachment_missing"));
            if (managed.mode() != org.synesis.workspace.application.provider.continuity.ProviderContinuityMode.MANAGED_CONTINUITY || managed.status() != ManagedAttachmentRecord.Status.ACTIVE) {
                throw new IllegalStateException("managed_attachment_not_active");
            }
        }

        ProviderSessionBindingService.BindingResult bindingResult = bindingService.ensure(location, request.provider(), request.connectionInstanceId());

        ProviderSessionBindingService.Binding binding;

        ReadinessResult readiness = bindingService.hasActiveNoChangeIntent(location, bindingResult.binding()) ? readinessService.assessNoChange(location, request.provider(), request.connectionInstanceId())
                : readinessService.assess(location, request.provider(), request.connectionInstanceId());
        if (!readiness.ready()) {
            if ("PROVIDER_CONFIGURATION_CONFLICT".equals(readiness.internalReason())) {
                throw AgentSessionFailure.of(AgentSessionFailure.Code.PROVIDER_CONFIGURATION_CONFLICT);
            }
            throw AgentSessionFailure.workspaceNotReady(readiness);
        }
        binding = readiness.binding();

        if ("REVOKED".equalsIgnoreCase(binding.status()) || "COMPLETED".equalsIgnoreCase(binding.status()) || "CANCELLED".equalsIgnoreCase(binding.status())) {
            throw new IllegalStateException("Session is in inactive status: " + binding.status());
        }

        boolean verified = "VERIFIED".equalsIgnoreCase(binding.verificationState());
        if (!verified) {
            throw new IllegalStateException("Worktree verification failed for session: " + binding.sessionId());
        }

        Path worktreePath = binding.worktreePath() != null ? Path.of(binding.worktreePath()).toAbsolutePath().normalize() : null;
        boolean isIsolated = worktreePath != null && !worktreePath.equals(root);

        return new AgentSessionContext(binding.projectId(),
                binding.nodeId(),
                binding.sessionId(),
                binding.supervisorId(),
                binding.workerId(),
                worktreePath,
                binding.branch(),
                binding.baseCommit(),
                bindingResult.binding().providerTrustState(),
                0,
                isIsolated,
                binding);
    }

    /**
     * Executes the {@code synesis.ensure_session} operation, returning a concise agent response.
     *
     * @param request session resolution parameters
     * @return concise agent response (status ready, retry_required, blocked, or failed)
     */
    public AgentResponse ensureSession(SessionResolutionRequest request) {
        try {
            AgentSessionContext context = resolveSessionContext(request);
            String workspaceState = context.isIsolatedWorkspace() ? "isolated" : "ready";
            String worktree = context.worktreePath() == null ? null : context.worktreePath().toString();
            return worktree == null ? AgentResponse.ready(workspaceState, context.pendingCount()) : AgentResponse.ready(workspaceState, context.pendingCount(), worktree);
        } catch (IllegalArgumentException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        } catch (AgentSessionFailure ex) {
            return failureResponse(ex);
        } catch (IllegalStateException ex) {
            return workspaceNotReady(null);
        } catch (ProviderSessionBindingService.BindingException ex) {
            if ("SESSION_TERMINAL".equals(ex.code())) {
                return new AgentResponse(AgentStatus.COMPLETED, null, null, java.util.Map.of("state", "SESSION_TERMINAL"));
            }
            if (AgentResponse.SESSION_TERMINAL_STATE_UNAVAILABLE.equals(ex.code())) {
                return AgentResponse.sessionTerminalStateUnavailable(ex);
            }
            if ("SESSION_AUTHORITY_OPEN".equals(ex.code())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, java.util.Map.of("reason", "SESSION_AUTHORITY_OPEN", "openAuthority",
                        java.util.List.of(ex.getMessage().split(",")), "instruction",
                        "This session's lane has ended, but it still holds what openAuthority lists. Call get_next_action and finish that first; then call ensure_session again to start a fresh session for your next task."));
            }
            if ("WORKSPACE_STALE_DIRTY".equals(ex.code())) {
                // The refusal left the binding and its worktree exactly as they were, and the lane is still the
                // agent's to finish. Sent to a person, the agent stopped (s5-run8).
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_STALE, AgentNextAction.RETRY, java.util.Map.of("reason", "WORKSPACE_STALE_DIRTY", "instruction",
                        "Your worktree holds work that is not yet published, and this computer's main has moved where Synesis cannot carry that work across safely, "
                                + "so your session, lane and worktree were left exactly as they were and nothing was lost. No person is needed. "
                                + "Call get_next_action and follow the action it projects for your lane; call ensure_session again only once that lane has ended."));
            }
            // The code alone left nothing to diagnose when a session could not be bound once in a real run.
            return AgentFailures.internal(request.projectRoot(), "session.bind", ex, java.util.Map.of("reason", ex.code()));
        } catch (Exception ex) {
            return AgentFailures.internal(request.projectRoot(), "session.ensure", ex, null);
        }
    }

    private static AgentResponse failureResponse(AgentSessionFailure failure) {
        return switch (failure.code()) {
            case REPOSITORY_NOT_PORTABLE -> new AgentResponse(AgentStatus.BLOCKED, AgentReason.REPOSITORY_NOT_PORTABLE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            case PROVIDER_INTEGRATION_REQUIRED -> new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROVIDER_INTEGRATION_REQUIRED, AgentNextAction.REQUEST_HUMAN_HELP, null);
            case PROVIDER_CONFIGURATION_CONFLICT ->
                    new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROVIDER_CONFIGURATION_CONFLICT, AgentNextAction.REQUEST_HUMAN_HELP, java.util.Map.of("diagnostic", "PROVIDER_CONFIGURATION_CONFLICT"));
            case WORKSPACE_NOT_READY -> failure.answer() != null ? failure.answer() : workspaceNotReady(failure.diagnostic());
        };
    }

    private static AgentResponse workspaceNotReady(String diagnostic) {
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, diagnostic == null ? null : java.util.Map.of("diagnostic", diagnostic));
    }
}
