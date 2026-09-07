package org.synesis.workspace.application.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.lifecycle.RepositoryPortabilityService;

/**
 * Resolves and establishes provider session context ambiently for agent transports.
 *
 * <p>This service translates caller inputs (control project root, provider, connection-instance ID)
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
        Path root = request.projectRoot()
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project root path does not exist or is not a directory: " + root);
        }
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            throw new IllegalStateException("Not a Synesis project root: " + root);
        }
        String rootNormalized = root.toString()
                .replace('\\', '/');
        if (rootNormalized.contains("/.synesis/local/worktrees/")) {
            throw new IllegalStateException("Assigned worktree path cannot be used as control project root");
        }
        if (Files.isRegularFile(root.resolve(".git"))) {
            throw new IllegalStateException("Linked Git worktree cannot be used as control project root");
        }

        ProjectApplicationService.ProjectLocation location = projectService.locate(root);

        RepositoryPortabilityService.Report portability = portabilityService.preflight(root);
        if (!portability.portable()) {
            throw new IllegalStateException("REPOSITORY_NOT_PORTABLE");
        }

        ProviderApplicationService.ProviderWorkAdmission providerAdmission = providerService.assessWorkAdmission(
                location, request.provider());
        if (!providerAdmission.admitted()) {
            throw new IllegalStateException("PROVIDER_INTEGRATION_REQUIRED");
        }

        if (request.managedAdmission()) {
            ProviderSessionBindingService.Binding managedBinding = bindingService.find(location, request.provider(),
                            request.connectionInstanceId())
                    .orElseThrow(() -> new IllegalStateException("managed_binding_missing"));
            ManagedAttachmentRecord managed = ManagedAttachmentService.storeFor(location, managedBinding.sessionId())
                    .read()
                    .orElseThrow(() -> new IllegalStateException("managed_attachment_missing"));
            if (managed.mode()
                    != org.synesis.workspace.application.provider.continuity.ProviderContinuityMode.MANAGED_CONTINUITY
                    || managed.status() != ManagedAttachmentRecord.Status.ACTIVE) {
                throw new IllegalStateException("managed_attachment_not_active");
            }
        }

        ProviderSessionBindingService.BindingResult bindingResult = bindingService.ensure(
                location, request.provider(), request.connectionInstanceId());

        ProviderSessionBindingService.Binding binding;

        WorkspaceReadinessService.ReadinessResult readiness = readinessService.assess(
                location, request.provider(), request.connectionInstanceId());
        if (!readiness.ready()) {
            if ("PROVIDER_CONFIGURATION_CONFLICT".equals(readiness.internalReason())) {
                throw new IllegalStateException("PROVIDER_CONFIGURATION_CONFLICT");
            }
            throw new IllegalStateException("Workspace readiness failed: " + readiness.internalReason());
        }
        binding = readiness.binding();

        if ("REVOKED".equalsIgnoreCase(binding.status())
                || "COMPLETED".equalsIgnoreCase(binding.status())
                || "CANCELLED".equalsIgnoreCase(binding.status())) {
            throw new IllegalStateException("Session is in inactive status: " + binding.status());
        }

        boolean verified = "VERIFIED".equalsIgnoreCase(binding.verificationState());
        if (!verified) {
            throw new IllegalStateException("Worktree verification failed for session: " + binding.sessionId());
        }

        Path worktreePath = binding.worktreePath() != null ? Path.of(binding.worktreePath())
                                                             .toAbsolutePath()
                                                             .normalize() : null;
        boolean isIsolated = worktreePath != null && !worktreePath.equals(root);

        return new AgentSessionContext(
                binding.projectId(),
                binding.nodeId(),
                binding.sessionId(),
                binding.supervisorId(),
                binding.workerId(),
                worktreePath,
                binding.branch(),
                binding.baseCommit(),
                bindingResult.binding()
                        .providerTrustState(),
                0,
                isIsolated,
                binding
        );
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
            String worktree = context.worktreePath() == null ? null : context.worktreePath()
                                                                      .toString();
            return worktree == null
                    ? AgentResponse.ready(workspaceState, context.pendingCount())
                    : AgentResponse.ready(workspaceState, context.pendingCount(), worktree);
        } catch (IllegalArgumentException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        } catch (IllegalStateException ex) {
            if ("REPOSITORY_NOT_PORTABLE".equals(ex.getMessage())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REPOSITORY_NOT_PORTABLE,
                        AgentNextAction.REQUEST_HUMAN_HELP, null);
            }
            if ("PROVIDER_INTEGRATION_REQUIRED".equals(ex.getMessage())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROVIDER_INTEGRATION_REQUIRED,
                        AgentNextAction.REQUEST_HUMAN_HELP, null);
            }
            if (String.valueOf(ex.getMessage())
                    .contains("PROVIDER_CONFIGURATION_CONFLICT")) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROVIDER_CONFIGURATION_CONFLICT,
                        AgentNextAction.REQUEST_HUMAN_HELP,
                        java.util.Map.of("diagnostic", "PROVIDER_CONFIGURATION_CONFLICT"));
            }
            return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                    AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION,
                    null);
        } catch (ProviderSessionBindingService.BindingException ex) {
            if ("SESSION_TERMINAL".equals(ex.code())) {
                return new AgentResponse(AgentStatus.COMPLETED, null, null,
                        java.util.Map.of("state", "SESSION_TERMINAL"));
            }
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP, java.util.Map.of("reason", ex.code()));
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED,
                    AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    null);
        }
    }

    /**
     * Bounded task intent description provided optionally during session resolution.
     *
     * @param goal                  concise goal description
     * @param acceptance            concise acceptance criteria
     * @param likelyScopes          likely file or package scopes
     * @param knownDependencies     capability identifiers explicitly required by this task
     * @param workGroupId           optional logical work-group identifier
     * @param role                  semantic producer or reviewer role
     * @param reviewTargetSelectors non-ownership selectors identifying producer work this reviewer may review
     */
    public record AgentTaskIntent(
            String goal,
            String acceptance,
            List<String> likelyScopes,
            List<String> knownDependencies,
            UUID workGroupId,
            WorkIntent.Role role,
            List<ResourceSelector> reviewTargetSelectors
    ) {

        /**
         * Validates bounds on task intent strings and lists.
         */
        public AgentTaskIntent {
            if (goal != null && goal.length() > 4096) {
                throw new IllegalArgumentException("goal exceeds 4096 characters");
            }
            if (acceptance != null && acceptance.length() > 4096) {
                throw new IllegalArgumentException("acceptance exceeds 4096 characters");
            }
            if (likelyScopes != null && likelyScopes.size() > 50) {
                throw new IllegalArgumentException("likelyScopes exceeds 50 items");
            }
            if (knownDependencies == null) {
                knownDependencies = List.of();
            }
            if (knownDependencies.size() > 50) {
                throw new IllegalArgumentException("knownDependencies exceeds 50 items");
            }
            if (knownDependencies.stream()
                    .anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
                throw new IllegalArgumentException("knownDependencies entries must be bounded strings");
            }
            knownDependencies = List.copyOf(knownDependencies);
            role = role == null ? WorkIntent.Role.PRODUCER : role;
            reviewTargetSelectors = reviewTargetSelectors == null
                    ? List.of() : List.copyOf(reviewTargetSelectors);
            if (reviewTargetSelectors.size() > 128) {
                throw new IllegalArgumentException("reviewTargetSelectors exceeds 128 items");
            }
        }

        /**
         * Constructs an intent without an explicit parent work group.
         *
         * @param goal              concise goal
         * @param acceptance        acceptance criteria
         * @param likelyScopes      likely scopes
         * @param knownDependencies known dependencies
         */
        @SuppressWarnings("unused")
        public AgentTaskIntent(String goal, String acceptance, List<String> likelyScopes,
                List<String> knownDependencies) {
            this(goal, acceptance, likelyScopes, knownDependencies, null,
                    WorkIntent.Role.PRODUCER, List.of());
        }

        /**
         * Constructs an intent with an explicit parent work group.
         *
         * @param goal              concise goal
         * @param acceptance        acceptance criteria
         * @param likelyScopes      likely scopes
         * @param knownDependencies known dependencies
         * @param workGroupId       logical work-group identifier
         */
        @SuppressWarnings("unused")
        public AgentTaskIntent(String goal, String acceptance, List<String> likelyScopes,
                List<String> knownDependencies, UUID workGroupId) {
            this(goal, acceptance, likelyScopes, knownDependencies, workGroupId,
                    WorkIntent.Role.PRODUCER, List.of());
        }
    }

    /**
     * Request payload for ambient session resolution.
     *
     * @param projectRoot          canonical control project root path
     * @param provider             stable provider name (e.g. "codex", "claude")
     * @param connectionInstanceId unique process connection-instance ID
     * @param taskIntent           optional task intent description
     * @param refresh              {@code true} if a session refresh is explicitly requested
     * @param managedAdmission     {@code true} only after trusted managed proof admission
     */
    public record SessionResolutionRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            AgentTaskIntent taskIntent,
            boolean refresh,
            boolean managedAdmission
    ) {

        /**
         * Validates required request parameters.
         */
        public SessionResolutionRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("provider is required");
            }
            if (connectionInstanceId == null || connectionInstanceId.isBlank()) {
                throw new IllegalArgumentException("connectionInstanceId is required");
            }
        }

        /**
         * Constructs ordinary session-bound resolution parameters.
         *
         * @param projectRoot          project root
         * @param provider             provider identifier
         * @param connectionInstanceId connection selector
         * @param taskIntent           optional task intent
         * @param refresh              explicit refresh flag
         */
        public SessionResolutionRequest(Path projectRoot, String provider, String connectionInstanceId,
                AgentTaskIntent taskIntent, boolean refresh) {
            this(projectRoot, provider, connectionInstanceId, taskIntent, refresh, false);
        }
    }

    /**
     * Internal diagnostic context representing an active, verified agent session.
     *
     * <p>This object contains internal details (IDs, worktree path, base commit) and MUST NOT
     * be returned directly in normal agent-facing responses.
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
     * @param isIsolatedWorkspace {@code true} if an isolated worktree is assigned
     * @param binding             durable session binding record
     */
    public record AgentSessionContext(
            String projectId,
            String nodeId,
            String sessionId,
            String supervisorId,
            String workerId,
            Path worktreePath,
            String branch,
            String baseCommit,
            String providerTrustState,
            int pendingCount,
            boolean isIsolatedWorkspace,
            ProviderSessionBindingService.Binding binding
    ) {

        /**
         * Validates required session context fields.
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
}
