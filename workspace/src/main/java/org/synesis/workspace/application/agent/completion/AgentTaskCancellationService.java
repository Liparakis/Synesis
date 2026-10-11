package org.synesis.workspace.application.agent.completion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.CallerAdmission;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * Application service handling ambient lane cancellation requests for workers
 * ({@code synesis.cancel_lane}).
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class AgentTaskCancellationService {

    private final ProjectApplicationService projectService;
    private final SessionAuthorityResolver authorityResolver;

    /**
     * Creates an agent task cancellation service.
     */
    public AgentTaskCancellationService() {
        this.projectService = new ProjectApplicationService();
        this.authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
    }

    /**
     * Cancels the lane of the admitted caller. A session that never announced a lane has none to
     * close, and a lane that is already cancelled is left as it is.
     */
    private static void closeLane(PredictionEventStore store, NodeIdentity identity, ProviderSessionBindingService.Binding binding) throws Exception {
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        var state = store.collaborationProjection().participantState(participant);
        if (state.isPresent() && state.get() != Participant.State.CANCELLED) {
            new WorkIntentService(store, identity).cancel(participant);
        }
    }

    private static boolean isTaskCancelled(PredictionEventStore store, UUID taskId) {
        for (var ev : store.events()) {
            if (ev.predictionId().equals(taskId) && ev.type() == PredictionEventType.TASK_CANCELLED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes ambient task cancellation authorization, event appends, dependency invalidation,
     * ownership release, and session finalization.
     *
     * @param request cancel task request
     * @return concise agent response payload
     */
    public AgentResponse cancelTask(CancelTaskRequest request) {
        Objects.requireNonNull(request, "request");

        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
        }

        CallerAdmission admission = CallerAdmission.admit(request.projectRoot(), projectService, authorityResolver, request.provider(), request.connectionInstanceId(), false);
        if (admission.refused()) {
            return admission.refusal();
        }
        ProjectLocation location = admission.location();
        ProviderSessionBindingService.Binding binding = admission.binding();
        NodeIdentity identity = admission.identity();

        String callerNodeId = identity.nodeId();
        String callerSupervisorId = binding.supervisorId();
        String callerWorkerId = binding.workerId();

        try {
            PredictionEventStore store = location.openCoordinationStore();

            UUID taskId = binding.taskId();

            // Check if task is already integrated or integrating
            if (store.taskCompletionProjection().taskState(taskId) == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATED) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.WAIT, Map.of("reason", "task_not_cancellable"));
            }

            // A repeated cancel succeeds. It still closes the lane, because an earlier attempt may
            // have recorded the task as cancelled and then failed before it got that far.
            if (isTaskCancelled(store, taskId)) {
                closeLane(store, identity, binding);
                Map<String, Object> result = Map.of("task", "cancelled");
                return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
            }

            List<CapabilityRequestRecord> ownRequests = store.capabilityRequestProjection().findAllForRequester(callerNodeId).stream()
                    .filter(req -> req.matchesRequester(callerNodeId, callerSupervisorId, callerWorkerId)).toList();
            // An owner that accepted a request is building against it, or has built and waits for
            // this lane's verdict. Ending the lane now would leave that verdict with nobody to give it.
            for (CapabilityRequestRecord req : ownRequests) {
                if (req.state().hasOpenObligation() && !req.state().isUnderNegotiation()) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.WAIT,
                            Map.of("reason", "capability_request_open", "capabilityRequestHandle", req.handle().value(), "state", req.state().value(),
                                    "instruction", "The owner accepted this capability request. Validate its implementation when get_next_action projects it, then cancel."));
                }
            }

            byte[] payload = request.reason().trim().getBytes(StandardCharsets.UTF_8);

            // 1. Append TASK_CANCELLATION_REQUESTED
            store.append(taskId, PredictionEventType.TASK_CANCELLATION_REQUESTED, callerNodeId, payload, identity);

            // 2. Append TASK_CANCELLED
            store.append(taskId, PredictionEventType.TASK_CANCELLED, callerNodeId, payload, identity);

            // 3. Cancel the capability requests of this caller that no owner has accepted yet.
            //    Requests that reached an outcome are history and stay as they are.
            for (CapabilityRequestRecord req : ownRequests) {
                if (req.state().isUnderNegotiation()) {
                    store.append(taskId, PredictionEventType.CAPABILITY_REQUEST_CANCELLED, callerNodeId, req.cancellation("Cancelled with the requester's lane").encode(), identity);
                }
            }

            // 4. Invalidate capability dependencies provided by this cancelled task
            store.append(taskId, PredictionEventType.DEPENDENCY_INVALIDATED, callerNodeId, ("invalidated:" + callerWorkerId).getBytes(StandardCharsets.UTF_8), identity);

            // 5. Release ownership claims if active
            var coordProj = store.coordinationProjection();
            for (var entry : coordProj.ownerships().entrySet()) {
                var claim = entry.getValue();
                if (callerNodeId.equals(claim.ownerNodeId()) && taskId.equals(claim.taskId())) {
                    org.synesis.coordination.domain.command.CoordinationCommand relCmd = org.synesis.coordination.domain.command.CoordinationCommand.create(UUID.randomUUID(),
                            store.projectId(),
                            claim.taskId(),
                            PredictionEventType.OWNERSHIP_RELEASED,
                            identity.nodeId(),
                            claim.encoded(),
                            identity);
                    store.append(taskId, PredictionEventType.OWNERSHIP_RELEASED, callerNodeId, relCmd.encoded(), identity);
                }
            }

            // 6. Finalize provider session with cancellation outcome
            store.append(taskId, PredictionEventType.SESSION_FINALIZED, callerNodeId, ("session_cancelled:" + binding.sessionId()).getBytes(StandardCharsets.UTF_8), identity);

            // 7. Close the lane and, when nothing else can add work to it, its work group. A
            //    failure here fails the call: reporting success with the lane still open left the
            //    agent with a lane it had been told was cancelled.
            closeLane(store, identity, binding);

            Map<String, Object> result = Map.of("task", "cancelled");
            return new AgentResponse(AgentStatus.COMPLETED, null, null, result);

        } catch (Exception ex) {
            return AgentFailures.internal(request.projectRoot(), "lane.cancel", ex, null);
        }
    }

    /**
     * Request payload for ambient task cancellation.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param reason               cancellation reason string (bounded 1-1000 characters)
     */
    public record CancelTaskRequest(Path projectRoot, String provider, String connectionInstanceId, String reason) {

        /**
         * Validates non-null core parameters.
         */
        public CancelTaskRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }
    }
}
