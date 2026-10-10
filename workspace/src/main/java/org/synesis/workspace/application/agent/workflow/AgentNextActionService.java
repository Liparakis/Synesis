package org.synesis.workspace.application.agent.workflow;

import static org.synesis.workspace.application.agent.workflow.NextActionSupport.activeIntentOf;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.logSuppressed;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.newEventStore;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.openEventStore;
import static org.synesis.workspace.application.agent.workflow.NextActionSupport.putResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.ProviderSessionTerminalizationService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * Application service that resolves the single highest-priority actionable coordination item for
 * an ambient MCP session.
 *
 * <p>The returned action is a <em>projection of durable collaboration state</em>, never a
 * suggestion derived from local timing. A concrete recommended tool together with its arguments is
 * the only lifecycle authority: callers must not upgrade an ordinary {@code WAIT} or
 * {@code IMPLEMENT} response into a guessed transition.</p>
 *
 * <h2>Resolution order</h2>
 * <p>The first stage that produces a response wins; later stages are never consulted.</p>
 * <ol>
 *   <li><b>Workspace gate</b> - a missing project descriptor, or any failure while locating the
 *       project or assessing readiness, yields {@code RETRY_REQUIRED}.</li>
 *   <li><b>Exact-binding gate</b> - a terminal session or a completed lane is answered
 *       immediately (a completed lane may still owe review follow-up).</li>
 *   <li><b>Readiness gate</b> - a workspace that is not ready returns the readiness response,
 *       except that a stale control checkout may still serve the durable review and completion
 *       protocol.</li>
 *   <li><b>Durable coordination</b> (one catch-all boundary): integration pump, review lane,
 *       explicit completion, integration/continuation, claim gate, integration state, then
 *       capability requests.</li>
 *   <li><b>Local fallback</b> - file-based coordination items ordered by
 *       {@link LocalCoordinationFallback priority}, or an idle response when there are none.</li>
 * </ol>
 *
 * <h2>Failure model</h2>
 * <p>Inspections that cannot be completed are <em>fail-closed</em> wherever the original protocol
 * requires it (for example an unverifiable publishable-change check never grants publication).
 * Failures that are deliberately converted into a fallback are reported through
 * {@link NextActionSupport#logSuppressed(String, Exception)} instead of being dropped silently.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The service holds no mutable state; every call works on freshly opened event-store snapshots.
 * It is therefore as thread-safe as the injected collaborators.</p>
 *
 * @since 1.0
 */
public final class AgentNextActionService {

    /**
     * Project descriptor whose presence marks an initialised Synesis workspace.
     */
    private static final String PROJECT_DESCRIPTOR = ".synesis/project.json";

    /**
     * Binding status of a session that has been terminalized.
     */
    private static final String STATUS_TERMINAL = "TERMINAL";

    /**
     * Binding status of a lane that has finished its work.
     */
    private static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * Binding status of a session that is currently bound to a worktree.
     */
    private static final String STATUS_BOUND = "BOUND";

    /**
     * Readiness reason: the worktree generation no longer matches the control checkout.
     */
    private static final String REASON_GENERATION_MISMATCH = "WORKSPACE_GENERATION_MISMATCH";

    /**
     * Readiness reason: a sibling integration advanced the control checkout.
     */
    private static final String REASON_CONTROL_BASE_ADVANCED = "CONTROL_BASE_ADVANCED";

    /**
     * Integration-attempt status that requires human intervention.
     */
    private static final String INTEGRATION_CONFLICT_STATUS = "conflict";

    /**
     * Resolves project metadata and durable project-local paths.
     */
    private final ProjectApplicationService projectService;

    /**
     * Verifies the exact provider binding and its assigned worktree.
     */
    private final WorkspaceReadinessService readinessService;

    /**
     * Adds durable continuation metadata to the projected response.
     */
    private final AgentWorkflowReducer workflowReducer;

    /**
     * Resolves and validates immutable review snapshots when the caller is authorized.
     */
    private final TaskSnapshotService snapshotService;

    /**
     * Looks up and inspects provider-session bindings.
     */
    private final ProviderSessionBindingService bindingService;

    /**
     * Shared integration pump that recovers or advances queued snapshots on every inbox read.
     */
    private final IntegrationOrchestrationService integrationService;

    /**
     * Creates a next-action service wired with the production collaborators.
     */
    public AgentNextActionService() {
        this(new ProjectApplicationService(), new WorkspaceReadinessService(), new AgentWorkflowReducer(), new TaskSnapshotService(), new ProviderSessionBindingService(), new IntegrationOrchestrationService());
    }

    /**
     * Creates a next-action service from explicit collaborators, primarily so tests can substitute
     * them.
     *
     * @param projectService     resolves project metadata and project-local paths
     * @param readinessService   verifies the provider binding and assigned worktree
     * @param workflowReducer    decorates responses with durable continuation metadata
     * @param snapshotService    validates immutable review snapshots
     * @param bindingService     looks up provider-session bindings
     * @param integrationService integration pump run on every durable inbox read
     * @throws NullPointerException if any argument is {@code null}
     */
    AgentNextActionService(ProjectApplicationService projectService,
            WorkspaceReadinessService readinessService,
            AgentWorkflowReducer workflowReducer,
            TaskSnapshotService snapshotService,
            ProviderSessionBindingService bindingService,
            IntegrationOrchestrationService integrationService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.readinessService = Objects.requireNonNull(readinessService, "readinessService");
        this.workflowReducer = Objects.requireNonNull(workflowReducer, "workflowReducer");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.integrationService = Objects.requireNonNull(integrationService, "integrationService");
    }

    /**
     * Resolves the single highest-priority actionable coordination item for the active session
     * worker.
     *
     * @param request the next-action request identifying the project, provider and connection
     * @return the decorated agent response; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public AgentResponse getNextAction(NextActionRequest request) {
        Objects.requireNonNull(request, "request");
        AgentResponse response = projectRecoveryDeadline(request, resolveNextAction(request));
        return workflowReducer.decorate(request, response);
    }

    /**
     * Adds a recovery deadline only when another session's process absence is verified and its
     * unfinished lane is waiting for the existing grace period to expire.
     */
    private AgentResponse projectRecoveryDeadline(NextActionRequest request, AgentResponse response) {
        if (response.status() != AgentStatus.WAITING || response.nextAction() != AgentNextAction.WAIT
                || (response.reason() != AgentReason.OWNER_RESPONSE_PENDING && response.reason() != AgentReason.IMPLEMENTATION_UNAVAILABLE)
                || !(response.result() instanceof Map<?, ?> current)) {
            return response;
        }
        Object handleValue = current.get(NextActionKeys.CAPABILITY_REQUEST_HANDLE);
        Object revisionValue = current.get(NextActionKeys.CONTRACT_REVISION);
        if (!(handleValue instanceof String handle) || !(revisionValue instanceof Number revision)) {
            return response;
        }
        try {
            ProjectLocation location = projectService.locate(request.projectRoot());
            Optional<PredictionEventStore> openedStore = openEventStore(location);
            if (openedStore.isEmpty()) {
                return response;
            }
            PredictionEventStore store = openedStore.get();
            var capability = store.capabilityRequestProjection().findByHandle(handle).orElse(null);
            if (capability == null || capability.contractRevision() != revision.longValue()
                    || (response.reason() == AgentReason.OWNER_RESPONSE_PENDING && capability.state() != CapabilityLifecycleState.AWAITING_OWNER)) {
                return response;
            }
            List<WorkIntent> ownerIntents = store.collaborationProjection().activeIntents().stream()
                    .filter(intent -> intent.authorityLineageId().equals(capability.authorityLineageId()))
                    .toList();
            if (ownerIntents.size() != 1) {
                return response;
            }
            String ownerParticipant = ownerIntents.getFirst().participant();
            List<SessionLeaseRecord> ownerLeases = new SessionLeaseStore().listAll(location.root()).stream()
                    .filter(lease -> lease.projectId().equals(location.projectId().toString()))
                    .filter(lease -> WorkspaceCollaborationService.participantHandle(lease.sessionId()).equals(ownerParticipant))
                    .toList();
            if (ownerLeases.size() != 1) {
                return response;
            }
            var deadline = new SessionLeaseService().recoveryDeadlineEpochMillis(ownerLeases.getFirst(), new SessionLeasePolicy());
            if (deadline.isEmpty()) {
                return response;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            putResult(result, current, null);
            result.put(NextActionKeys.RECOVERY_DEADLINE_EPOCH_MILLIS, deadline.getAsLong());
            result.put("intentionalRecoveryWait", true);
            return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
        } catch (Exception failure) {
            logSuppressed("recovery deadline projection", failure);
            return response;
        }
    }

    /**
     * Runs the full resolution pipeline described in the class documentation.
     *
     * @param request the next-action request; must not be {@code null}
     * @return the undecorated agent response; never {@code null}
     */
    private AgentResponse resolveNextAction(NextActionRequest request) {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(PROJECT_DESCRIPTOR))) {
            return workspaceNotReady();
        }

        WorkspaceInspection inspection;
        try {
            inspection = inspectWorkspace(request, root);
        } catch (Exception failure) {
            logSuppressed("workspace inspection", failure);
            return workspaceNotReady();
        }
        if (inspection.shortCircuit() != null) {
            return inspection.shortCircuit();
        }

        ReadinessResult readiness = inspection.readiness();
        if (!readiness.ready()) {
            return notReadyResponse(request, inspection);
        }

        AgentResponse durable = resolveDurableCoordination(request, root, inspection.location(), readiness.binding(), readiness.worktree());
        AgentResponse resolved = durable != null ? durable : LocalCoordinationFallback.resolveLocalCoordination(bindingService, request, inspection.location(), readiness.worktree());
        return request.completionRequested() ? notingNothingToPublish(resolved, inspection.location(), readiness.binding(), readiness.worktree()) : resolved;
    }

    /**
     * Tells a lane that asked to finish and has changed nothing that it has nothing to publish.
     *
     * <p>No finish action applies to such a lane, and an answer that only lets it keep
     * implementing left an agent that had found nothing to change with no way to end the lane: it
     * stayed open and its work group with it. The answer keeps everything it carried and adds the
     * two ways forward.</p>
     *
     * @param response the resolved answer to a request to finish
     * @param location project location
     * @param binding  the verified binding of the calling session
     * @param worktree the verified assigned worktree
     * @return the answer, marked when it offered no action and the lane has nothing to publish
     */
    private AgentResponse notingNothingToPublish(AgentResponse response, ProjectLocation location, ProviderSessionBindingService.Binding binding, Path worktree) {
        if (response.status() != AgentStatus.READY || response.nextAction() != null) {
            return response;
        }
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            LaneScope lane = new LaneScope(WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, binding.baseCommit());
            if (store.isEmpty() || !LaneCompletionProjection.nothingToPublish(snapshotService, store.get(), lane)) {
                return response;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            putResult(result, response.result(), null);
            result.put(NextActionKeys.NOTHING_TO_PUBLISH, true);
            result.put("instruction", "This lane has changed none of its claimed files, so there is nothing to publish. Change them, or if nothing needs to change call cancel_lane with the reason.");
            return new AgentResponse(response.status(), response.reason(), null, result);
        } catch (Exception failure) {
            logSuppressed("nothing-to-publish projection", failure);
            return response;
        }
    }

    /**
     * Builds the response that tells the caller to (re-)establish its session.
     *
     * @return a {@code RETRY_REQUIRED} response with {@code ENSURE_SESSION} as next action
     */
    private static AgentResponse workspaceNotReady() {
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
    }

    /**
     * Locates the project, checks the exact binding and assesses workspace readiness.
     *
     * <p>When ordinary readiness fails only because the worktree generation moved on, completion
     * readiness is assessed instead: a worker may commit its completed change before polling the
     * completion inbox, and this read-only projection may validate a clean descendant for snapshot
     * publication even though mutation must still be rejected.</p>
     *
     * @param request the next-action request
     * @param root    normalized project root
     * @return the inspection outcome, either short-circuited by the binding gate or carrying the
     *         readiness result
     * @throws Exception any failure while locating the project or assessing readiness; the caller
     *                   maps it to {@code RETRY_REQUIRED}
     */
    private WorkspaceInspection inspectWorkspace(NextActionRequest request, Path root) throws Exception {
        ProjectLocation location = projectService.locate(root);
        ProviderSessionBindingService.Binding exactBinding = bindingService.find(location, request.provider(), request.connectionInstanceId()).orElse(null);
        if (exactBinding != null) {
            AgentResponse bindingResponse = terminalOrCompletedBindingResponse(location, exactBinding);
            if (bindingResponse != null) {
                return WorkspaceInspection.shortCircuited(bindingResponse);
            }
        }

        ReadinessResult readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        if (!readiness.ready() && REASON_GENERATION_MISMATCH.equals(readiness.internalReason())) {
            ReadinessResult completionReadiness = readinessService.assessCompletion(location, request.provider(), request.connectionInstanceId());
            if (completionReadiness.ready()) {
                readiness = completionReadiness;
            }
        }
        return WorkspaceInspection.inspected(location, exactBinding, readiness);
    }

    /**
     * Resolves the response for a workspace that failed its readiness check.
     *
     * <p>A sibling integration may advance the control checkout while a reviewer holds legitimate
     * uncommitted work in its assigned worktree. The stale-dirty workspace stays fail-closed for
     * every workspace operation, but the durable review protocol may continue because
     * {@code review_validation} is authorized by the exact session, grant, epoch and immutable
     * snapshot rather than by a replacement worktree.</p>
     *
     * @param request    the next-action request
     * @param inspection the inspection whose readiness is not ready
     * @return a stale-workspace review/completion action when one applies, otherwise the readiness
     *         response
     */
    private AgentResponse notReadyResponse(NextActionRequest request, WorkspaceInspection inspection) {
        ProviderSessionBindingService.Binding exactBinding = inspection.exactBinding();
        ReadinessResult readiness = inspection.readiness();
        if (exactBinding != null && STATUS_BOUND.equals(exactBinding.status()) && REASON_CONTROL_BASE_ADVANCED.equals(readiness.internalReason()) && (bindingService.hasConfirmedUncommittedWork(exactBinding)
                || hasActiveIntent(inspection.location(), exactBinding))) {
            AgentResponse staleAction = staleCoordinationAction(inspection.location(), exactBinding, request.completionRequested());
            if (staleAction != null) {
                return staleAction;
            }
        }
        return readiness.response();
    }

    /**
     * Returns a terminal or completed-lane response for an exact binding, if one applies.
     *
     * @param location project location
     * @param binding  the exact binding of the calling session
     * @return the terminal, completed or review-follow-up response, or {@code null} when the
     *         binding is neither terminal nor completed
     */
    private AgentResponse terminalOrCompletedBindingResponse(ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        try {
            if (isSessionTerminal(location, binding)) {
                return terminalSessionResponse(binding.sessionId());
            }
        } catch (Exception failure) {
            return AgentResponse.sessionTerminalStateUnavailable(failure);
        }
        if (!STATUS_COMPLETED.equals(binding.status())) {
            return null;
        }
        AgentResponse reviewResponse = ReviewLaneProjection.completedReviewAction(location, binding.sessionId());
        return reviewResponse != null ? reviewResponse : new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of(NextActionKeys.STATE, NextActionProtocol.STATE_COMPLETED, NextActionKeys.LANE, binding.sessionId()));
    }

    /**
     * Determines whether the session behind a binding has been terminalized.
     *
     * @param location project location
     * @param binding  the binding to test
     * @return {@code true} if the binding status or the durable terminal state says so
     * @throws Exception if the durable terminal state cannot be read
     */
    private static boolean isSessionTerminal(ProjectLocation location, ProviderSessionBindingService.Binding binding) throws Exception {
        return STATUS_TERMINAL.equals(binding.status()) || ProviderSessionTerminalizationService.isSessionTerminal(location, binding.sessionId());
    }

    /**
     * Builds the response for a session that is already terminal.
     *
     * @param sessionId the terminal session
     * @return a {@code COMPLETED} response carrying the {@code SESSION_TERMINAL} state
     */
    private static AgentResponse terminalSessionResponse(String sessionId) {
        return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of(NextActionKeys.STATE, NextActionProtocol.STATE_SESSION_TERMINAL, NextActionKeys.LANE, sessionId));
    }

    /**
     * Returns whether the exact bound session owns an active intent.
     *
     * <p>The check is scoped to the exact participant and an <em>active</em> intent: a completed
     * work group or another participant's lane cannot authorize continuation for the caller.</p>
     *
     * @param location project location
     * @param binding  the exact binding of the calling session
     * @return {@code true} if the participant owns an active intent; {@code false} on any failure
     */
    private static boolean hasActiveIntent(ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            return store.filter(predictionEventStore -> activeIntentOf(predictionEventStore, WorkspaceCollaborationService.participantHandle(binding.sessionId())).isPresent()).isPresent();
        } catch (Exception failure) {
            logSuppressed("active-intent lookup", failure);
            return false;
        }
    }

    /**
     * Projects review and completion actions for a bound session whose control checkout went stale.
     *
     * @param location            project location
     * @param binding             the exact bound session
     * @param completionRequested whether the caller explicitly asked to close its lane
     * @return the review or completion action, or {@code null} when none applies or the projection
     *         is unavailable
     */
    private AgentResponse staleCoordinationAction(ProjectLocation location, ProviderSessionBindingService.Binding binding, boolean completionRequested) {
        try {
            Optional<PredictionEventStore> openedStore = openEventStore(location);
            if (openedStore.isEmpty()) {
                return null;
            }
            PredictionEventStore store = openedStore.get();
            Map<String, Object> collaboration = CollaborationView.collaborationDetails(store, binding.sessionId());
            Path worktree = binding.worktreePath() == null ? null : Path.of(binding.worktreePath());
            LaneScope lane = new LaneScope(WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, binding.baseCommit());

            AgentResponse reviewLane = ReviewLaneProjection.reviewLaneAction(snapshotService, store, collaboration, lane, true);
            if (reviewLane != null) {
                return reviewLane;
            }
            if (completionRequested) {
                NodeIdentity callerIdentity = loadCallerIdentity(location);
                CapabilityActor actor = new CapabilityActor(callerIdentity.nodeId(), binding.supervisorId(), binding.workerId(), binding.sessionId());
                return LaneCompletionProjection.completionRequestAction(snapshotService, store, lane, actor);
            }
            return null;
        } catch (Exception failure) {
            logSuppressed("stale-workspace coordination projection", failure);
            return null;
        }
    }

    /**
     * Runs the durable coordination pipeline behind a single catch-all boundary.
     *
     * <p>Any failure is reported through {@link NextActionSupport#logSuppressed(String, Exception)} and treated as
     * "no durable action", which lets the caller fall back to the local coordination items.</p>
     *
     * @param request  the next-action request
     * @param root     normalized project root
     * @param location project location
     * @param binding  the verified binding of the calling session
     * @param worktree the verified assigned worktree
     * @return the durable action, or {@code null} when none applies or the pipeline failed
     */
    private AgentResponse resolveDurableCoordination(NextActionRequest request, Path root, ProjectLocation location, ProviderSessionBindingService.Binding binding, Path worktree) {
        try {
            return durableCoordinationAction(request, root, location, binding, worktree);
        } catch (Exception failure) {
            logSuppressed("durable coordination projection", failure);
            return null;
        }
    }

    /**
     * Evaluates the durable stages in priority order and returns the first response produced.
     *
     * <ol>
     *   <li>integration pump (terminal short-circuit when it completes the lane),</li>
     *   <li>review lane: review requests, reviewer wait, revision, review and grant waits,</li>
     *   <li>explicit completion, when the caller requested it,</li>
     *   <li>integration visibility versus recovery continuation,</li>
     *   <li>claim gate for sessions without an own active intent,</li>
     *   <li>integration conflict / dependency wait,</li>
     *   <li>capability requests (owner first, then requester).</li>
     * </ol>
     *
     * @param request  the next-action request
     * @param root     normalized project root
     * @param location project location
     * @param binding  the verified binding of the calling session
     * @param worktree the verified assigned worktree
     * @return the first applicable durable action, or {@code null} when none applies
     * @throws Exception any failure while reading identity, event store, bindings or the pump
     */
    private AgentResponse durableCoordinationAction(NextActionRequest request, Path root, ProjectLocation location, ProviderSessionBindingService.Binding binding, Path worktree) throws Exception {
        NodeIdentity callerIdentity = loadCallerIdentity(location);
        Optional<PredictionEventStore> openedStore = openEventStore(location);
        if (openedStore.isEmpty()) {
            return null;
        }
        PredictionEventStore store = openedStore.get();

        // Startup reconciliation is deliberately pull-safe: every durable inbox read gives the
        // shared integration pump a chance to recover an interrupted attempt or to advance the
        // oldest eligible immutable snapshot. The pump owns its project lock and never mutates a
        // worker worktree.
        AgentResponse integrationPump = integrationService.orchestrateIntegration(root, store, callerIdentity);
        if (integrationPump.status() == AgentStatus.COMPLETED) {
            store = newEventStore(location);
            AgentResponse bindingResponse = exactBindingLifecycleResponse(request, location);
            if (bindingResponse != null) {
                return bindingResponse;
            }
        }

        Map<String, Object> collaboration = CollaborationView.collaborationDetails(store, binding.sessionId(), null, binding.provider());
        LaneScope lane = new LaneScope(WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, binding.baseCommit());
        CapabilityActor actor = new CapabilityActor(callerIdentity.nodeId(), binding.supervisorId(), binding.workerId(), binding.sessionId());

        AgentResponse reviewLane = ReviewLaneProjection.reviewLaneAction(snapshotService, store, collaboration, lane, false);
        if (reviewLane != null) {
            return reviewLane;
        }

        if (request.completionRequested()) {
            AgentResponse owedReply = LaneCompletionProjection.owedOwnerReply(store, lane, actor);
            if (owedReply != null) {
                return owedReply;
            }
            AgentResponse awaitedVerdict = LaneCompletionProjection.awaitedVerdict(store, lane, actor);
            if (awaitedVerdict != null) {
                return awaitedVerdict;
            }
            AgentResponse awaitedAnswer = LaneCompletionProjection.awaitedRequesterAnswer(store, lane, actor, System.currentTimeMillis());
            if (awaitedAnswer != null) {
                return awaitedAnswer;
            }
            AgentResponse completion = LaneCompletionProjection.completionRequestAction(snapshotService, store, lane, actor);
            if (completion != null) {
                return completion;
            }
        } else {
            // Must precede the integration pump result: a dependent snapshot waiting on this
            // owner's unfinished lane makes the pump answer WAIT to every caller, the owner
            // included, and the owner is the only one who can end that wait.
            AgentResponse ownerCompletion = LaneCompletionProjection.validatedOwnerCompletionAction(store, lane, actor);
            if (ownerCompletion != null) {
                return ownerCompletion;
            }
        }

        AgentResponse continuation = RecoveryContinuationProjection.continuationAction(lane.participantId(), collaboration);
        AgentResponse integrationOrContinuation = integrationOrContinuationAction(store, collaboration, integrationPump, continuation);
        if (integrationOrContinuation != null) {
            return integrationOrContinuation;
        }

        Optional<WorkIntent> callerIntent = activeIntentOf(store, lane.participantId());
        boolean coordinationActivated = store.collaborationProjection().activated();
        if (coordinationActivated && callerIntent.isEmpty()) {
            // A cancelled lane is closed, as a finished one is. Asking its session for a new intent
            // would invite the agent to announce the work it has just cancelled.
            if (store.collaborationProjection().participantState(lane.participantId()).filter(Participant.State.CANCELLED::equals).isPresent()) {
                return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of(NextActionKeys.STATE, NextActionProtocol.STATE_CANCELLED, NextActionKeys.LANE, binding.sessionId()));
            }
            // With every announced lane finished or cancelled there is nothing to join. A session
            // told to establish an intent here, typically a resumed agent whose own lane is done,
            // goes looking for work to announce.
            if (WorkIntentService.hasHadWork(store) && !WorkIntentService.hasOpenWork(store)) {
                Map<String, Object> noOpenWork = new LinkedHashMap<>(collaboration);
                noOpenWork.put(NextActionKeys.STATE, NextActionProtocol.STATE_NO_OPEN_WORK);
                noOpenWork.put("instruction", "Every lane announced in this project is finished or cancelled. Announce a new intent with ensure_session only if part of the goal is still not done.");
                return new AgentResponse(AgentStatus.COMPLETED, null, null, noOpenWork);
            }
            return claimRequiredResponse(collaboration);
        }

        AgentResponse integrationState = integrationStateAction(store, actor);
        if (integrationState != null) {
            return integrationState;
        }
        return CapabilityActionProjection.capabilityAction(store, actor, callerIntent.orElse(null), coordinationActivated);
    }

    /**
     * Re-reads the exact binding and returns its terminal or completed-lane response, if any.
     *
     * @param request  the next-action request
     * @param location project location
     * @return the terminal or completed response, or {@code null} when none applies
     * @throws Exception if the binding lookup fails
     */
    private AgentResponse exactBindingLifecycleResponse(NextActionRequest request, ProjectLocation location) throws Exception {
        Optional<ProviderSessionBindingService.Binding> exactBinding = bindingService.find(location, request.provider(), request.connectionInstanceId());
        return exactBinding.map(binding -> terminalOrCompletedBindingResponse(location, binding)).orElse(null);
    }

    /**
     * Decides between the integration pump result and a recovery continuation.
     *
     * <p>A recovery-held owner cannot resolve the request fencing integration until a successor can
     * discover its continuation, so a continuation outranks a pending integration wait (this
     * projection transfers no authority). Otherwise a non-terminal pump result stays visible unless
     * review-candidate discovery or a reviewer continuation must remain reachable; a new reviewer
     * can only lawfully select a candidate that is still visible.</p>
     *
     * @param store         current project projection
     * @param collaboration public collaboration projection
     * @param pump          the integration pump result
     * @param continuation  the available recovery continuation, or {@code null}
     * @return the pump result or the continuation to return, or {@code null} to keep resolving
     */
    private static AgentResponse integrationOrContinuationAction(PredictionEventStore store, Map<String, Object> collaboration, AgentResponse pump, AgentResponse continuation) {
        boolean integrationPending = pump.status() == AgentStatus.WAITING && pump.reason() == AgentReason.INTEGRATION_PENDING;
        if (integrationPending && continuation != null) {
            return continuation;
        }

        boolean reviewCandidateSelectionRequired = Boolean.TRUE.equals(collaboration.get(NextActionKeys.REVIEW_CANDIDATE_SELECTION_REQUIRED));
        boolean reviewerContinuationForPendingReview = AgentReviewActionProjection.hasAvailableReviewerReviewContinuation(store, collaboration);
        boolean pumpIsNonTerminal = pump.status() != AgentStatus.READY && pump.status() != AgentStatus.COMPLETED;
        if (pumpIsNonTerminal && !reviewCandidateSelectionRequired && !reviewerContinuationForPendingReview) {
            return pump;
        }

        // A request addressed to this participant is a durable inbox item and an explicit completion
        // request closes this participant's own lane. Both must stay reachable even when an
        // unrelated recovery-held lane is available: continuing first could select an opposite-role
        // lane and make the owner response or snapshot publication unreachable (or trigger
        // REVIEW_SELF_NOT_ALLOWED).
        return continuation;
    }

    /**
     * Blocks a session that has not established its own active intent.
     *
     * <p>Such a session must not service another lane's capability inbox: provider models may
     * receive an implementation-available item before they have decoded a claim-bearing
     * {@code ensure_session} request, and answering it would let the unclaimed session act as owner
     * or requester of a different lane. The response stays actionable - the caller must establish
     * its own claim first, and discovery remains available in the bounded payload.</p>
     *
     * @param collaboration public collaboration projection
     * @return a {@code BLOCKED} response asking the caller to ensure its session
     */
    private static AgentResponse claimRequiredResponse(Map<String, Object> collaboration) {
        Map<String, Object> claimRequired = new LinkedHashMap<>(collaboration);
        claimRequired.put("claimsRequired", true);
        claimRequired.put(NextActionKeys.REASON, AgentReason.COORDINATION_INTENT_REQUIRED.value());
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, claimRequired);
    }

    /**
     * Reports an unresolved integration conflict or a task that is waiting on its dependencies.
     *
     * @param store current project projection
     * @param actor capability identity of the caller
     * @return a {@code BLOCKED} or {@code WAITING} response, or {@code null} when integration is
     *         not holding the caller up
     */
    private static AgentResponse integrationStateAction(PredictionEventStore store, CapabilityActor actor) {
        var taskCompletion = store.taskCompletionProjection();

        boolean conflict = taskCompletion.activeIntegrationAttempt().map(attempt -> INTEGRATION_CONFLICT_STATUS.equals(attempt.status())).orElse(false);
        if (conflict) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.REQUEST_HUMAN_HELP, pendingOnly(1));
        }

        boolean waitingForDependencies = taskCompletion.findLatestSnapshotForWorker(actor.nodeId(), actor.workerId()).map(snapshot -> taskCompletion.taskState(snapshot.taskId()))
                .filter(state -> state == TaskCompletionState.WAITING_FOR_DEPENDENCIES || state == TaskCompletionState.SNAPSHOT_READY).isPresent();
        if (waitingForDependencies) {
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, pendingOnly(1));
        }
        return null;
    }

    /**
     * Loads, or creates on first use, the identity of the local node.
     *
     * @param location project location whose profile directory stores the identity
     * @return the node identity
     * @throws Exception if the identity cannot be read or created
     */
    private static NodeIdentity loadCallerIdentity(ProjectLocation location) throws Exception {
        return location.loadOrCreateIdentity();
    }

    /**
     * Creates a mutable detail map that carries only a pending count.
     *
     * @param pending the pending count
     * @return a fresh, mutable single-entry map
     */
    @SuppressWarnings("SameParameterValue")
    private static Map<String, Object> pendingOnly(int pending) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.PENDING, pending);
        return result;
    }

    /**
     * Outcome of the workspace gate: either an immediate response or the data needed to continue.
     *
     * @param location     project location; {@code null} when short-circuited
     * @param exactBinding exact binding of the calling session, or {@code null} when none exists
     * @param readiness    readiness assessment; {@code null} when short-circuited
     * @param shortCircuit response that ends resolution immediately, or {@code null} to continue
     */
    private record WorkspaceInspection(ProjectLocation location, ProviderSessionBindingService.Binding exactBinding, ReadinessResult readiness, AgentResponse shortCircuit) {

        /**
         * Creates an outcome that ends resolution with the given response.
         *
         * @param response the response to return to the caller
         * @return a short-circuited inspection
         */
        static WorkspaceInspection shortCircuited(AgentResponse response) {
            return new WorkspaceInspection(null, null, null, response);
        }

        /**
         * Creates an outcome that lets resolution continue.
         *
         * @param location     project location
         * @param exactBinding exact binding of the calling session, or {@code null}
         * @param readiness    readiness assessment
         * @return a continuing inspection
         */
        static WorkspaceInspection inspected(ProjectLocation location, ProviderSessionBindingService.Binding exactBinding, ReadinessResult readiness) {
            return new WorkspaceInspection(location, exactBinding, readiness, null);
        }
    }
}
