package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.lifecycle.codex.CodexLifecycleHttpClient;
import org.synesis.workspace.lifecycle.codex.LifecycleControlRequestEnvelope;

/**
 * Production caller boundary for Codex lifecycle commands.
 *
 * <p>START first invokes the existing Synesis session and collaboration
 * workflow. It may establish the binding, participant, WorkIntent, acquired
 * claim, lane, epoch, and worktree, but it cannot launch Codex or write the
 * lifecycle idempotency ledger during that phase. Only the resulting immutable
 * authority context is submitted to the lifecycle owner. The class is
 * thread-safe because each invocation freezes its own request envelope.
 *
 * @since 1.0
 */
public final class ProviderSessionCommand {

    private final AgentSessionService sessionService;
    private final WorkspaceCollaborationService collaborationService;
    private final ProviderSessionBindingService bindingService;

    /** Creates a command boundary with existing Synesis application services. */
    public ProviderSessionCommand() {
        this(new AgentSessionService(), new WorkspaceCollaborationService(), new ProviderSessionBindingService());
    }

    /**
     * Creates an injectable command boundary.
     *
     * @param sessionService existing session authority service
     * @param collaborationService existing claim/work-intent service
     * @param bindingService exact binding lookup service
     */
    public ProviderSessionCommand(AgentSessionService sessionService,
            WorkspaceCollaborationService collaborationService, ProviderSessionBindingService bindingService) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.collaborationService = Objects.requireNonNull(collaborationService, "collaborationService");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
    }

    /**
     * Immutable START input supplied by a provider-facing caller.
     *
     * @param projectRoot control project root
     * @param endpoint existing coordination host endpoint
     * @param hostInstanceId exact production owner instance ID
     * @param connectionInstanceId exact provider connection identity
     * @param goal WorkIntent goal
     * @param acceptance WorkIntent acceptance criteria
     * @param selectors repository-relative claim selectors
     * @param input explicit initial Codex turn input
     * @param expectedLifecycleRevision expected owner revision
     * @param callerDeadlineEpochMillis original absolute deadline
     * @param options semantic lifecycle options
     */
    public record StartRequest(Path projectRoot, URI endpoint, String hostInstanceId,
            String connectionInstanceId, String goal, String acceptance, List<ResourceSelector> selectors,
            String input, long expectedLifecycleRevision, long callerDeadlineEpochMillis,
            Map<String, String> options) {
        /** Validates and freezes caller input. */
        public StartRequest {
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
            Objects.requireNonNull(endpoint, "endpoint");
            require(hostInstanceId, "hostInstanceId");
            require(connectionInstanceId, "connectionInstanceId");
            require(goal, "goal");
            require(acceptance, "acceptance");
            selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
            if (selectors.isEmpty()) {
                throw new IllegalArgumentException("at least one claim selector is required");
            }
            require(input, "input");
            if (expectedLifecycleRevision < 0 || callerDeadlineEpochMillis <= 0) {
                throw new IllegalArgumentException("invalid lifecycle revision or deadline");
            }
            options = Map.copyOf(Objects.requireNonNull(options, "options"));
        }

        private static void require(String value, String label) {
            if (value == null || value.isBlank() || value.length() > 8_192) {
                throw new IllegalArgumentException(label + " invalid");
            }
        }
    }

    /**
     * Establishes exact authority and submits one immutable START request.
     *
     * @param request start input
     * @return owner response
     * @throws Exception when authority establishment, signing, or transport fails
     */
    public CodexLifecycleHttpClient.Response start(StartRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        AgentSessionService.AgentTaskIntent intent = new AgentSessionService.AgentTaskIntent(
                request.goal(), request.acceptance(),
                request.selectors().stream().map(ResourceSelector::value).toList(), List.of());
        AgentSessionService.AgentSessionContext session = sessionService.resolveSessionContext(
                new AgentSessionService.SessionResolutionRequest(request.projectRoot(), "codex",
                        request.connectionInstanceId(), intent, false));
        ClaimResult claim = collaborationService.announce(request.projectRoot(), "codex",
                request.connectionInstanceId(), request.goal(), request.acceptance(), request.selectors());
        if (!claim.acquired() || claim.intent() == null) {
            throw new IOException("lifecycle_claim_not_acquired");
        }
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(request.projectRoot());
        ProviderSessionBindingService.Binding binding = bindingService.find(location, "codex",
                request.connectionInstanceId()).orElseThrow(() -> new IOException("lifecycle_binding_missing"));
        if (!binding.sessionId().equals(session.sessionId()) || !"VERIFIED".equals(binding.verificationState())
                || !"VERIFIED".equals(binding.providerTrustState()) || binding.worktreePath() == null) {
            throw new IOException("lifecycle_binding_stale");
        }
        Path assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
        Path real = assigned.toRealPath();
        LifecycleControlRequestEnvelope.AuthorityContext authority =
                new LifecycleControlRequestEnvelope.AuthorityContext(
                        binding.projectId(), location.root().toAbsolutePath().normalize().toString(), "codex",
                        request.connectionInstanceId(), binding.sessionId(),
                        binding.providerInstanceFingerprint(), binding.bindingVersion(),
                        WorkspaceCollaborationService.participantHandle(binding.sessionId()),
                        claim.intent().intentId().toString(), claim.intent().version(), assigned.toString(),
                        real.toString(), binding.gitCommonDir(), binding.branch(), binding.baseCommit(),
                        binding.supervisorId(), binding.workerId());
        LifecycleControlRequestEnvelope envelope = new LifecycleControlRequestEnvelope(UUID.randomUUID(),
                request.hostInstanceId(), authority, LifecycleControlRequestEnvelope.Operation.START,
                request.expectedLifecycleRevision(), null, null, true, request.input(),
                request.callerDeadlineEpochMillis(), request.options());
        return submit(request.endpoint(), envelope.sign(
                new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity()));
    }

    /**
     * Signs a previously established Codex lifecycle envelope with the local
     * project identity.  The caller can retain the returned immutable value
     * for byte-equivalent transport retries.
     *
     * @param projectRoot initialized control project root
     * @param request immutable Codex lifecycle request
     * @return signed request envelope
     * @throws Exception when the project identity cannot be loaded
     */
    public LifecycleControlRequestEnvelope.SignedEnvelope sign(Path projectRoot,
            LifecycleControlRequestEnvelope request) throws Exception {
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(
                projectRoot.toAbsolutePath().normalize());
        NodeIdentity signer = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        return request.sign(signer);
    }

    /**
     * Submits one already signed Codex lifecycle envelope through the
     * production owner's loopback route.  The operation encoded in the
     * envelope determines whether the owner invokes START, NOTIFY, STEER,
     * WAIT, INTERRUPT, HARD_STOP, RESUME, or STATUS.
     *
     * @param endpoint existing coordination owner endpoint
     * @param signed immutable signed envelope; reuse it for exact retries
     * @return owner response
     * @throws Exception when transport or owner processing fails
     */
    public CodexLifecycleHttpClient.Response submit(URI endpoint,
            LifecycleControlRequestEnvelope.SignedEnvelope signed) throws Exception {
        return new CodexLifecycleHttpClient(endpoint).submit(signed);
    }

    /**
     * Signs and submits one immutable Codex lifecycle operation.  This is the
     * production call path for non-START operations after the caller has
     * resolved the exact authority context through the existing Synesis
     * session workflow.
     *
     * @param projectRoot initialized control project root
     * @param endpoint existing coordination owner endpoint
     * @param request immutable lifecycle request
     * @return owner response
     * @throws Exception when signing, transport, or owner processing fails
     */
    public CodexLifecycleHttpClient.Response submit(Path projectRoot, URI endpoint,
            LifecycleControlRequestEnvelope request) throws Exception {
        return submit(endpoint, sign(projectRoot, request));
    }
}
