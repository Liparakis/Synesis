package org.synesis.workspace.application.agent.workflow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
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
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

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
 *       {@link CoordinationItemType priority}, or an idle response when there are none.</li>
 * </ol>
 *
 * <h2>Failure model</h2>
 * <p>Inspections that cannot be completed are <em>fail-closed</em> wherever the original protocol
 * requires it (for example an unverifiable publishable-change check never grants publication).
 * Failures that are deliberately converted into a fallback are reported through
 * {@link #logSuppressed(String, Exception)} instead of being dropped silently.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The service holds no mutable state; every call works on freshly opened event-store snapshots.
 * It is therefore as thread-safe as the injected collaborators.</p>
 *
 * @since 1.0
 */
public final class AgentNextActionService {

    /**
     * Logger used to surface failures that are deliberately converted into a fallback result.
     */
    private static final System.Logger LOGGER = System.getLogger(AgentNextActionService.class.getName());

    /**
     * Project descriptor whose presence marks an initialised Synesis workspace.
     */
    private static final String PROJECT_DESCRIPTOR = ".synesis/project.json";

    /**
     * Project-relative directory holding the durable coordination event store.
     */
    private static final String COORDINATION_DIR = ".synesis/coordination";

    /**
     * Sub-directory of {@link #COORDINATION_DIR} whose existence signals a populated event log.
     */
    private static final String EVENTS_DIR = "events";

    /**
     * Project-relative location of the local, file-based coordination inbox.
     */
    private static final String LOCAL_ITEMS_FILE = ".synesis/local/coordination/items.json";

    /**
     * Profile sub-directory that stores the node identity.
     */
    private static final String LINK_PROFILE_DIR = "link";

    /**
     * Application-state sub-directory inspected for durable command diagnostics.
     */
    private static final String COMMANDS_DIR = "commands";

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
     * Wire value of the workspace marker and of the idle {@code ready} response.
     */
    private static final String ISOLATED = "isolated";

    /**
     * Placeholder shown for owner-request fields the local item did not provide.
     */
    private static final String PLACEHOLDER_TEXT = "...";

    /**
     * Capability name reported when a local owner request carries none.
     */
    private static final String UNKNOWN_CAPABILITY = "unknown";

    /**
     * Reason reported when the requester asked for a revision without stating why.
     */
    private static final String DEFAULT_REVISION_REASON = "Revision required by requester";

    /**
     * Reason reported when the owner rejected a capability request without stating why.
     */
    private static final String DEFAULT_REJECTION_REASON = "Capability request rejected by owner";

    /**
     * Summary attached to every snapshot-publication finish payload.
     */
    private static final String PUBLISH_SNAPSHOT_SUMMARY = "Publish the completed immutable snapshot";

    /**
     * Capability-request fields a requester must supply, as reported by the durable path.
     */
    private static final List<String> DURABLE_CAPABILITY_FIELDS = List.of("inputs", "output", "requiredBehavior", "acceptanceTests");

    /**
     * Capability-request fields a requester must supply, as reported by the local fallback.
     */
    private static final List<String> LOCAL_CAPABILITY_FIELDS = List.of("inputs", "output", "behavior", "acceptanceTest");

    /**
     * Capability states in which a requester has something to act on or wait for. A
     * {@code REVISION_REQUESTED} request outranks all of them and is handled separately.
     */
    private static final Set<CapabilityLifecycleState> REQUESTER_ACTIONABLE_STATES = Set.of(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE,
            CapabilityLifecycleState.REJECTED,
            CapabilityLifecycleState.AWAITING_OWNER,
            CapabilityLifecycleState.ACCEPTED,
            CapabilityLifecycleState.IMPLEMENTING);

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
        AgentResponse response = resolveNextAction(request);
        return workflowReducer.decorate(request, response);
    }

    /**
     * Returns whether a reviewer still lacks a resolved review of a current producer lane.
     *
     * <p>Open review grants always count as unresolved. A consumed grant addressed to a successor
     * reviewer authorizes <em>continuation</em> of the reviewer lane and is explicitly not a review
     * obligation, so it never strands the successor in {@code REVIEWER_PENDING}.</p>
     *
     * @param store    current project projection
     * @param reviewer the reviewer intent being evaluated
     * @return {@code true} if the reviewer still owes a review
     */
    static boolean hasUnresolvedReviewObligation(PredictionEventStore store, WorkIntent reviewer) {
        if (reviewer.role() != WorkIntent.Role.REVIEWER) {
            return false;
        }
        var workGroups = store.workGroupProjection();
        List<LaneGrant> reviewerGrants = workGroups.grants().stream().filter(grant -> grant.workGroupId().equals(reviewer.workGroupId())).filter(grant -> grant.targetParticipant().equals(reviewer.participant()))
                .filter(grant -> !AgentReviewActionProjection.grantTargetsSupersededIntent(store, grant)).filter(grant -> !workGroups.grantSuperseded(grant.grantId())).toList();

        boolean unresolvedGrant = reviewerGrants.stream().filter(grant -> AgentReviewActionProjection.allowsIndependentReview(store, grant))
                .anyMatch(grant -> workGroups.reviewValidationForGrant(grant.grantId()).isEmpty());
        if (unresolvedGrant) {
            return true;
        }

        List<WorkIntent> producers = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> intent.workGroupId().equals(reviewer.workGroupId())).filter(intent -> !intent.participant().equals(reviewer.participant())).toList();
        List<WorkIntent> matching = reviewer.reviewTargetSelectors().isEmpty() ? producers : producers.stream().filter(producer -> AgentReviewActionProjection.reviewTargetsMatch(reviewer, producer)).toList();

        if (matching.isEmpty()) {
            if (reviewerGrants.stream().anyMatch(grant -> workGroups.continuationGrant(grant.grantId()))) {
                // A continuation grant restores the reviewer lane; it is not a review obligation.
                return false;
            }
            // With no producer yet, an empty grant list means the reviewer is waiting for review
            // work to emerge. Open grants were handled above, so any grant left here is either a
            // self-review or already validated and does not imply future review work.
            return reviewerGrants.isEmpty();
        }
        return matching.stream().anyMatch(producer -> !AgentReviewActionProjection.hasCompletedReview(store, reviewer, producer));
    }

    /**
     * Maps a protocol action name to the {@link AgentNextAction} it implies.
     *
     * @param protocolAction wire name of the protocol action, such as {@code "wait"}
     * @return the matching action; {@link AgentNextAction#REQUEST_COORDINATION} for any name that
     *         has no dedicated mapping
     */
    static AgentNextAction protocolNextAction(String protocolAction) {
        return switch (protocolAction) {
            case Protocol.ACTION_RESPOND_COORDINATION -> AgentNextAction.RESPOND_COORDINATION;
            case Protocol.ACTION_REVIEW_DECISION -> AgentNextAction.REVIEW_DECISION;
            case Protocol.ACTION_WAIT -> AgentNextAction.WAIT;
            default -> AgentNextAction.REQUEST_COORDINATION;
        };
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
        if (durable != null) {
            return durable;
        }
        return resolveLocalCoordination(request, inspection.location(), readiness.worktree());
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
            return terminalStateUnavailableResponse(failure);
        }
        if (!STATUS_COMPLETED.equals(binding.status())) {
            return null;
        }
        AgentResponse reviewResponse = completedReviewAction(location, binding.sessionId());
        return reviewResponse != null ? reviewResponse : new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of(Keys.STATE, Protocol.STATE_COMPLETED, Keys.LANE, binding.sessionId()));
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
        return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of(Keys.STATE, Protocol.STATE_SESSION_TERMINAL, Keys.LANE, sessionId));
    }

    /**
     * Builds the fail-closed response used when the terminal state could not be determined.
     *
     * @param failure the failure raised while reading the terminal state
     * @return a {@code FAILED} response asking for human help, tagged with the root cause type
     */
    private static AgentResponse terminalStateUnavailableResponse(Exception failure) {
        Throwable cause = Objects.requireNonNullElse(failure.getCause(), failure);
        return new AgentResponse(AgentStatus.FAILED,
                AgentReason.INTERNAL_FAILURE,
                AgentNextAction.REQUEST_HUMAN_HELP,
                Map.of(Keys.REASON, "SESSION_TERMINAL_STATE_UNAVAILABLE", "causeType", cause.getClass().getSimpleName()));
    }

    /**
     * Projects review follow-up for a completed lane whose participant may still owe or await
     * review work.
     *
     * @param location  project location
     * @param sessionId the completed session
     * @return a review response, a {@code WAIT} while a completed work group is still active, or
     *         {@code null} when nothing is owed or the projection is unavailable
     */
    private AgentResponse completedReviewAction(ProjectLocation location, String sessionId) {
        try {
            Optional<PredictionEventStore> openedStore = openEventStore(location);
            if (openedStore.isEmpty()) {
                return null;
            }
            PredictionEventStore store = openedStore.get();
            String participant = WorkspaceCollaborationService.participantHandle(sessionId);
            Map<String, Object> baseCollaboration = collaborationDetails(store, sessionId);
            AgentResponse pendingReviewResponse = AgentReviewActionProjection.pendingReviewRequestResponse(baseCollaboration, store, participant);
            if (pendingReviewResponse != null) {
                return pendingReviewResponse;
            }
            Set<UUID> completedGroups = AgentReviewActionProjection.completedParticipantWorkGroups(store, participant);
            if (completedGroups.isEmpty()) {
                return null;
            }
            Map<String, Object> collaboration = collaborationDetails(store, sessionId, completedGroups);
            AgentResponse reviewResponse = AgentReviewActionProjection.reviewActionResponse(collaboration, true);
            if (reviewResponse != null) {
                return reviewResponse;
            }
            var workGroups = store.workGroupProjection();
            boolean groupActive = completedGroups.stream().anyMatch(groupId -> workGroups.group(groupId).filter(group -> group.status() == WorkGroup.Status.ACTIVE).isPresent());
            return groupActive ? new AgentResponse(AgentStatus.WAITING, null, AgentNextAction.WAIT, collaboration) : null;
        } catch (Exception failure) {
            logSuppressed("completed-lane review projection", failure);
            return null;
        }
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
            Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
            Path worktree = binding.worktreePath() == null ? null : Path.of(binding.worktreePath());
            LaneScope lane = new LaneScope(WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, binding.baseCommit());

            AgentResponse reviewLane = reviewLaneAction(store, collaboration, lane, true);
            if (reviewLane != null) {
                return reviewLane;
            }
            if (completionRequested) {
                NodeIdentity callerIdentity = loadCallerIdentity(location);
                CapabilityActor actor = new CapabilityActor(callerIdentity.nodeId(), binding.supervisorId(), binding.workerId());
                return completionRequestAction(store, lane, actor);
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
     * <p>Any failure is reported through {@link #logSuppressed(String, Exception)} and treated as
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

        Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
        LaneScope lane = new LaneScope(WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, binding.baseCommit());
        CapabilityActor actor = new CapabilityActor(callerIdentity.nodeId(), binding.supervisorId(), binding.workerId());

        AgentResponse reviewLane = reviewLaneAction(store, collaboration, lane, false);
        if (reviewLane != null) {
            return reviewLane;
        }

        if (request.completionRequested()) {
            AgentResponse completion = completionRequestAction(store, lane, actor);
            if (completion != null) {
                return completion;
            }
        }

        AgentResponse continuation = continuationAction(lane.participantId(), collaboration);
        AgentResponse integrationOrContinuation = integrationOrContinuationAction(store, collaboration, integrationPump, continuation);
        if (integrationOrContinuation != null) {
            return integrationOrContinuation;
        }

        Optional<WorkIntent> callerIntent = activeIntentOf(store, lane.participantId());
        boolean coordinationActivated = store.collaborationProjection().activated();
        if (coordinationActivated && callerIntent.isEmpty()) {
            return claimRequiredResponse(collaboration);
        }

        AgentResponse integrationState = integrationStateAction(store, actor);
        if (integrationState != null) {
            return integrationState;
        }
        return capabilityAction(store, actor, callerIntent.orElse(null), coordinationActivated);
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

        boolean reviewCandidateSelectionRequired = Boolean.TRUE.equals(collaboration.get(Keys.REVIEW_CANDIDATE_SELECTION_REQUIRED));
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
        claimRequired.put(Keys.REASON, AgentReason.COORDINATION_INTENT_REQUIRED.value());
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
     * Resolves the review-related actions of a lane, strongest first: pending review requests,
     * review actions, reviewer wait, correction revision, then review coordination and grant waits.
     *
     * @param store         current project projection
     * @param collaboration public collaboration projection
     * @param lane          the caller's lane
     * @param reviewOnly    {@code true} to restrict coordination and review actions to review
     *                      matters, as required while the workspace is stale
     * @return the first applicable action, or {@code null} when the lane has no review matters
     */
    private AgentResponse reviewLaneAction(PredictionEventStore store, Map<String, Object> collaboration, LaneScope lane, boolean reviewOnly) {
        String participant = lane.participantId();
        AgentResponse response = AgentReviewActionProjection.pendingReviewRequestResponse(collaboration, store, participant);
        if (response != null) {
            return response;
        }
        response = AgentReviewActionProjection.reviewActionResponse(collaboration, reviewOnly);
        if (response != null) {
            return response;
        }
        response = AgentReviewActionProjection.reviewerPendingAction(store, participant);
        if (response != null) {
            return response;
        }
        response = revisionRequiredAction(store, participant, lane.worktree());
        if (response != null) {
            return response;
        }
        return reviewCoordinationAndGrantResponse(store, lane, collaboration, reviewOnly);
    }

    /**
     * Resolves the review wait first, then pending coordination, then an outstanding grant wait.
     *
     * @param store         current project projection
     * @param lane          the caller's lane
     * @param collaboration public collaboration projection
     * @param reviewOnly    {@code true} to limit pending coordination to review requests
     * @return the first applicable action, or {@code null} when nothing is pending
     */
    private AgentResponse reviewCoordinationAndGrantResponse(PredictionEventStore store, LaneScope lane, Map<String, Object> collaboration, boolean reviewOnly) {
        AgentResponse reviewResponse = reviewPendingAction(store, lane.participantId());
        if (reviewResponse != null) {
            return reviewResponse;
        }
        AgentResponse coordinationResponse = pendingCoordinationResponse(collaboration, reviewOnly);
        if (coordinationResponse != null) {
            return coordinationResponse;
        }
        return pendingReviewGrantResponse(collaboration, pendingReviewGrantAction(store, lane));
    }

    /**
     * Projects the exact correction revision after a reviewed snapshot was rejected.
     *
     * <p>The owner must admit a fresh review request before the implementer is told to publish the
     * correction; otherwise the revision projection would hide the pending request and leave the
     * reviewer without a lawful current-epoch grant. A failed publishable-change comparison keeps
     * the revision gate closed.</p>
     *
     * @param store            current project projection
     * @param participantId    exact caller participant
     * @param assignedWorktree verified assigned worktree, or {@code null} when unknown
     * @return a {@code REVISION_REQUIRED} response, or {@code null} when no revision is owed
     */
    private AgentResponse revisionRequiredAction(PredictionEventStore store, String participantId, Path assignedWorktree) {
        if (isNullOrBlank(participantId)) {
            return null;
        }
        var completion = store.taskCompletionProjection();
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId) || hasPendingReviewRequest(store, participantId)) {
                continue;
            }
            if (completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            TaskSnapshotRecord rejected = latestRejectedSnapshot(store, intent);
            if (rejected == null || AgentReviewActionProjection.hasCurrentReviewGrant(store, intent)) {
                continue;
            }
            if (hasRevisionPublishableChanges(assignedWorktree, intent, rejected)) {
                continue;
            }
            return revisionRequiredResponse(store, participantId, intent, rejected);
        }
        return null;
    }

    /**
     * Returns whether a review request addressed to the participant is still pending admission.
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return {@code true} if a pending {@code REVIEW} request targets the participant
     */
    private static boolean hasPendingReviewRequest(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == CoordinationRequest.Status.PENDING && request.kind() == CoordinationRequest.Kind.REVIEW && request.target().equals(participantId));
    }

    /**
     * Finds the most recent rejected snapshot of an earlier claim epoch in the same authority
     * lineage.
     *
     * @param store  current project projection
     * @param intent the producer intent being corrected
     * @return the latest rejected predecessor snapshot, or {@code null} when there is none
     */
    private static TaskSnapshotRecord latestRejectedSnapshot(PredictionEventStore store, WorkIntent intent) {
        return store.taskCompletionProjection().allSnapshots().stream().filter(snapshot -> snapshot.taskId().equals(intent.taskId()))
                .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(intent.authorityLineageId())).filter(snapshot -> snapshot.provenance().claimEpoch() < intent.version())
                .filter(snapshot -> snapshotStateOf(store, snapshot) == TaskCompletionState.REVIEW_REJECTED).max(Comparator.comparingLong(snapshot -> snapshot.provenance().claimEpoch())).orElse(null);
    }

    /**
     * Returns whether the worktree already holds changes that revise a rejected snapshot.
     *
     * @param assignedWorktree verified assigned worktree, or {@code null} when unknown
     * @param intent           the producer intent being corrected
     * @param rejected         the rejected predecessor snapshot
     * @return {@code true} only when the comparison succeeded and found publishable changes; a
     *         failed comparison returns {@code false} so the revision gate stays closed
     */
    private boolean hasRevisionPublishableChanges(Path assignedWorktree, WorkIntent intent, TaskSnapshotRecord rejected) {
        if (assignedWorktree == null) {
            return false;
        }
        try {
            return snapshotService.hasRevisionPublishableChanges(assignedWorktree, intent.selectors(), intent.baseCommit(), rejected);
        } catch (Exception failure) {
            logSuppressed("revision publishable-change comparison", failure);
            return false;
        }
    }

    /**
     * Builds the {@code REVISION_REQUIRED} response with its executable {@code implement} payload.
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @param intent        the producer intent being corrected
     * @param rejected      the rejected predecessor snapshot
     * @return the ready response asking the implementer to publish the correction
     */
    private static AgentResponse revisionRequiredResponse(PredictionEventStore store, String participantId, WorkIntent intent, TaskSnapshotRecord rejected) {
        long workGroupVersion = store.workGroupProjection().group(intent.workGroupId()).map(WorkGroup::version).orElse(0L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.STATE, Protocol.STATE_REVISION_REQUIRED);
        result.put("revisionRequired", true);
        result.put(Keys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        result.put("rejectedSnapshot", CollaborationResponseMapper.snapshotMap(rejected, store));
        result.put("latestRejectedSnapshotId", rejected.snapshotId());
        result.put(Keys.WORK_GROUP_ID, intent.workGroupId().toString());
        result.put(Keys.CLAIM_EPOCH, intent.version());
        result.put(Keys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        putNextProtocol(result, Protocol.ACTION_IMPLEMENT, "implementation_revision", revisionRequiredPayload(store, participantId, intent, workGroupVersion));
        return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, null, result);
    }

    /** Builds the exact executable correction payload for a rejected snapshot. */
    private static Map<String, Object> revisionRequiredPayload(PredictionEventStore store, String participantId, WorkIntent intent, long workGroupVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putLaneFence(payload, intent, participantId, workGroupVersion, store.headSequence());
        payload.put(Keys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        payload.put("baseCommit", intent.baseCommit());
        payload.put(Keys.SELECTORS, selectorMaps(intent));
        return payload;
    }

    /**
     * Projects the wait state for an implementer whose exact snapshot awaits review.
     *
     * <p>A continuation preserves authority lineage, but a predecessor snapshot is not the
     * successor's executable review revision. The wait therefore stays bound to the exact current
     * intent and epoch; otherwise a migrated review grant could be stranded behind an older
     * {@code REVIEW_PENDING} snapshot. A pending review-admission request is answered by the owner
     * first, because the snapshot wait only makes sense once a reviewer has been admitted.</p>
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return a {@code WAITING} response, or {@code null} when no snapshot awaits review
     */
    private static AgentResponse reviewPendingAction(PredictionEventStore store, String participantId) {
        if (isNullOrBlank(participantId)) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId)) {
                continue;
            }
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);
            if (snapshot == null || snapshotStateOf(store, snapshot) != TaskCompletionState.REVIEW_PENDING) {
                continue;
            }
            boolean pendingReviewAdmission = store.collaborationProjection().requests().stream().anyMatch(request -> AgentReviewActionProjection.pendingReviewAdmissionTargets(store, request, intent, participantId));
            if (pendingReviewAdmission) {
                continue;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(Keys.STATE, TaskCompletionState.REVIEW_PENDING.name());
            result.put("reviewPending", true);
            result.put(Keys.SNAPSHOT, CollaborationResponseMapper.snapshotMap(snapshot, store));
            result.put(Keys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
            result.put(Keys.WORK_GROUP_ID, intent.workGroupId().toString());
            result.put(Keys.CLAIM_EPOCH, intent.version());
            putNextProtocol(result, Protocol.ACTION_WAIT, "review_validation", reviewPendingPayload(intent, snapshot, participantId));
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
        }
        return null;
    }

    /** Builds the exact wait payload for the lane and immutable snapshot awaiting review. */
    private static Map<String, Object> reviewPendingPayload(WorkIntent intent, TaskSnapshotRecord snapshot, String participantId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.snapshotId());
        payload.put(Keys.INTENT_ID, intent.intentId().toString());
        payload.put(Keys.WORK_GROUP_ID, intent.workGroupId().toString());
        payload.put(Keys.CLAIM_EPOCH, intent.version());
        payload.put(Keys.PARTICIPANT, participantId);
        payload.put(Keys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        return payload;
    }

    /**
     * Wraps a pending review-grant detail map in a {@code WAIT} response.
     *
     * @param collaboration      public collaboration projection
     * @param pendingReviewGrant grant details from {@link #pendingReviewGrantAction}, or
     *                           {@code null}
     * @return a ready {@code WAIT} response merging both maps, or {@code null} when there is no
     *         pending grant
     */
    private static AgentResponse pendingReviewGrantResponse(Map<String, Object> collaboration, Map<String, Object> pendingReviewGrant) {
        if (pendingReviewGrant == null) {
            return null;
        }
        Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
        ownerWait.putAll(pendingReviewGrant);
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, ownerWait);
    }

    /**
     * Projects a review grant that a reviewer must still be admitted to and consume.
     *
     * <p>A reciprocal reviewer grant must not fence an active lane before that lane has produced
     * any claim-covered source change: the participant still needs the ordinary {@code IMPLEMENT}
     * envelope to do its assigned work. Once publishable changes exist, the wait is kept so that
     * publication stays gated on reviewer admission and consumption. An inspection failure is
     * fail-closed - the wait is retained rather than projecting an unverified continuation.</p>
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @return the grant-pending detail map, or {@code null} when no grant is pending
     */
    private Map<String, Object> pendingReviewGrantAction(PredictionEventStore store, LaneScope lane) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        String participantId = lane.participantId();

        List<ResourceSelector> participantClaims = collaboration.activeIntents().stream().filter(candidate -> candidate.participant().equals(participantId))
                .filter(candidate -> candidate.role() == WorkIntent.Role.PRODUCER).flatMap(candidate -> candidate.selectors().stream()).distinct().toList();
        List<ResourceSelector> foreignClaims = foreignClaims(store, participantId);

        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!isProducerLane(intent, participantId)) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            if (completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            for (LaneGrant grant : workGroups.grants()) {
                if (!isOpenReviewGrantFor(store, grant, intent, participantId)) {
                    continue;
                }
                if (lane.worktree() != null) {
                    Publishability publishability = inspectScopedPublishableChanges(lane.worktree(), intent, participantClaims, foreignClaims, lane.baseCommit());
                    if (publishability == Publishability.NOT_PUBLISHABLE) {
                        // The inspection depends only on the intent, so every remaining grant of
                        // this intent would be skipped for the same reason.
                        break;
                    }
                }
                return reviewGrantPendingResult(grant, intent, group);
            }
        }
        return null;
    }

    /**
     * Returns whether a grant is an open, unsuperseded review grant addressed to another
     * participant for the exact intent and claim epoch.
     *
     * @param store         current project projection
     * @param grant         the grant to test
     * @param intent        the producer intent under review
     * @param participantId exact caller participant
     * @return {@code true} if the grant still awaits admission or consumption
     */
    private static boolean isOpenReviewGrantFor(PredictionEventStore store, LaneGrant grant, WorkIntent intent, String participantId) {
        var workGroups = store.workGroupProjection();
        return grant.workGroupId().equals(intent.workGroupId()) && grant.targetIntentId().equals(intent.intentId()) && grant.claimEpoch() == intent.version() && !grant.targetParticipant().equals(participantId)
                && !workGroups.grantSuperseded(grant.grantId()) && workGroups.grantAvailable(grant.grantId()) && workGroups.reviewValidationForGrant(grant.grantId()).isEmpty();
    }

    /**
     * Builds the detail map for a pending review grant.
     *
     * @param grant  the pending grant
     * @param intent the producer intent under review
     * @param group  the active work group of the intent
     * @return the {@code REVIEW_GRANT_PENDING} detail map
     */
    private static Map<String, Object> reviewGrantPendingResult(LaneGrant grant, WorkIntent intent, WorkGroup group) {
        Map<String, Object> payload = reviewGrantPendingPayload(grant, intent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.STATE, Protocol.STATE_REVIEW_GRANT_PENDING);
        result.put("reviewGrantPending", true);
        result.put("reviewGrant", CollaborationResponseMapper.laneGrantMap(grant));
        result.put(Keys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
        result.put(Keys.REVIEWER_PARTICIPANT, grant.targetParticipant());
        putNextProtocol(result, Protocol.ACTION_WAIT, "review_grant_consumption", payload);
        return result;
    }

    /** Builds the wait payload that admits the reviewer for the exact grant epoch. */
    private static Map<String, Object> reviewGrantPendingPayload(LaneGrant grant, WorkIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grantId", grant.grantId().toString());
        payload.put(Keys.WORK_GROUP_ID, grant.workGroupId().toString());
        payload.put(Keys.INTENT_ID, grant.targetIntentId().toString());
        payload.put("reviewedIntentId", grant.targetIntentId().toString());
        payload.put("reviewedParticipantId", intent.participant());
        payload.put(Keys.CLAIM_EPOCH, grant.claimEpoch());
        payload.put("targetParticipant", grant.targetParticipant());
        payload.put(Keys.REVIEWER_PARTICIPANT, grant.targetParticipant());
        payload.put("snapshotRequired", true);
        return payload;
    }

    /**
     * Builds the response for pending coordination, optionally limited to review requests.
     *
     * @param collaboration public collaboration projection containing {@code pendingCoordination}
     * @param reviewOnly    {@code true} to answer only review-acceptance requests
     * @return a {@code RESPOND_COORDINATION} response, or {@code null} when nothing qualifies
     */
    private static AgentResponse pendingCoordinationResponse(Map<String, Object> collaboration, boolean reviewOnly) {
        List<Map<String, Object>> pending = projectedList(collaboration, Keys.PENDING_COORDINATION);
        if (pending.isEmpty()) {
            return null;
        }
        Map<String, Object> reviewAcceptance = AgentReviewActionProjection.reviewAcceptanceAction(pending);
        if (reviewOnly && reviewAcceptance == null) {
            return null;
        }
        Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
        if (reviewAcceptance != null) {
            ownerAction.putAll(reviewAcceptance);
        } else {
            ownerAction.put(Keys.REVIEW_DECISION, ownerReviewDecision(pending));
        }
        return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING, AgentNextAction.RESPOND_COORDINATION, ownerAction);
    }

    /**
     * Builds the decision template an owner must complete when answering pending requests.
     *
     * @param pending the pending coordination requests addressed to the owner
     * @return the {@code reviewDecision} descriptor, including one response template per request
     */
    private static Map<String, Object> ownerReviewDecision(List<Map<String, Object>> pending) {
        return Map.of("required",
                true,
                "tool",
                Protocol.ACTION_RESPOND_COORDINATION,
                "field",
                "coordinationStatus",
                "allowedResults",
                List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED"),
                "optionalFields",
                List.of("proposal"),
                "responseTemplates",
                pending.stream().map(item -> Map.of("kind", "coordination_response", "payload", Map.of("coordinationRequest", item.get("requestId")))).toList(),
                "instruction",
                "Choose an owned request template and supply coordinationStatus after judgment; " + "the template is not executable until that decision is filled.");
    }

    /**
     * Projects recovery-held work that a different current participant may explicitly continue.
     *
     * <p>This is a read-only projection. It never issues a grant, changes claims or exposes the
     * internal snapshot reference. The continuation request derives its target identity from the
     * exact authenticated caller and revalidates every field under the project append lock.</p>
     *
     * @param store         current project projection
     * @param participantId exact current caller participant
     * @return deterministic, public-safe continuation candidates ordered by source intent id
     */
    private static List<Map<String, Object>> continuationCandidates(PredictionEventStore store, String participantId) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        List<ResourceSelector> callerSelectors = collaboration.activeIntents().stream().filter(existing -> existing.participant().equals(participantId)).flatMap(existing -> existing.selectors().stream()).toList();

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (WorkIntent intent : collaboration.activeIntents()) {
            boolean recoveryHeld = collaboration.participantState(intent.participant()).orElse(null) == Participant.State.RECOVERY_HELD && collaboration.recoverySnapshotReference(intent.participant()).isPresent();
            if (!recoveryHeld) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            boolean sameParticipant = intent.participant().equals(participantId);
            boolean scopeConflict = !sameParticipant && overlapsAny(callerSelectors, intent.selectors());
            candidates.add(continuationCandidate(intent, group, sameParticipant, scopeConflict));
        }
        candidates.sort(Comparator.comparing(candidate -> String.valueOf(candidate.get(Keys.SOURCE_INTENT_ID))));
        return List.copyOf(candidates);
    }

    /**
     * Builds the public-safe description of one continuation candidate.
     *
     * @param intent          the recovery-held intent
     * @param group           the active work group of the intent
     * @param sameParticipant whether the caller is the former owner
     * @param scopeConflict   whether the caller's own claims overlap the candidate's claims
     * @return the candidate map; {@code available} is {@code false} when blocked
     */
    private static Map<String, Object> continuationCandidate(WorkIntent intent, WorkGroup group, boolean sameParticipant, boolean scopeConflict) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put(Keys.SOURCE_INTENT_ID, intent.intentId().toString());
        candidate.put(Keys.INTENT_ID, intent.intentId().toString());
        candidate.put("taskId", intent.taskId().toString());
        candidate.put(Keys.WORK_GROUP_ID, intent.workGroupId().toString());
        candidate.put("goal", intent.goal());
        candidate.put("acceptance", intent.acceptance());
        candidate.put(Keys.SELECTORS, selectorMaps(intent));
        candidate.put(Keys.CLAIM_EPOCH, intent.version());
        candidate.put("role", intent.role().wireValue());
        candidate.put(Keys.KNOWN_DEPENDENCIES, intent.knownDependencies());
        candidate.put("formerOwner", intent.participant());
        candidate.put("formerOwnerState", Participant.State.RECOVERY_HELD.name());
        candidate.put(Keys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
        candidate.put("unfinished", true);
        candidate.put("blocksWorkGroupCompletion", true);
        candidate.put("durableArtifactsAvailable", true);
        candidate.put(Keys.AVAILABLE, !sameParticipant && !scopeConflict);
        candidate.put("continuationRequest", Map.of("kind", Protocol.KIND_CONTINUATION, "payload", Map.of(Keys.INTENT_ID, intent.intentId().toString(), Keys.CLAIM_EPOCH, intent.version())));
        if (sameParticipant) {
            candidate.put("blockedReason", "CURRENT_PARTICIPANT_IS_FORMER_OWNER");
        } else if (scopeConflict) {
            candidate.put("blockedReason", "OVERLAPPING_CLAIM");
        }
        return candidate;
    }

    /**
     * Returns the highest-priority explicit successor action for the current participant.
     *
     * <p>With exactly one available candidate the executable payload is included; with several the
     * caller must choose explicitly because no candidate is ever auto-assigned.</p>
     *
     * @param participantId exact caller participant
     * @param collaboration public collaboration projection
     * @return a continuation action, or {@code null} when no candidate is available
     */
    private static AgentResponse continuationAction(String participantId, Map<String, Object> collaboration) {
        List<Map<String, Object>> available = projectedList(collaboration, Keys.CONTINUATION_CANDIDATES).stream().filter(candidate -> Boolean.TRUE.equals(candidate.get(Keys.AVAILABLE))).toList();
        if (available.isEmpty()) {
            return null;
        }
        Map<String, Object> selected = available.getFirst();
        Map<String, Object> result = new LinkedHashMap<>(collaboration);
        result.put(Keys.STATE, Protocol.STATE_RECOVERY_CONTINUATION_AVAILABLE);
        result.put("continuationAvailable", true);
        result.put("continuationSelectionRequired", available.size() > 1);
        result.put(Keys.NEXT_PROTOCOL_ACTION, Protocol.ACTION_REQUEST_COORDINATION);
        result.put(Keys.NEXT_PROTOCOL_KIND, Protocol.KIND_CONTINUATION);
        if (available.size() == 1) {
            result.put("selectedContinuation", selected);
            result.put(Keys.NEXT_PROTOCOL_PAYLOAD, Map.of(Keys.INTENT_ID, selected.get(Keys.SOURCE_INTENT_ID), Keys.CLAIM_EPOCH, selected.get(Keys.CLAIM_EPOCH)));
        } else {
            result.put("selectionInstruction", "Choose one continuation candidate explicitly; no candidate is auto-assigned.");
        }
        result.put("continuationParticipant", participantId);
        return new AgentResponse(AgentStatus.READY, AgentReason.CONTINUATION_AVAILABLE, AgentNextAction.REQUEST_COORDINATION, result);
    }

    /**
     * Resolves the first eligible snapshot-publication or no-change completion action.
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return a {@code FINISH_LANE} response, or {@code null} when the lane cannot be finished yet
     */
    private AgentResponse completionRequestAction(PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        Map<String, Object> publishedSnapshot = publishedSnapshotCompletionAction(store, lane.participantId(), actor);
        if (publishedSnapshot != null) {
            return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publishedSnapshot);
        }
        Map<String, Object> publication = snapshotPublicationAction(store, lane, actor);
        if (publication != null) {
            return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, AgentNextAction.FINISH_LANE, publication);
        }
        Map<String, Object> noChange = noChangeCompletionAction(store, lane, actor);
        return noChange == null ? null : new AgentResponse(AgentStatus.READY, null, AgentNextAction.FINISH_LANE, noChange);
    }

    /**
     * Projects the idempotent finish required after a reviewed snapshot is accepted, including when
     * the original worker lane remains dirty.
     *
     * <p>{@code finish_lane} snapshots the visible lane into an immutable commit and deliberately
     * does not require the harness to commit its worktree first. Once review accepts that snapshot,
     * the producer still needs one exact finish call to drive integration and release its binding.
     * The projection is therefore based only on the durable snapshot and the current producer
     * intent; it never authorizes another mutable workspace operation.</p>
     *
     * @param store         durable project event store
     * @param participantId exact caller participant
     * @param actor         capability identity of the caller
     * @return the finish payload, or {@code null} when no accepted published snapshot awaits
     *         producer completion
     */
    private static Map<String, Object> publishedSnapshotCompletionAction(PredictionEventStore store, String participantId, CapabilityActor actor) {
        if (isNullOrBlank(participantId)) {
            return null;
        }
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!isProducerLane(intent, participantId) || AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store, intent, actor.nodeId(), actor.supervisorId(), actor.workerId())) {
                continue;
            }
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).orElse(null);
            if (snapshot == null) {
                continue;
            }
            TaskCompletionState state = snapshotStateOf(store, snapshot);
            if (state != TaskCompletionState.REVIEW_ACCEPTED && state != TaskCompletionState.INTEGRATED) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            return publishedSnapshotCompletionResult(store, intent, participantId, group, snapshot, state);
        }
        return null;
    }

    /** Builds the finish response for a snapshot that has already passed review. */
    private static Map<String, Object> publishedSnapshotCompletionResult(PredictionEventStore store,
            WorkIntent intent,
            String participantId,
            WorkGroup group,
            TaskSnapshotRecord snapshot,
            TaskCompletionState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(Keys.SUMMARY, PUBLISH_SNAPSHOT_SUMMARY);
        putLaneFence(payload, intent, participantId, group.version(), store.headSequence());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.STATE, state.name());
        result.put("snapshotAlreadyPublished", true);
        result.put(Keys.SNAPSHOT, CollaborationResponseMapper.snapshotMap(snapshot, store));
        result.put(Keys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        putNextProtocol(result, Protocol.ACTION_FINISH_LANE, "snapshot_completion", payload);
        return result;
    }

    /**
     * Projects the finish request that publishes a lane's immutable snapshot for the first time.
     *
     * <p>Reviewer lanes may publish only their own claimed report or test artifact. The
     * role-independent snapshot path is safe here because the lane claims and the foreign-claim
     * fence still exclude another participant's source changes. Consuming a {@code REVIEW} grant
     * authorizes publication but does not manufacture implementation work, so the same read-only
     * source/artifact gate that {@code finish_lane} applies while creating the snapshot is applied
     * here. A failed inspection never turns into a false publication permission; the ordinary
     * {@code IMPLEMENT} path stays available.</p>
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the finish detail map, or {@code null} when no publication is due
     */
    private Map<String, Object> snapshotPublicationAction(PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        String participantId = lane.participantId();

        List<ResourceSelector> participantClaims = collaboration.activeIntents().stream().filter(candidate -> candidate.participant().equals(participantId)).flatMap(candidate -> candidate.selectors().stream()).distinct()
                .toList();
        List<ResourceSelector> foreignClaims = foreignClaims(store, participantId);

        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!intent.participant().equals(participantId)) {
                continue;
            }
            Publishability publishability = inspectScopedPublishableChanges(lane.worktree(), intent, participantClaims, foreignClaims, lane.baseCommit());
            if (publishability != Publishability.PUBLISHABLE || !reviewRequirementSatisfied(store, intent, participantId) || AgentReviewActionProjection.hasUnresolvedCapabilityObligation(store,
                    intent,
                    actor.nodeId(),
                    actor.supervisorId(),
                    actor.workerId()) || completion.findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isPresent()) {
                continue;
            }
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) {
                continue;
            }
            return snapshotPublicationResult(store, intent, participantId, group);
        }
        return null;
    }

    /**
     * Returns whether review is either not required or already satisfied for a lane.
     *
     * @param store         current project projection
     * @param intent        the lane's intent
     * @param participantId exact caller participant
     * @return {@code true} if a review grant was consumed, the group's policy makes review
     *         mandatory (and is handled elsewhere), or the task does not require review
     */
    private static boolean reviewRequirementSatisfied(PredictionEventStore store, WorkIntent intent, String participantId) {
        var workGroups = store.workGroupProjection();
        boolean reviewGrantConsumed = workGroups.grants().stream()
                .anyMatch(grant -> grant.workGroupId().equals(intent.workGroupId()) && grant.targetIntentId().equals(intent.intentId()) && grant.claimEpoch() == intent.version() && !grant.targetParticipant()
                        .equals(participantId) && !workGroups.grantSuperseded(grant.grantId()) && workGroups.grantConsumed(grant.grantId()));
        boolean mandatoryPolicyReview = workGroups.group(intent.workGroupId()).map(group -> group.reviewPolicy().mandatory()).orElse(false);
        return reviewGrantConsumed || mandatoryPolicyReview || !AgentTaskCompletionService.reviewRequired(store, intent, participantId);
    }

    /**
     * Builds the finish detail map that publishes a lane's snapshot.
     *
     * @param store         current project projection
     * @param intent        the lane's intent
     * @param participantId exact caller participant
     * @param group         the active work group of the intent
     * @return the {@code snapshotPublicationRequired} detail map
     */
    private static Map<String, Object> snapshotPublicationResult(PredictionEventStore store, WorkIntent intent, String participantId, WorkGroup group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotPublicationRequired", true);
        result.put(Keys.WORK_GROUP_ID, intent.workGroupId().toString());
        result.put(Keys.INTENT_ID, intent.intentId().toString());
        result.put(Keys.CLAIM_EPOCH, intent.version());
        result.put(Keys.PARTICIPANT, participantId);
        result.put(Keys.NEXT_PROTOCOL_ACTION, Protocol.ACTION_FINISH_LANE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(Keys.SUMMARY, PUBLISH_SNAPSHOT_SUMMARY);
        putLaneFence(payload, intent, participantId, group.version(), store.headSequence());
        if (hasRejectedPredecessor(store, intent)) {
            payload.put(Keys.AUTHORITY_LINEAGE_ID, intent.authorityLineageId().toString());
        }
        result.put(Keys.NEXT_PROTOCOL_PAYLOAD, payload);
        return result;
    }

    /**
     * Returns whether this lane corrects a snapshot of an earlier epoch that review rejected.
     *
     * @param store  current project projection
     * @param intent the lane's intent
     * @return {@code true} if a rejected predecessor snapshot exists for the same lane and lineage
     */
    private static boolean hasRejectedPredecessor(PredictionEventStore store, WorkIntent intent) {
        return store.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> snapshot.taskId().equals(intent.taskId()) && snapshot.provenance().laneId().equals(intent.intentId()) && snapshot.provenance().authorityLineageId()
                        .equals(intent.authorityLineageId()) && snapshot.provenance().claimEpoch() < intent.version() && snapshotStateOf(store, snapshot) == TaskCompletionState.REVIEW_REJECTED);
    }

    /**
     * Projects the exact explicit finish request for a clean no-change lane.
     *
     * <p>The payload contains only server-derived identifiers and the current optimistic versions.
     * It is an executable suggestion, not an implicit lifecycle transition; the provider must call
     * {@code finish_lane} with this payload.</p>
     *
     * @param store current project projection
     * @param lane  the caller's lane
     * @param actor capability identity of the caller
     * @return the typed finish payload, or {@code null} when no-change completion is not eligible
     */
    private Map<String, Object> noChangeCompletionAction(PredictionEventStore store, LaneScope lane, CapabilityActor actor) {
        String participantId = lane.participantId();
        for (WorkIntent intent : store.collaborationProjection().activeIntents()) {
            if (!intent.participant().equals(participantId)) {
                continue;
            }
            NoChangeCompletionEligibility.Result eligibility = NoChangeCompletionEligibility.assess(store, intent, participantId, actor.nodeId(), actor.supervisorId(), actor.workerId(), lane.worktree(), snapshotService);
            if (!eligibility.eligible()) {
                continue;
            }
            WorkGroup group = store.workGroupProjection().group(intent.workGroupId()).orElse(null);
            if (group == null) {
                continue;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(Keys.STATE, Protocol.STATE_NO_CHANGE_COMPLETION_READY);
            result.put("noChangeCompletionAvailable", true);
            result.put(Keys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
            result.put(Keys.WORK_GROUP, CollaborationResponseMapper.workGroupMap(group));
            putNextProtocol(result, Protocol.ACTION_FINISH_LANE, "no_change_completion", noChangeCompletionPayload(store, intent, participantId, group));
            return result;
        }
        return null;
    }

    /** Builds the server-versioned finish payload for an eligible no-change lane. */
    private static Map<String, Object> noChangeCompletionPayload(PredictionEventStore store, WorkIntent intent, String participantId, WorkGroup group) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", "no_change");
        putLaneFence(payload, intent, participantId, group.version(), store.headSequence());
        payload.put(Keys.SUMMARY, "Verification completed successfully; no repository mutation was required");
        return payload;
    }

    /**
     * Checks publishability once for projections that share the same lane and claim fence.
     *
     * @param worktree          verified assigned worktree, or {@code null} when unknown
     * @param intent            the lane's intent
     * @param participantClaims selectors claimed by the caller
     * @param foreignClaims     selectors claimed by other participants
     * @param bindingBaseCommit base commit recorded in the provider binding
     * @return the inspection outcome; {@link Publishability#UNKNOWN} when no worktree is known or
     *         the inspection failed
     */
    private Publishability inspectScopedPublishableChanges(Path worktree, WorkIntent intent, List<ResourceSelector> participantClaims, List<ResourceSelector> foreignClaims, String bindingBaseCommit) {
        if (worktree == null) {
            return Publishability.UNKNOWN;
        }
        try {
            String snapshotBaseCommit = snapshotService.snapshotBaseForBoundWorktree(worktree, intent.baseCommit(), bindingBaseCommit);
            boolean publishable = snapshotService.hasScopedPublishableChanges(worktree, intent.selectors(), participantClaims, foreignClaims, snapshotBaseCommit);
            return publishable ? Publishability.PUBLISHABLE : Publishability.NOT_PUBLISHABLE;
        } catch (Exception failure) {
            logSuppressed("scoped publishable-change inspection", failure);
            return Publishability.UNKNOWN;
        }
    }

    /**
     * Collects the claims of other participants so restored durable content can stay visible
     * without becoming this participant's publication authority.
     *
     * @param store         current project projection
     * @param participantId exact caller participant
     * @return the distinct selectors claimed by every other participant
     */
    private static List<ResourceSelector> foreignClaims(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> !intent.participant().equals(participantId)).flatMap(intent -> intent.selectors().stream()).distinct().toList();
    }

    /**
     * Resolves capability-request work: unmet dependencies first, then owner duties, then the
     * requester's view of its own requests.
     *
     * @param store                 current project projection
     * @param actor                 capability identity of the caller
     * @param callerIntent          the caller's active intent, or {@code null} when none exists
     * @param coordinationActivated whether collaboration is active, which restricts requests to the
     *                              caller's authority lineage
     * @return a capability action, or {@code null} when nothing needs attention
     */
    private static AgentResponse capabilityAction(PredictionEventStore store, CapabilityActor actor, WorkIntent callerIntent, boolean coordinationActivated) {
        CapabilityRequestProjection capabilities = store.capabilityRequestProjection();
        if (callerIntent != null) {
            AgentResponse missingDependency = missingDependencyResponse(capabilities, actor, callerIntent);
            if (missingDependency != null) {
                return missingDependency;
            }
        }
        Predicate<CapabilityRequestRecord> inCallerLineage = inCallerLineage(coordinationActivated, callerIntent);

        AgentResponse ownerResponse = ownerCapabilityAction(capabilities, actor, inCallerLineage);
        if (ownerResponse != null) {
            return ownerResponse;
        }
        return requesterCapabilityAction(capabilities, actor);
    }

    /**
     * Builds a predicate that keeps only requests in the caller's authority lineage once
     * collaboration is active.
     *
     * @param coordinationActivated whether collaboration is active
     * @param callerIntent          the caller's active intent, or {@code null} when none exists
     * @return a predicate that accepts every request while collaboration is inactive, otherwise
     *         only requests whose lineage matches the caller's intent
     */
    private static Predicate<CapabilityRequestRecord> inCallerLineage(boolean coordinationActivated, WorkIntent callerIntent) {
        return candidate -> !coordinationActivated || callerIntent != null && callerIntent.authorityLineageId().equals(candidate.authorityLineageId());
    }

    /**
     * Asks the caller to request the first known dependency it has not requested yet.
     *
     * @param capabilities capability-request projection
     * @param actor        capability identity of the caller
     * @param intent       the caller's active intent
     * @return a {@code NEEDS_CAPABILITY} response, or {@code null} when every dependency has been
     *         requested (or there are none)
     */
    private static AgentResponse missingDependencyResponse(CapabilityRequestProjection capabilities, CapabilityActor actor, WorkIntent intent) {
        if (intent.knownDependencies().isEmpty()) {
            return null;
        }
        var requestedCapabilities = capabilities.findAllForRequester(actor.nodeId()).stream().filter(candidate -> candidate.matchesRequester(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                .map(CapabilityRequestRecord::capability).collect(Collectors.toSet());
        String missingDependency = intent.knownDependencies().stream().filter(dependency -> !requestedCapabilities.contains(dependency)).findFirst().orElse(null);
        if (missingDependency == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.CAPABILITY, missingDependency);
        result.put(Keys.REQUIRED_FIELDS, DURABLE_CAPABILITY_FIELDS);
        result.put(Keys.PENDING, intent.knownDependencies().size());
        result.put(Keys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        result.put(Keys.KNOWN_DEPENDENCIES, intent.knownDependencies());
        result.put("coordinationKind", "capability_request");
        return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
    }

    /**
     * Resolves what the caller owes as <em>owner</em> of capability requests: answering pending
     * requests, waiting while an accepted request is implemented, or answering a validation
     * revision.
     *
     * @param capabilities    capability-request projection
     * @param actor           capability identity of the caller
     * @param inCallerLineage filter restricting requests to the caller's authority lineage
     * @return an owner action, or {@code null} when the caller owes nothing as owner
     */
    private static AgentResponse ownerCapabilityAction(CapabilityRequestProjection capabilities, CapabilityActor actor, Predicate<CapabilityRequestRecord> inCallerLineage) {
        List<CapabilityRequestRecord> ownerPending = capabilities.findPendingForOwner(actor.nodeId()).stream().filter(inCallerLineage).toList();
        if (!ownerPending.isEmpty()) {
            return capabilityOwnerResponse(ownerPending, AgentStatus.READY, null, AgentNextAction.RESPOND_COORDINATION);
        }

        List<CapabilityRequestRecord> ownerAccepted = capabilities.records().values().stream().filter(candidate -> candidate.matchesOwner(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                .filter(candidate -> candidate.state() == CapabilityLifecycleState.ACCEPTED || candidate.state() == CapabilityLifecycleState.IMPLEMENTING).filter(inCallerLineage).toList();
        if (!ownerAccepted.isEmpty()) {
            return capabilityOwnerResponse(ownerAccepted, AgentStatus.WAITING, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT);
        }

        List<CapabilityRequestRecord> validationRevisions = capabilities.findValidationRevisionForOwner(actor.nodeId()).stream().filter(inCallerLineage).toList();
        if (validationRevisions.isEmpty()) {
            return null;
        }
        CapabilityRequestRecord first = validationRevisions.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.CAPABILITY_REQUEST_HANDLE, first.handle().value());
        result.put(Keys.REASON, Objects.requireNonNullElse(first.reason(), DEFAULT_REVISION_REASON));
        result.put(Keys.PENDING, validationRevisions.size());
        return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_FAILED, AgentNextAction.RESPOND_TO_VALIDATION_REVISION, result);
    }

    /**
     * Resolves what the caller must do as <em>requester</em> of capability requests.
     *
     * @param capabilities capability-request projection
     * @param actor        capability identity of the caller
     * @return a requester action, or {@code null} when none of the caller's requests needs
     *         attention
     */
    private static AgentResponse requesterCapabilityAction(CapabilityRequestProjection capabilities, CapabilityActor actor) {
        List<CapabilityRequestRecord> pending = capabilities.findPendingForRequester(actor.nodeId()).stream().filter(candidate -> candidate.matchesRequester(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                .toList();
        CapabilityRequestRecord focus = selectRequesterFocus(pending);
        if (focus == null) {
            return null;
        }

        CapabilityLifecycleState state = focus.state();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.CAPABILITY_REQUEST_HANDLE, focus.handle().value());

        if (state == CapabilityLifecycleState.REVISION_REQUESTED) {
            result.put(Keys.CONTRACT, capabilityContractMap(focus));
            result.put(Keys.PENDING, pending.size());
            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, AgentNextAction.REVISE_CAPABILITY_REQUEST, result);
        }
        if (state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
            // The requester must validate the available implementation.
            ImplementationRevisionRecord implementation = capabilities.findLatestImplementation(focus.handle().value()).orElse(null);
            int revision = implementation != null ? implementation.revisionNumber() : 1;
            result.put("inboxItemId", UUID.nameUUIDFromBytes(("capability:" + focus.handle().value()).getBytes(StandardCharsets.UTF_8)).toString());
            result.put(Keys.CAPABILITY, focus.capability());
            result.put(Keys.AUTHORITY_LINEAGE_ID, focus.authorityLineageId().toString());
            result.put("revision", revision);
            result.put(Keys.PENDING, pending.size());
            result.put(Keys.REVIEW_DECISION, Map.of("required", true, "field", "result", "allowedResults", List.of("accepted", "revision_required"), "revisionReasonRequired", true));
            return new AgentResponse(AgentStatus.READY, null, AgentNextAction.VALIDATE_IMPLEMENTATION, result);
        }
        if (state == CapabilityLifecycleState.REJECTED) {
            result.put(Keys.REASON, Objects.requireNonNullElse(focus.reason(), DEFAULT_REJECTION_REASON));
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_REJECTED, AgentNextAction.RETRY, result);
        }
        result.put(Keys.PENDING, pending.size());
        AgentReason reason = state == CapabilityLifecycleState.AWAITING_OWNER ? AgentReason.OWNER_RESPONSE_PENDING : AgentReason.IMPLEMENTATION_UNAVAILABLE;
        return new AgentResponse(AgentStatus.WAITING, reason, AgentNextAction.WAIT, result);
    }

    /**
     * Picks the request a requester should focus on: the first {@code REVISION_REQUESTED} request
     * if any exists, otherwise the first request in an actionable state.
     *
     * @param pending the requester's pending requests, in projection order
     * @return the focus request, or {@code null} when none is in an actionable state
     */
    private static CapabilityRequestRecord selectRequesterFocus(List<CapabilityRequestRecord> pending) {
        CapabilityRequestRecord firstActionable = null;
        for (CapabilityRequestRecord candidate : pending) {
            CapabilityLifecycleState state = candidate.state();
            if (state == CapabilityLifecycleState.REVISION_REQUESTED) {
                return candidate;
            }
            if (firstActionable == null && REQUESTER_ACTIONABLE_STATES.contains(state)) {
                firstActionable = candidate;
            }
        }
        return firstActionable;
    }

    /**
     * Builds the shared owner response for the first pending capability request.
     *
     * @param requests the owner's requests; must not be empty
     * @param status   status to report
     * @param reason   reason to report, or {@code null}
     * @param action   next action to recommend
     * @return the owner response describing the first request and the total pending count
     */
    private static AgentResponse capabilityOwnerResponse(List<CapabilityRequestRecord> requests, AgentStatus status, AgentReason reason, AgentNextAction action) {
        CapabilityRequestRecord request = requests.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Keys.CAPABILITY_REQUEST_HANDLE, request.handle().value());
        result.put(Keys.CAPABILITY, request.capability());
        result.put(Keys.AUTHORITY_LINEAGE_ID, request.authorityLineageId().toString());
        result.put(Keys.CONTRACT, capabilityContractMap(request));
        result.put(Keys.PENDING, requests.size());
        return new AgentResponse(status, reason, action, result);
    }

    /**
     * Builds the stable capability-contract object shared by owner and requester responses.
     *
     * @param request the capability request
     * @return the contract map with inputs, output, required behavior and acceptance tests
     */
    private static Map<String, Object> capabilityContractMap(CapabilityRequestRecord request) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("inputs", request.contract().inputs());
        contract.put("output", request.contract().output());
        contract.put("requiredBehavior", request.contract().requiredBehavior());
        contract.put("acceptanceTests", request.contract().acceptanceTests());
        return contract;
    }

    /**
     * Builds the JSON-safe collaboration discovery and pending-request projection for a request
     * that has no verified binding.
     *
     * <p>The caller's session is matched through the SHA-256 fingerprint of its connection instance
     * id. When no event store exists, or any lookup fails, an empty isolated-workspace projection is
     * returned instead.</p>
     *
     * @param location project location
     * @param request  the next-action request
     * @return a fresh, mutable discovery map; never {@code null}
     */
    private Map<String, Object> collaborationDetailsForRequest(ProjectLocation location, NextActionRequest request) {
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            if (store.isPresent()) {
                String fingerprint = sha256Hex(request.connectionInstanceId());
                var binding = bindingService.list(location, request.provider()).stream().filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())).findFirst().orElse(null);
                return collaborationDetails(store.get(), binding != null ? binding.sessionId() : "");
            }
        } catch (Exception failure) {
            logSuppressed("collaboration discovery", failure);
        }
        Map<String, Object> result = collaborationDetailsBase(0, List.of(), List.of());
        result.put(Keys.PENDING_COORDINATION, List.of());
        return result;
    }

    /**
     * Converts collaboration records into a provider-safe next-action payload.
     *
     * @param store     current project projection
     * @param sessionId session of the caller, or blank for an unbound observer
     * @return a fresh, mutable discovery map
     */
    private static Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId) {
        return collaborationDetails(store, sessionId, null);
    }

    /**
     * Converts collaboration records into a provider-safe next-action payload, optionally
     * restricting review actions to a set of work groups.
     *
     * @param store             current project projection
     * @param sessionId         session of the caller, or blank for an unbound observer
     * @param reviewGroupFilter work groups to which review actions are restricted, or {@code null}
     *                          for no restriction
     * @return a fresh, mutable discovery map whose key order is part of the wire format
     */
    private static Map<String, Object> collaborationDetails(PredictionEventStore store, String sessionId, Set<UUID> reviewGroupFilter) {
        String participantId = sessionId == null || sessionId.isBlank() ? "" : WorkspaceCollaborationService.participantHandle(sessionId);
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var activeIntents = collaboration.activeIntents();

        List<Map<String, Object>> intents = activeIntents.stream().map(CollaborationResponseMapper::intentMap).toList();
        Map<String, Object> currentIntent = activeIntents.stream().filter(intent -> intent.participant().equals(participantId)).map(CollaborationResponseMapper::intentMap).findFirst().orElse(null);
        List<Map<String, Object>> participants = collaboration.participants().stream().map(CollaborationResponseMapper::participantMap).toList();
        List<Map<String, Object>> pending = collaboration.requests().stream().filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .filter(request -> !collaboration.inboxAcknowledged(request.requestId())).filter(request -> participantId.isBlank() || request.target().equals(participantId)).map(CollaborationResponseMapper::requestMap)
                .toList();
        List<Map<String, Object>> enrichedPending = pending.stream().map(request -> AgentReviewActionProjection.enrichPendingRequest(request, store)).toList();

        Map<String, Object> result = collaborationDetailsBase(enrichedPending.size(), participants, intents);
        result.put("groups", workGroups.groups().stream().map(CollaborationResponseMapper::workGroupMap).toList());
        result.put("grants", workGroups.grants().stream().map(CollaborationResponseMapper::laneGrantMap).toList());
        result.put("snapshots", store.taskCompletionProjection().allSnapshots().stream().map(snapshot -> CollaborationResponseMapper.snapshotMap(snapshot, store)).toList());
        result.put("currentParticipant", participantId);
        result.put("currentIntent", currentIntent);
        result.put(Keys.PENDING_COORDINATION, enrichedPending);
        result.put(Keys.CONTINUATION_CANDIDATES, continuationCandidates(store, participantId));

        List<Map<String, Object>> reviewCandidates = AgentReviewActionProjection.reviewCandidates(store);
        result.put("reviewCandidates", reviewCandidates);
        result.put(Keys.REVIEW_CANDIDATE_SELECTION_REQUIRED, !reviewCandidates.isEmpty());
        result.put("actionableReviewCandidates", AgentReviewActionProjection.actionableReviewCandidates(store, participantId, reviewCandidates));
        if (!reviewCandidates.isEmpty()) {
            result.put("reviewCandidateSelectionInstruction", "Choose an exact candidate explicitly; Synesis does not select one for you.");
        }
        result.put("reviewActions", AgentReviewActionProjection.reviewActions(store, participantId, reviewGroupFilter));
        result.put("claimConflicts", List.of());
        return result;
    }

    /**
     * Creates the common discovery fields shared by populated and isolated workspaces.
     *
     * @param pending      number of pending coordination requests
     * @param participants projected participants
     * @param intents      projected active intents
     * @return a fresh, mutable map holding the four common fields in wire order
     */
    private static Map<String, Object> collaborationDetailsBase(int pending, List<Map<String, Object>> participants, List<Map<String, Object>> intents) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", ISOLATED);
        result.put(Keys.PENDING, pending);
        result.put("participants", participants);
        result.put("intents", intents);
        return result;
    }

    /**
     * Resolves the local, file-based fallback when the durable pipeline produced nothing.
     *
     * @param request  the next-action request
     * @param location project location
     * @param worktree the verified assigned worktree
     * @return the idle response when there are no local items, otherwise the response for the
     *         highest-priority item
     */
    private AgentResponse resolveLocalCoordination(NextActionRequest request, ProjectLocation location, Path worktree) {
        List<CoordinationItem> items = loadCoordinationItems(worktree, location.root(), request.provider());
        if (items.isEmpty()) {
            return idleResponse(location, request);
        }
        CoordinationItem top = items.stream().min(Comparator.comparingInt((CoordinationItem item) -> item.type().priority()).thenComparingLong(CoordinationItem::sequence)).orElseThrow();
        return localItemResponse(top, items.size());
    }

    /**
     * Builds the idle response: collaboration discovery plus the durable command diagnostics.
     *
     * @param location project location
     * @param request  the next-action request
     * @return a {@code READY} response without a recommended action
     */
    private AgentResponse idleResponse(ProjectLocation location, NextActionRequest request) {
        Map<String, Object> collaboration = collaborationDetailsForRequest(location, request);
        ProjectCommandDiagnostics.Report command = ProjectCommandDiagnostics.inspect(AdministrativeStateLocator.applicationStateRoot().resolve(COMMANDS_DIR));
        collaboration.put("durableCommands", command.nextActionDetails());
        return new AgentResponse(AgentStatus.READY, null, null, collaboration);
    }

    /**
     * Reads the local coordination items addressed to the target worker.
     *
     * <p>The assigned worktree's inbox takes precedence over the project-level inbox. A malformed
     * file or entry field of the wrong type discards <em>all</em> items (a deliberate fail-quiet
     * inherited from the original implementation; see {@link #logSuppressed}).</p>
     *
     * @param assignedWorktree verified assigned worktree; must not be {@code null}
     * @param projectRoot      project root used when the worktree has no inbox
     * @param targetWorker     worker (provider) name the items must address, case-insensitively
     * @return the matching, non-obsolete items in file order; empty when there is no inbox or it
     *         cannot be read
     */
    private static List<CoordinationItem> loadCoordinationItems(Path assignedWorktree, Path projectRoot, String targetWorker) {
        Path itemsFile = assignedWorktree.resolve(LOCAL_ITEMS_FILE);
        if (!Files.exists(itemsFile)) {
            itemsFile = projectRoot.resolve(LOCAL_ITEMS_FILE);
        }
        if (!Files.exists(itemsFile)) {
            return List.of();
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(itemsFile));
            if (!(parsed instanceof List<?> entries)) {
                return List.of();
            }
            List<CoordinationItem> items = new ArrayList<>();
            long sequence = 0;
            for (Object entry : entries) {
                sequence++;
                if (entry instanceof Map<?, ?> fields) {
                    toCoordinationItem(fields, targetWorker, sequence).ifPresent(items::add);
                }
            }
            return List.copyOf(items);
        } catch (Exception failure) {
            logSuppressed("local coordination inbox", failure);
            return List.of();
        }
    }

    /**
     * Converts one raw inbox entry into a {@link CoordinationItem}.
     *
     * @param fields       the raw JSON object
     * @param targetWorker worker (provider) name the item must address
     * @param sequence     one-based position of the entry in the inbox, used for stable ordering
     * @return the item, or empty when it addresses another worker, is obsolete or completed, or
     *         has no type
     * @throws ClassCastException if {@code workerId}, {@code type}, {@code capability} or
     *                            {@code details} has an unexpected JSON type
     */
    @SuppressWarnings("unchecked")
    private static Optional<CoordinationItem> toCoordinationItem(Map<?, ?> fields, String targetWorker, long sequence) {
        String worker = (String) fields.get("workerId");
        boolean addressedToTarget = worker == null || worker.isBlank() || worker.equalsIgnoreCase(targetWorker);
        if (!addressedToTarget) {
            return Optional.empty();
        }
        boolean obsolete = Boolean.TRUE.equals(fields.get("obsolete")) || Boolean.TRUE.equals(fields.get("completed"));
        if (obsolete) {
            return Optional.empty();
        }
        String type = (String) fields.get("type");
        String capability = (String) fields.get("capability");
        Map<String, Object> details = (Map<String, Object>) fields.get("details");
        if (details == null) {
            details = Map.of();
        }
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(new CoordinationItem(CoordinationItemType.parse(type), capability, details, sequence));
    }

    /**
     * Maps the winning local item to its agent response.
     *
     * @param top          the highest-priority item
     * @param pendingCount total number of local items
     * @return the response for the item's type
     */
    private static AgentResponse localItemResponse(CoordinationItem top, int pendingCount) {
        return switch (top.type()) {
            case SAFETY_FAILURE -> new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            case DEPENDENCY_INVALIDATED -> {
                Map<String, Object> result = new LinkedHashMap<>();
                if (top.capability() != null) {
                    result.put(Keys.CAPABILITY, top.capability());
                }
                result.put(Keys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.DEPENDENCY_INVALIDATED, AgentNextAction.RETRY, result);
            }
            case OWNER_REQUEST -> {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put(Keys.CAPABILITY, top.capability() != null ? top.capability() : UNKNOWN_CAPABILITY);
                request.put("inputs", top.details().getOrDefault("inputs", PLACEHOLDER_TEXT));
                request.put("output", top.details().getOrDefault("output", PLACEHOLDER_TEXT));
                request.put("behavior", top.details().getOrDefault("behavior", PLACEHOLDER_TEXT));
                request.put("acceptanceTest", top.details().getOrDefault("acceptanceTest", PLACEHOLDER_TEXT));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", request);
                result.put(Keys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_REQUEST_PENDING, null, result);
            }
            case NEEDS_CAPABILITY -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(Keys.CAPABILITY, top.capability());
                result.put(Keys.REQUIRED_FIELDS, LOCAL_CAPABILITY_FIELDS);
                result.put(Keys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
            }
            case VALIDATION_REQUIRED -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(Keys.CAPABILITY, top.capability());
                result.put(Keys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, null, result);
            }
            case WAITING_FOR_OWNER -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put(Keys.PENDING, pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
            }
            case UNKNOWN -> AgentResponse.ready(ISOLATED, pendingCount);
        };
    }

    /**
     * Loads, or creates on first use, the identity of the local node.
     *
     * @param location project location whose profile directory stores the identity
     * @return the node identity
     * @throws Exception if the identity cannot be read or created
     */
    private static NodeIdentity loadCallerIdentity(ProjectLocation location) throws Exception {
        return new IdentityBootstrap(location.profile().resolve(LINK_PROFILE_DIR)).loadOrCreate().identity();
    }

    /**
     * Opens the durable event store of a project without checking that it exists yet.
     *
     * @param location project location
     * @return a new store snapshot
     * @throws Exception if the store cannot be opened
     */
    private static PredictionEventStore newEventStore(ProjectLocation location) throws Exception {
        return new PredictionEventStore(location.root().resolve(COORDINATION_DIR), location.projectId());
    }

    /**
     * Opens the durable event store only when an event log exists.
     *
     * @param location project location
     * @return a new store snapshot, or empty when the project has no event log yet
     * @throws Exception if the store cannot be opened
     */
    private static Optional<PredictionEventStore> openEventStore(ProjectLocation location) throws Exception {
        if (!Files.exists(location.root().resolve(COORDINATION_DIR).resolve(EVENTS_DIR))) {
            return Optional.empty();
        }
        return Optional.of(newEventStore(location));
    }

    /**
     * Finds the first active intent owned by a participant.
     *
     * @param store         current project projection
     * @param participantId exact participant handle
     * @return the participant's active intent, if any
     */
    private static Optional<WorkIntent> activeIntentOf(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantId)).findFirst();
    }

    /**
     * Returns the lifecycle state of a snapshot, defaulting to {@link TaskCompletionState#ACTIVE}.
     *
     * @param store    current project projection
     * @param snapshot the snapshot to look up
     * @return the recorded state, or {@code ACTIVE} when none has been recorded
     */
    private static TaskCompletionState snapshotStateOf(PredictionEventStore store, TaskSnapshotRecord snapshot) {
        return store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
    }

    /**
     * Returns whether an intent is a producer lane owned by the participant.
     *
     * @param intent        the intent to test
     * @param participantId exact participant handle
     * @return {@code true} for the participant's own {@code PRODUCER} intents
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isProducerLane(WorkIntent intent, String participantId) {
        return participantId.equals(intent.participant()) && intent.role() == WorkIntent.Role.PRODUCER;
    }

    /**
     * Appends the server-derived revision and ownership fields every finish payload carries.
     *
     * @param payload          payload being built
     * @param intent           the lane's intent
     * @param participantId    exact caller participant
     * @param workGroupVersion current optimistic version of the work group
     * @param expectedRevision current head sequence of the event store
     */
    private static void putLaneFence(Map<String, Object> payload, WorkIntent intent, String participantId, long workGroupVersion, long expectedRevision) {
        payload.put(Keys.INTENT_ID, intent.intentId().toString());
        payload.put(Keys.WORK_GROUP_ID, intent.workGroupId().toString());
        payload.put(Keys.CLAIM_EPOCH, intent.version());
        payload.put("workGroupVersion", workGroupVersion);
        payload.put("expectedRevision", expectedRevision);
        payload.put(Keys.PARTICIPANT, participantId);
    }

    /**
     * Appends the three-part protocol directive that tells the provider which tool to call next.
     *
     * @param result  response detail map being built
     * @param action  protocol action, such as {@link Protocol#ACTION_WAIT}
     * @param kind    protocol kind qualifying the action
     * @param payload executable arguments for the action
     */
    private static void putNextProtocol(Map<String, Object> result, String action, String kind, Map<String, Object> payload) {
        result.put(Keys.NEXT_PROTOCOL_ACTION, action);
        result.put(Keys.NEXT_PROTOCOL_KIND, kind);
        result.put(Keys.NEXT_PROTOCOL_PAYLOAD, payload);
    }

    /**
     * Converts an intent's selectors into their public wire representation.
     *
     * @param intent the intent whose selectors to convert
     * @return the selector maps in intent order
     */
    private static List<Map<String, Object>> selectorMaps(WorkIntent intent) {
        return intent.selectors().stream().map(CollaborationResponseMapper::selectorMap).toList();
    }

    /**
     * Returns whether any claimed selector overlaps any requested selector.
     *
     * @param claimed   selectors already claimed
     * @param requested selectors being requested
     * @return {@code true} if at least one pair overlaps
     */
    private static boolean overlapsAny(Collection<ResourceSelector> claimed, Collection<ResourceSelector> requested) {
        return claimed.stream().anyMatch(existing -> requested.stream().anyMatch(existing::overlaps));
    }

    /**
     * Reads a list of maps out of a collaboration projection.
     *
     * @param collaboration public collaboration projection
     * @param key           key whose value is a list of maps
     * @return the stored list, or an empty list when the key is absent
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> projectedList(Map<String, Object> collaboration, String key) {
        return (List<Map<String, Object>>) collaboration.getOrDefault(key, List.of());
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
        result.put(Keys.PENDING, pending);
        return result;
    }

    /**
     * Returns whether a string is {@code null} or consists only of whitespace.
     *
     * @param value the string to test
     * @return {@code true} if {@code value} is {@code null} or blank
     */
    private static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Computes the lower-case hexadecimal SHA-256 digest of a string's UTF-8 bytes.
     *
     * @param value the text to hash
     * @return the 64-character hexadecimal digest
     * @throws IllegalStateException if the JVM lacks SHA-256, which the Java platform guarantees
     */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every Java platform implementation", impossible);
        }
    }

    /**
     * Reports a failure that is deliberately converted into a fallback result.
     *
     * <p>Keeping the fallback while still emitting the cause makes a persistently failing
     * projection diagnosable instead of silently degrading to a less specific response.</p>
     *
     * @param operation short description of the step that failed
     * @param failure   the suppressed failure
     */
    private static void logSuppressed(String operation, Exception failure) {
        LOGGER.log(System.Logger.Level.WARNING, "Next-action step failed and was suppressed: " + operation, failure);
    }

    /**
     * Identifies the caller's lane inside the project.
     *
     * @param participantId exact participant handle of the calling session
     * @param worktree      verified assigned worktree, or {@code null} when unknown
     * @param baseCommit    base commit recorded in the provider binding
     */
    private record LaneScope(String participantId, Path worktree, String baseCommit) {

    }

    /**
     * Identifies the caller in capability-request matching.
     *
     * @param nodeId       node identity of the calling process
     * @param supervisorId supervisor recorded in the provider binding
     * @param workerId     worker recorded in the provider binding
     */
    private record CapabilityActor(String nodeId, String supervisorId, String workerId) {

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

    /**
     * Represents a pending item from the local coordination inbox.
     *
     * @param type       item type, which determines both its priority and its response
     * @param capability capability identifier, or {@code null}
     * @param details    additional detail payload; never {@code null}
     * @param sequence   one-based position in the inbox, used for deterministic ordering
     */
    private record CoordinationItem(CoordinationItemType type, String capability, Map<String, Object> details, long sequence) {

    }

    /**
     * Types of local coordination items, declared in descending priority.
     */
    private enum CoordinationItemType {

        /**
         * A safety check failed; a human must intervene.
         */
        SAFETY_FAILURE(1),

        /**
         * A dependency changed underneath the worker, which must retry.
         */
        DEPENDENCY_INVALIDATED(2),

        /**
         * The worker owns a capability request it has to answer.
         */
        OWNER_REQUEST(3),

        /**
         * The worker must describe a capability it needs.
         */
        NEEDS_CAPABILITY(4),

        /**
         * A delivered capability awaits validation.
         */
        VALIDATION_REQUIRED(5),

        /**
         * The worker waits for a capability owner to respond.
         */
        WAITING_FOR_OWNER(6),

        /**
         * Any type this service does not recognize; ranked last and treated as idle.
         */
        UNKNOWN(99);

        /**
         * Sort key; lower values are served first.
         */
        private final int priority;

        /**
         * Creates a type with its priority.
         *
         * @param priority sort key; lower values are served first
         */
        CoordinationItemType(int priority) {
            this.priority = priority;
        }

        /**
         * Returns the sort key of this type.
         *
         * @return the priority; lower values are served first
         */
        int priority() {
            return priority;
        }

        /**
         * Parses an inbox type name case-insensitively.
         *
         * @param raw the raw type name, possibly {@code null}
         * @return the matching type, or {@link #UNKNOWN} when {@code raw} is {@code null} or
         *         unrecognized
         */
        static CoordinationItemType parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            String normalized = raw.toUpperCase(Locale.ROOT);
            for (CoordinationItemType type : values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * Result of checking a worktree for publishable changes.
     */
    private enum Publishability {

        /**
         * Claim-covered publishable changes exist.
         */
        PUBLISHABLE,

        /**
         * The inspection succeeded and found nothing to publish.
         */
        NOT_PUBLISHABLE,

        /**
         * No worktree is known or the inspection failed; never treated as permission.
         */
        UNKNOWN
    }

    /**
     * JSON keys of the next-action wire format that this class writes or reads in more than one
     * place. Keys used at a single site stay inline next to the value they describe.
     */
    private static final class Keys {

        /**
         * Lifecycle state of a projection.
         */
        static final String STATE = "state";

        /**
         * Lane (session) a terminal or completed response refers to.
         */
        static final String LANE = "lane";

        /**
         * Human- or machine-readable reason accompanying a response.
         */
        static final String REASON = "reason";

        /**
         * Number of pending items behind the reported one.
         */
        static final String PENDING = "pending";

        /**
         * Identifier of a work intent.
         */
        static final String INTENT_ID = "intentId";

        /**
         * Identifier of a work group.
         */
        static final String WORK_GROUP_ID = "workGroupId";

        /**
         * Claim epoch (intent version) the response is bound to.
         */
        static final String CLAIM_EPOCH = "claimEpoch";

        /**
         * Participant handle of the caller.
         */
        static final String PARTICIPANT = "participant";

        /**
         * Authority lineage identifier shared across continuations.
         */
        static final String AUTHORITY_LINEAGE_ID = "authorityLineageId";

        /**
         * Human-readable summary of a finish payload.
         */
        static final String SUMMARY = "summary";

        /**
         * Projection of the caller's active intent.
         */
        static final String CURRENT_INTENT = "currentIntent";

        /**
         * Projection of an immutable snapshot.
         */
        static final String SNAPSHOT = "snapshot";

        /**
         * Projection of a work group.
         */
        static final String WORK_GROUP = "workGroup";

        /**
         * Capability name.
         */
        static final String CAPABILITY = "capability";

        /**
         * Handle that identifies a capability request.
         */
        static final String CAPABILITY_REQUEST_HANDLE = "capabilityRequestHandle";

        /**
         * Capability contract (inputs, output, behavior, acceptance tests).
         */
        static final String CONTRACT = "contract";

        /**
         * Dependencies an intent declared up front.
         */
        static final String KNOWN_DEPENDENCIES = "knownDependencies";

        /**
         * Fields a requester must supply.
         */
        static final String REQUIRED_FIELDS = "requiredFields";

        /**
         * Resource selectors claimed by an intent.
         */
        static final String SELECTORS = "selectors";

        /**
         * Decision descriptor the caller must complete.
         */
        static final String REVIEW_DECISION = "reviewDecision";

        /**
         * Participant acting as reviewer.
         */
        static final String REVIEWER_PARTICIPANT = "reviewerParticipant";

        /**
         * Protocol action the provider should perform next.
         */
        static final String NEXT_PROTOCOL_ACTION = "nextProtocolAction";

        /**
         * Protocol kind that qualifies {@link #NEXT_PROTOCOL_ACTION}.
         */
        static final String NEXT_PROTOCOL_KIND = "nextProtocolKind";

        /**
         * Executable arguments for {@link #NEXT_PROTOCOL_ACTION}.
         */
        static final String NEXT_PROTOCOL_PAYLOAD = "nextProtocolPayload";

        /**
         * Pending coordination requests addressed to the caller.
         */
        static final String PENDING_COORDINATION = "pendingCoordination";

        /**
         * Recovery-held work the caller may continue.
         */
        static final String CONTINUATION_CANDIDATES = "continuationCandidates";

        /**
         * Whether the caller must choose a review candidate explicitly.
         */
        static final String REVIEW_CANDIDATE_SELECTION_REQUIRED = "reviewCandidateSelectionRequired";

        /**
         * Identifier of the recovery-held intent a continuation would take over.
         */
        static final String SOURCE_INTENT_ID = "sourceIntentId";

        /**
         * Whether a continuation candidate can be taken by the caller.
         */
        static final String AVAILABLE = "available";

        /**
         * Not instantiable.
         */
        private Keys() {
        }
    }

    /**
     * Protocol vocabulary of the next-action wire format: action names, kinds and state values.
     */
    private static final class Protocol {

        /**
         * Tell the provider to wait.
         */
        static final String ACTION_WAIT = "wait";

        /**
         * Tell the provider to finish its lane.
         */
        static final String ACTION_FINISH_LANE = "finish_lane";

        /**
         * Tell the provider to publish an implementation revision.
         */
        static final String ACTION_IMPLEMENT = "implement";

        /**
         * Tell the provider to open a coordination request.
         */
        static final String ACTION_REQUEST_COORDINATION = "request_coordination";

        /**
         * Tell the provider to answer a coordination request.
         */
        static final String ACTION_RESPOND_COORDINATION = "respond_coordination";

        /**
         * Tell the provider to record a review decision.
         */
        static final String ACTION_REVIEW_DECISION = "review_decision";

        /**
         * Protocol kind and request kind for taking over recovery-held work.
         */
        static final String KIND_CONTINUATION = "continuation";

        /**
         * State of a session that was terminalized.
         */
        static final String STATE_SESSION_TERMINAL = "SESSION_TERMINAL";

        /**
         * State of a lane that finished its work.
         */
        static final String STATE_COMPLETED = "COMPLETED";

        /**
         * State of a producer that must publish a corrected revision.
         */
        static final String STATE_REVISION_REQUIRED = "REVISION_REQUIRED";

        /**
         * State of a participant that may take over recovery-held work.
         */
        static final String STATE_RECOVERY_CONTINUATION_AVAILABLE = "RECOVERY_CONTINUATION_AVAILABLE";

        /**
         * State of a clean lane that may finish without publishing a change.
         */
        static final String STATE_NO_CHANGE_COMPLETION_READY = "NO_CHANGE_COMPLETION_READY";

        /**
         * State of a producer waiting for its reviewer to be admitted and to consume a grant.
         */
        static final String STATE_REVIEW_GRANT_PENDING = "REVIEW_GRANT_PENDING";

        /**
         * Not instantiable.
         */
        private Protocol() {
        }
    }
}
