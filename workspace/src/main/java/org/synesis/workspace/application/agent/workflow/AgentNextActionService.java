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
     * What the integration pump answers when it has nothing to say to a caller. The pump's wait for review is about snapshots
     * of this computer's lanes and is shown to every caller; a producer that has not published has a step of its own instead
     * (s5-run15: a lane with finished work was told to wait for another lane's review, stopped, and its work never landed).
     */
    private static final AgentResponse NO_PUMP_WAIT = new AgentResponse(AgentStatus.READY, null, AgentNextAction.RETRY, Map.of());

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
                || response.reason() != AgentReason.IMPLEMENTATION_UNAVAILABLE
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
            if (capability == null || capability.contractRevision() != revision.longValue()) {
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
            AgentResponse notReady = notReadyResponse(request, inspection);
            // A stale workspace is the first answer a returning agent gets: it carries the notices too.
            return inspection.exactBinding() == null ? notReady : notingDisputesAndTrash(notReady, inspection.location(), inspection.exactBinding());
        }

        AgentResponse durable = resolveDurableCoordination(request, root, inspection.location(), readiness.binding(), readiness.worktree());
        AgentResponse resolved = durable != null ? durable : LocalCoordinationFallback.resolveLocalCoordination(bindingService, request, inspection.location(), readiness.worktree());
        AgentResponse answered = notingDisputesAndTrash(notingRejection(resolved, inspection.location(), readiness.binding()), inspection.location(), readiness.binding());
        AgentResponse noted = request.completionRequested() ? notingNothingToPublish(answered, inspection.location(), readiness.binding(), readiness.worktree()) : answered;
        return notingReviewNotes(noted, inspection.location(), readiness.binding());
    }

    /**
     * Tells a reviewer that has recorded its verdict to write its review notes and finish (ADR-0102 item 10). It
     * replaces the nothing-to-publish advice, which offered a reviewer the cancellation of the lane it must finish.
     */
    private static AgentResponse notingReviewNotes(AgentResponse response, ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            return store.isEmpty() ? response : ReviewNotes.onceDecided(response, store.get(), WorkspaceCollaborationService.participantHandle(binding.sessionId()));
        } catch (Exception failure) {
            logSuppressed("review notes projection", failure);
            return response;
        }
    }

    /**
     * Says that the project's command is no longer the one that failed. A lane that was shown only
     * the old failure, after an operator had changed the command, told the operator the old
     * command still failed and never tried the new one.
     */
    private static String commandChangedSince(ProjectLocation location, Object failedCommand) {
        String now = location.validation() == null ? null : org.synesis.workspace.infrastructure.json.ProviderJson.write(location.validation().argv());
        if (failedCommand == null || failedCommand.equals(now)) {
            return "";
        }
        return now == null ? " The project declares no validation command any more: ask to finish again."
                : " The project's validation command has been changed since that failure and is now " + now + ": your work has not been tried against it, so ask to finish again.";
    }

    /**
     * Tells a producer whose last snapshot was rejected that it was, and why, in whatever answer
     * it is given until it submits a new one.
     *
     * @param response the resolved answer
     * @param location project location
     * @param binding  the verified binding of the calling session
     * @return the answer, carrying the rejected snapshot and its review decision when one is unanswered
     */
    private AgentResponse notingRejection(AgentResponse response, ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        if (response.result() != null && !(response.result() instanceof Map<?, ?>)) {
            return response;
        }
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            Map<String, Object> rejected = store.isEmpty() ? null : ReviewLaneProjection.unansweredRejection(store.get(), WorkspaceCollaborationService.participantHandle(binding.sessionId()));
            if (rejected == null) {
                return response;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            putResult(result, response.result(), null);
            result.putIfAbsent("rejectedSnapshot", rejected);
            // Worded by who refused it. Told that a reviewer had rejected it, a lane refused by
            // the validation command would look for a review decision that does not exist.
            result.putIfAbsent("instruction", rejected.get("validationFailure") instanceof Map<?, ?> failure
                    ? "The project's validation command failed on your last snapshot combined with the work integrated before it; rejectedSnapshot.validationFailure.output is what it printed "
                            + "and .command is what was run. Correct it in your claimed files. Another lane's files are not yours to change: ask that lane with send_message or a contract change request. "
                            + "If nothing in your claimed files can make the command pass, tell the operator why with send_message to [\"operator\"] and stop. "
                            + "Otherwise ask to finish again with get_next_action and completionRequested; that publishes your files as they are, changed or not."
                            + commandChangedSince(location, failure.get("command"))
                    : "The reviewer rejected your last snapshot; rejectedSnapshot.reviewDecision.reason says what to correct. Make that correction in your claimed files, "
                            + "then ask to finish again with get_next_action and completionRequested.");
            return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
        } catch (Exception failure) {
            logSuppressed("rejection projection", failure);
            return response;
        }
    }

    /**
     * Adds what every answer carries while it applies ({@link AnswerNotices}): a review dispute the caller is a party
     * to, its lane taken over by another computer, its work in the trash (ADR-0098 step 7).
     *
     * @param response the resolved answer
     * @param location project location
     * @param binding  the verified binding of the calling session
     * @return the answer with those notices, or as it was when none applies
     */
    private AgentResponse notingDisputesAndTrash(AgentResponse response, ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        if (response.result() != null && !(response.result() instanceof Map<?, ?>)) {
            return response;
        }
        try {
            Optional<PredictionEventStore> store = openEventStore(location);
            Map<String, Object> notices = store.isEmpty() ? Map.of()
                    : AnswerNotices.forParticipant(store.get(), location.root(), WorkspaceCollaborationService.participantHandle(binding.sessionId()));
            if (notices.isEmpty()) {
                return response;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            putResult(result, response.result(), null);
            notices.forEach(result::putIfAbsent);
            return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
        } catch (Exception failure) {
            logSuppressed("dispute and trash projection", failure);
            return response;
        }
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
                // The refusal is explained here too. An operator's change of the validation
                // command moves the control head, so the lane that most needs to be told the
                // command changed is the one whose workspace has just become stale.
                return notingRejection(staleAction, inspection.location(), exactBinding);
            }
            AgentResponse repairWait = staleRepairWait(inspection.location(), exactBinding);
            if (repairWait != null) {
                return repairWait;
            }
            if (!request.completionRequested() && hasActiveIntent(inspection.location(), exactBinding)) {
                return staleLaneCompletionRequest();
            }
        }
        return readiness.response();
    }

    /**
     * The answer for a stale session that holds a lane with work it has not published. Its worktree cannot be refreshed
     * ({@code ensure_session} says to follow this projection) and it asked for nothing, so the readiness response sent it
     * back to {@code ensure_session}: in s12a-run2 an agent was resumed 15 times on that loop. Publishing is the way out
     * the protocol already has: the completion request projects {@code finish_lane}, the snapshot is merged or, when
     * main moved under it, repaired by another agent (ADR-0098 step 5 item 54). Nothing is mutated here.
     */
    private static AgentResponse staleLaneCompletionRequest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completionRequested", true);
        result.put("instruction", "Your worktree is stale and holds work you have not published, and it cannot be refreshed without risking that work. Do not call ensure_session. "
                + "Publish your lane: call get_next_action again with completionRequested true and follow the action it projects. If main moved under your files, "
                + "the merge conflict is repaired by another agent.");
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_STALE, AgentNextAction.RETRY, result);
    }

    /**
     * The answer for a stale session whose published snapshot needs repair. {@code ensure_session} sends a stale-dirty
     * session to this projection, and the readiness response sent it back, so the producer looped until the run ended
     * (ADR-0098 step 5 item 53, s11b-run1). The repair lane is joined by another participant, never by the author
     * (the repair lane's rule in ADR-0100), and the author's worktree is not touched: the work it holds is in the
     * snapshot, and a refresh could lose what the snapshot does not hold. So the author is told to wait.
     *
     * @return a wait naming the snapshot, or {@code null} when no snapshot of this session needs repair
     */
    private AgentResponse staleRepairWait(ProjectLocation location, ProviderSessionBindingService.Binding binding) {
        try {
            Optional<PredictionEventStore> openedStore = openEventStore(location);
            if (openedStore.isEmpty()) {
                return null;
            }
            var completion = openedStore.get().taskCompletionProjection();
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            return completion.allSnapshots().stream()
                    .filter(snapshot -> snapshot.provenance().participant().equals(participant)
                            && completion.taskState(snapshot.taskId()) == TaskCompletionState.REPAIR_REQUIRED)
                    .findFirst().map(snapshot -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put(NextActionKeys.STATE, "repair_required");
                        result.put("snapshotId", snapshot.snapshotId());
                        result.put("instruction", "Your published snapshot " + snapshot.snapshotId() + " could not be merged onto main and needs a repair lane, which another participant joins. "
                                + "Your worktree is stale and holds work that is already in that snapshot; it is left as it is. Do not call ensure_session and do not edit. Wait for the repair; "
                                + "get_next_action tells you when your lane has ended.");
                        return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.WAIT, result);
                    }).orElse(null);
        } catch (Exception failure) {
            logSuppressed("stale-workspace repair projection", failure);
            return null;
        }
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

            AgentResponse reviewLane = ReviewLaneProjection.reviewLaneAction(snapshotService, store, collaboration, lane, true, completionRequested);
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
     *   <li>for a caller that holds no lane, a recovery continuation, then a combine lane offered to it,</li>
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

        AgentResponse action = stagedAction(request, location, binding, worktree, store, callerIdentity, integrationPump);
        if (operatorHolds(integrationPump, action, store, binding)) {
            return integrationPump;
        }
        return absentConsumerHold(location, action, binding).orElse(action);
    }

    /**
     * Tells an owner that is told to wait, and one of whose bound consumers counts as absent,
     * that the consumer has been put to the operator (ADR-0092). Told only to keep waiting, it
     * polled to its limit for an agent that was not coming back. An owner whose own consumers
     * are all present keeps its wait: another owner's absent consumer is not its concern.
     */
    private Optional<AgentResponse> absentConsumerHold(ProjectLocation location, AgentResponse action, ProviderSessionBindingService.Binding binding) throws Exception {
        if (action == null || action.nextAction() != AgentNextAction.WAIT) {
            return Optional.empty();
        }
        // Read after the pump: the pass that has just run is the one that raises the request,
        // and the projection opened before it would tell the owner to wait one poll longer.
        PredictionEventStore current = newEventStore(location);
        Optional<org.synesis.coordination.domain.operator.OperatorRequest> request = current.operatorRequestProjection().open(org.synesis.coordination.domain.operator.OperatorRequest.BOUND_CONSUMER_ABSENT);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        java.util.Set<java.util.UUID> ownLineages = current.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant))
                .map(WorkIntent::authorityLineageId).collect(java.util.stream.Collectors.toSet());
        long now = System.currentTimeMillis();
        // What is shown is who is absent now, not who was when the request was raised.
        List<String> absent = org.synesis.workspace.application.integration.ConsumerAbsenceGuard.owed(location.root(), current).stream()
                .filter(owed -> now >= owed.absentAtEpochMillis() && ownLineages.contains(owed.binding().authorityLineageId()))
                .map(org.synesis.workspace.application.integration.ConsumerAbsenceGuard.Owed::described).toList();
        if (absent.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("id", request.get().id());
        shown.put("condition", request.get().condition());
        shown.put("summary", request.get().summary());
        shown.put("evidence", absent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operatorRequest", shown);
        result.put("instruction", "A lane bound to your contract has not acted and its agent has not returned, so Synesis has asked the operator to start one. "
                + "Your work is kept and is integrated when that lane has checked it or has ended. Stop here and do not poll.");
        return Optional.of(new AgentResponse(AgentStatus.BLOCKED, AgentReason.OPERATOR_REQUIRED, AgentNextAction.REQUEST_HUMAN_HELP, result));
    }

    /**
     * Tells whether an open operator request is what this caller is waiting on.
     *
     * <p>The request stops integration. A lane that still has a step of its own keeps that step:
     * it is held only when integration is all it waits for, or when it has nothing to do and its
     * published snapshot is waiting to be integrated. Shown an ordinary implementation step
     * instead, the owner of a refused snapshot polled to the limit and stopped.</p>
     */
    private static boolean operatorHolds(AgentResponse pump, AgentResponse action, PredictionEventStore store, ProviderSessionBindingService.Binding binding) {
        if (pump.reason() != AgentReason.OPERATOR_REQUIRED) {
            return false;
        }
        if (action != null) {
            return action.reason() == AgentReason.INTEGRATION_PENDING;
        }
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        java.util.Set<java.util.UUID> waiting = store.taskCompletionProjection().lineagesAwaitingIntegration();
        return store.collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.participant().equals(participant) && waiting.contains(intent.authorityLineageId()));
    }

    /** Evaluates the stages after the integration pump, in the order {@link #durableCoordinationAction} documents. */
    private AgentResponse stagedAction(NextActionRequest request, ProjectLocation location, ProviderSessionBindingService.Binding binding, Path worktree, PredictionEventStore store, NodeIdentity callerIdentity,
            AgentResponse integrationPump) throws Exception {
        Map<String, Object> collaboration = CollaborationView.collaborationDetails(store, binding.sessionId(), null, binding.provider(), callerIdentity.nodeId());
        LaneScope lane = new LaneScope(WorkspaceCollaborationService.participantHandle(binding.sessionId()), worktree, binding.baseCommit());
        CapabilityActor actor = new CapabilityActor(callerIdentity.nodeId(), binding.supervisorId(), binding.workerId(), binding.sessionId());

        AgentResponse reviewLane = ReviewLaneProjection.reviewLaneAction(snapshotService, store, collaboration, lane, false, request.completionRequested());
        if (reviewLane != null) {
            return reviewLane;
        }

        if (request.completionRequested()) {
            AgentResponse owedReply = LaneCompletionProjection.owedOwnerReply(store, lane, actor);
            if (owedReply != null) {
                return owedReply;
            }
            // A request still waiting for this owner's implementation outranks the verdict of
            // another requester: the owner was shown that wait while the rest waited for it.
            AgentResponse owedPublication = LaneCompletionProjection.capabilityPublicationAction(snapshotService, store, lane, actor);
            if (owedPublication != null) {
                return owedPublication;
            }
            AgentResponse awaitedVerdict = LaneCompletionProjection.awaitedVerdict(store, lane, actor);
            if (awaitedVerdict != null) {
                return awaitedVerdict;
            }
            AgentResponse awaitedAnswer = LaneCompletionProjection.awaitedRequesterAnswer(store, lane, actor);
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

        Optional<WorkIntent> callerIntent = activeIntentOf(store, lane.participantId());
        boolean coordinationActivated = store.collaborationProjection().activated();
        if (integrationPump.status() == AgentStatus.WAITING && integrationPump.reason() == AgentReason.INTEGRATION_PENDING && callerIntent.isPresent()) {
            // One lane's snapshot waiting on a dependency makes the pump answer WAIT to every
            // caller. A caller with a capability step of its own, a published implementation to
            // validate or a request to answer or publish for, is the one who can end that wait.
            // Shown only the wait, five agents waited on each other.
            AgentResponse owed = CapabilityActionProjection.capabilityAction(store, actor, callerIntent.get(), coordinationActivated);
            // A step is the caller's own when it names something to do. A wait is not, and
            // neither is a note that a dependency is satisfied. Asking for a binding handle
            // here hid the change requests an owner has to answer, which carry none.
            if (owed != null && owed.nextAction() != null && owed.nextAction() != AgentNextAction.WAIT) {
                return owed;
            }
        }

        AgentResponse continuation = RecoveryContinuationProjection.continuationAction(store, lane.participantId(), collaboration);
        if (callerIntent.isEmpty() && continuation != null) {
            // A caller that holds no lane has nothing of its own for a project-wide wait to keep
            // reachable, and a continuation is its only way to a lane. A resumed implementer whose
            // snapshot awaited review was shown that wait instead, never took its lane back, and
            // so never admitted the reviewer the wait was for.
            return continuation;
        }
        if (coordinationActivated && callerIntent.isEmpty() && !combineCandidates(store, lane.participantId(), location).isEmpty()) {
            // So is a combine lane, and the run controller resumes a stopped agent for it (designer item 22). The pump's
            // wait is for other lanes, and a combine's group never had a lane, so the project can look finished while
            // it waits. In s5-run6 the agent resumed for a combine was told to wait for another lane's review, and stopped.
            return claimRequiredResponse(store, lane.participantId(), collaboration, location);
        }
        AgentResponse integrationOrContinuation = integrationOrContinuationAction(store, collaboration, pumpShownTo(store, callerIntent, integrationPump), continuation);
        if (integrationOrContinuation != null) {
            return integrationOrContinuation;
        }

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
            return claimRequiredResponse(store, lane.participantId(), collaboration, location);
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
     * What of the integration pump's answer the caller is shown. A producer that has published nothing yet is not shown a
     * wait that is for other lanes or for the pass itself: the review wait, the combine wait, which names the unlanded
     * snapshot of every combine lane, and another pass holding the integration lock ({@code integrationRunning}). Its own
     * publication is its next step (ADR-0098 step 5 items 35 and 36). In s5-run20 the agent of a second combine lane was told
     * the first lane's snapshot would land and that nothing more was needed from it; in s5-run21 it was told
     * {@code integrationRunning} because another pass held the lock at that moment. Either way it stopped, nothing recorded
     * in the log ends such a wait, so no wake came, and a re-merge of two combines cannot land before both have a result.
     * The pump's waits on a dependency or a request of the lane itself stay shown.
     *
     * <p>The combine wait says that nothing more is needed from the lane. That is true of a combine lane that has published
     * and of no other, so it is shown to no lane outside a combine's work group (item 37): a reviewer whose verdict was
     * recorded was told so in s5-run22, kept its lane open, and so was never offered the second combine, which only a
     * lane-less agent is.</p>
     */
    static AgentResponse pumpShownTo(PredictionEventStore store, Optional<WorkIntent> callerIntent, AgentResponse pump) {
        Map<?, ?> result = pump.result() instanceof Map<?, ?> shown ? shown : Map.of();
        boolean combineWait = pump.reason() == AgentReason.INTEGRATION_PENDING && result.containsKey("combinePending");
        boolean otherLanesWait = pump.reason() == AgentReason.VALIDATION_REQUIRED || combineWait
                || pump.reason() == AgentReason.INTEGRATION_PENDING && result.containsKey("integrationRunning");
        boolean combineWaitForAnotherLane = combineWait && callerIntent.isPresent() && store.taskCompletionProjection().combineOfGroup(callerIntent.get().workGroupId()).isEmpty();
        return (otherLanesWait && producerStillToPublish(store, callerIntent)) || combineWaitForAnotherLane ? NO_PUMP_WAIT : pump;
    }

    /** Whether the caller holds a producer lane that has published nothing yet: its own publication is its next step. */
    private static boolean producerStillToPublish(PredictionEventStore store, Optional<WorkIntent> callerIntent) {
        return callerIntent.filter(intent -> intent.role() == WorkIntent.Role.PRODUCER)
                .filter(intent -> store.taskCompletionProjection().findSnapshotForTaskRevision(intent.taskId(), intent.intentId(), intent.version()).isEmpty()).isPresent();
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
        // An operator request stops integration, not every lane: it is shown to the lanes that
        // wait on integration, by the caller, and not to one that still has work to do.
        boolean pumpIsNonTerminal = pump.status() != AgentStatus.READY && pump.status() != AgentStatus.COMPLETED && pump.reason() != AgentReason.OPERATOR_REQUIRED;
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
     * @param store         current project projection
     * @param participantId exact participant handle of the caller
     * @param collaboration public collaboration projection
     * @return a {@code BLOCKED} response asking the caller to ensure its session
     */
    private static AgentResponse claimRequiredResponse(PredictionEventStore store, String participantId, Map<String, Object> collaboration,
            ProjectLocation location) {
        Map<String, Object> claimRequired = new LinkedHashMap<>(collaboration);
        claimRequired.put(NextActionKeys.CLAIMS_REQUIRED, true);
        claimRequired.put(NextActionKeys.REASON, AgentReason.COORDINATION_INTENT_REQUIRED.value());
        // An agent told only to call ensure_session called it without a task until its polls ran
        // out, and left without reading the goal.
        // A review candidate is as much a way to a lane as an unclaimed part. Shown only the
        // unclaimed part, a fresh agent left a snapshot awaiting review unreviewed.
        String choice = AgentReviewActionProjection.reviewCandidatesFor(store, participantId).isEmpty() ? "choose a part that no listed participant claims, and announce it by calling "
                : "either choose a part that no listed participant claims, or review a snapshot awaiting review: reviewCandidates[i].reviewerIntent holds its ensure_session arguments, "
                        + "which already claim the file you write your review notes to. Announce your choice by calling ";
        // Run 6: a fresh agent read the README's "python -m unittest passes" as a part nobody
        // claimed and announced a lane for it, though every integration already runs that command.
        String validation = location.validation() == null ? "" : " Synesis runs this project's validation command (`" + String.join(" ", location.validation().argv())
                + "`) on every integration, so running or reporting it is not a part to claim.";
        claimRequired.put("instruction", "This session holds no lane. Read the project goal with read_file, " + choice
                + "ensure_session with a task (goal, acceptance, claims). Calling ensure_session without a task changes nothing." + validation);
        // Two copies of main that Git could not merge wait for an agent to combine them; nothing reaches main until one
        // change holds both (ADR-0098 step 5, designer item 22).
        List<Map<String, Object>> combines = combineCandidates(store, participantId, location);
        if (!combines.isEmpty()) {
            claimRequired.put("combineCandidates", combines);
            claimRequired.put("instruction", "This session holds no lane, and two copies of main could not be merged: take the combine lane first by calling ensure_session "
                    + "with combineCandidates[0].ensureSession. Your worktree then holds both versions with Git's conflict markers; make one change that keeps both, "
                    + "with no markers left, and finish the lane as usual. If both cannot be kept, send a message to combineCandidates[0].authors saying why, and keep "
                    + "the lane open until they answer." + validation);
        }
        // A snapshot that could not be merged waits for another agent to repair it; its author cannot (ADR-0098 step 5 item 54).
        List<Map<String, Object>> repairs = repairCandidates(store, participantId, location);
        if (!repairs.isEmpty()) {
            claimRequired.put("repairCandidates", repairs);
        }
        // A combine keeps the instruction: the two copies of main cannot meet until it lands, and the repair stays listed.
        if (!repairs.isEmpty() && !claimRequired.containsKey("combineCandidates")) {
            claimRequired.put("instruction", "This session holds no lane, and a published snapshot could not be merged onto main and waits for a repair: take the repair lane first by calling "
                    + "ensure_session with repairCandidates[0].ensureSession. Your worktree then holds main with the snapshot's change and Git's conflict markers; resolve them, "
                    + "keep both intents, and finish the lane as usual." + validation);
        }
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, claimRequired);
    }

    /** The repairs this session may take, as its next action offers them; none when this computer is unknown. */
    private static List<Map<String, Object>> repairCandidates(PredictionEventStore store, String participantId, ProjectLocation location) {
        try {
            return org.synesis.workspace.application.collaboration.RepairLaneOffer.offeredTo(store,
                    org.synesis.workspace.application.collaboration.CombineLaneJoin.sessionsOfAgent(location.root(), participantId), location.loadOrCreateIdentity().nodeId()).stream()
                    .map(org.synesis.workspace.application.collaboration.RepairLaneOffer::describe).toList();
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** The combines this session may take, as its next action offers them; none when this computer is unknown. */
    private static List<Map<String, Object>> combineCandidates(PredictionEventStore store, String participantId, ProjectLocation location) {
        try {
            String node = location.loadOrCreateIdentity().nodeId();
            return org.synesis.workspace.application.collaboration.CombineLaneJoin.offeredTo(store,
                    org.synesis.workspace.application.collaboration.CombineLaneJoin.sessionsOfAgent(location.root(), participantId), node).stream()
                    .map(combine -> org.synesis.workspace.application.collaboration.CombineLaneJoin.describe(store, combine)).toList();
        } catch (Exception unreadable) {
            return List.of();
        }
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

        // The caller's own computer merges its lanes (ADR-0098 step 5); another computer's conflict is not its block.
        boolean conflict = taskCompletion.activeIntegrationAttempt(actor.nodeId()).map(attempt -> INTEGRATION_CONFLICT_STATUS.equals(attempt.status())).orElse(false);
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
