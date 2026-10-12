package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpCollaborationProjection.collaborationStatusMap;
import static org.synesis.mcp.application.McpCollaborationProjection.requestMap;
import static org.synesis.mcp.application.McpCoordinationPayload.normalizeStrictCoordination;
import static org.synesis.mcp.application.McpCoordinationPayload.normalizeStrictResponse;
import static org.synesis.mcp.application.McpCoordinationPayload.parseReviewPolicy;
import static org.synesis.mcp.application.McpJsonRpcResponses.object;
import static org.synesis.mcp.application.McpJsonRpcResponses.policyDenied;
import static org.synesis.mcp.application.McpToolArguments.optionalBooleanArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalLongArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalStringArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalUuidArgument;
import static org.synesis.mcp.application.McpToolArguments.requiredStringArgument;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.synesis.coordination.application.ContinuationResult;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService.CancelTaskRequest;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.completion.CompletionOutcome;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.ContractOwnerService;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * Runs the coordination-facing tools: {@code get_next_action}, {@code request_coordination},
 * {@code respond_coordination}, {@code publish_capability_implementation}, {@code finish_lane} and
 * {@code cancel_lane}.
 *
 * <p>Each tool parses its wire arguments and delegates to the application service that owns the
 * durable decision. They share no connection state with the handler: the project root arrives as an
 * argument at each call, and the exact provider and connection binding is re-resolved by the
 * services on every request.</p>
 */
final class McpCoordinationTools {

    /**
     * Service that projects the durable next action for the bound participant.
     */
    private final AgentNextActionService nextActionService;
    /**
     * Service that creates capability requests.
     */
    private final CapabilityRequestService capabilityRequestService;
    /**
     * Service that processes capability-owner responses.
     */
    private final ContractOwnerService contractOwnerService;
    /**
     * Service that publishes capability implementations.
     */
    private final ImplementationPublicationService publicationService;
    /**
     * Service that validates published implementations.
     */
    private final ImplementationValidationService validationService;
    /**
     * Service that validates immutable review snapshots.
     */
    private final ReviewValidationService reviewValidationService;
    /**
     * Service that completes a participant's current task.
     */
    private final AgentTaskCompletionService taskCompletionService;
    /**
     * Service that cancels a participant's current task.
     */
    private final AgentTaskCancellationService taskCancellationService;
    /**
     * Service that reads and mutates durable collaboration state.
     */
    private final WorkspaceCollaborationService collaborationService;
    /**
     * Resolves the exact provider and connection binding used for authority checks.
     */
    private final SessionAuthorityResolver authorityResolver;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;
    /**
     * Runs recovery reconciliation before an ordinary inbox poll.
     */
    private final McpRecoveryReconciliation reconciliation;

    /**
     * Creates the coordination tools for one connection.
     *
     * @param nextActionService         service that projects the durable next action
     * @param capabilityRequestService  service that creates capability requests
     * @param contractOwnerService   service through which a lane publishes its contracts and answers change requests
     * @param publicationService        service that publishes capability implementations
     * @param validationService         service that validates published implementations
     * @param reviewValidationService   service that validates immutable review snapshots
     * @param taskCompletionService     service that completes a participant's current task
     * @param taskCancellationService   service that cancels a participant's current task
     * @param collaborationService      service that reads and mutates durable collaboration state
     * @param authorityResolver         resolver for the exact provider and connection binding
     * @param reconciliation            recovery reconciliation run before an inbox poll
     * @param provider                  stable provider ID associated with this connection's binding
     * @param connectionInstanceId      connection incarnation used to prevent cross-connection authority reuse
     */
    McpCoordinationTools(AgentNextActionService nextActionService,
            CapabilityRequestService capabilityRequestService,
            ContractOwnerService contractOwnerService,
            ImplementationPublicationService publicationService,
            ImplementationValidationService validationService,
            ReviewValidationService reviewValidationService,
            AgentTaskCompletionService taskCompletionService,
            AgentTaskCancellationService taskCancellationService,
            WorkspaceCollaborationService collaborationService,
            SessionAuthorityResolver authorityResolver,
            McpRecoveryReconciliation reconciliation,
            String provider,
            String connectionInstanceId) {
        this.nextActionService = nextActionService;
        this.capabilityRequestService = capabilityRequestService;
        this.contractOwnerService = contractOwnerService;
        this.publicationService = publicationService;
        this.validationService = validationService;
        this.reviewValidationService = reviewValidationService;
        this.taskCompletionService = taskCompletionService;
        this.taskCancellationService = taskCancellationService;
        this.collaborationService = collaborationService;
        this.authorityResolver = authorityResolver;
        this.reconciliation = reconciliation;
        this.provider = provider;
        this.connectionInstanceId = connectionInstanceId;
    }

    /**
     * Evaluates an integration check, or projects the durable next action after a recovery
     * reconciliation pass.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse getNextAction(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        boolean completionRequested;
        try {
            completionRequested = optionalBooleanArgument(arguments, "completionRequested");
        } catch (IllegalArgumentException invalidCompletionRequest) {
            agentResponse = McpRefusalGuidance.malformed(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "INVALID_COMPLETION_REQUEST", invalidCompletionRequest);
            return agentResponse;
        }
        if (arguments != null && arguments.get("integrationCheck") instanceof Map<?, ?> check) {
            agentResponse = McpIntegrationCheckEvidence.check(check);
        } else {
            reconciliation.reconcileRecoveryBeforeInboxPoll(activeProjectRoot);
            NextActionRequest nextReq = new NextActionRequest(activeProjectRoot, provider, connectionInstanceId, completionRequested);
            agentResponse = nextActionService.getNextAction(nextReq);
        }
        return agentResponse;
    }

    /**
     * Dispatches one strict coordination request after normalizing its payload.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse requestCoordination(Map<String, Object> arguments, Path activeProjectRoot) {
        if (arguments == null || !arguments.containsKey("kind")) {
            return policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD");
        }
        McpCoordinationRequest request;
        try {
            request = normalizeStrictCoordination(arguments);
        } catch (McpCoordinationPayload.RefusedPayloadException failure) {
            return refusedPayload(failure);
        } catch (IllegalArgumentException failure) {
            return policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "error", failure.getMessage());
        }
        try {
            return switch (request) {
                case McpCoordinationRequest.PublishContract publish ->
                        contractOwnerService.publish(new ContractOwnerService.PublishRequest(activeProjectRoot, provider, connectionInstanceId, publish.paths(), publish.terms(), publish.answers()));
                case McpCoordinationRequest.BindContract bind ->
                        capabilityRequestService.bind(new CapabilityRequestService.BindRequest(activeProjectRoot, provider, connectionInstanceId, bind.capability(), bind.ownerPath(), bind.revision()));
                case McpCoordinationRequest.RequestChange change ->
                        capabilityRequestService.requestChange(new CapabilityRequestService.ChangeRequest(activeProjectRoot, provider, connectionInstanceId, change.ownerPath(), change.need()));
                case McpCoordinationRequest.Status ignored -> {
                    // Every agent may read every contract and every change request (ADR-0092).
                    var contracts = new org.synesis.workspace.application.ProjectApplicationService().locate(activeProjectRoot).openCoordinationStore().contractProjection();
                    Map<String, Object> status = new LinkedHashMap<>(collaborationStatusMap(collaborationService.status(activeProjectRoot)));
                    status.put("contracts", contracts.contracts().stream().map(org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper::contractMap).toList());
                    status.put("changeRequests", contracts.changeRequests().stream().map(org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper::changeRequestMap).toList());
                    yield completed(status);
                }
                case McpCoordinationRequest.OpenRequest open -> {
                    var opened = collaborationService.request(activeProjectRoot, provider, connectionInstanceId, open.intentId(), open.kind(), open.proposal(), open.snapshotId());
                    yield completed(Map.of("request", requestMap(opened)));
                }
                case McpCoordinationRequest.Handoff handoff -> {
                    var offered = collaborationService.handoff(activeProjectRoot, provider, connectionInstanceId, handoff.intentId(), handoff.target(), handoff.proposal());
                    yield completed(Map.of("request", requestMap(offered)));
                }
                case McpCoordinationRequest.CreateWorkGroup create -> {
                    collaborationService.createWorkGroup(activeProjectRoot, create.workGroupId(), create.goal(), create.acceptance(), parseReviewPolicy(create.reviewPolicy()));
                    yield completed(object("workGroupId", create.workGroupId().toString(), "status", "ACTIVE"));
                }
                case McpCoordinationRequest.ConsumeLaneGrant consume -> {
                    var callerBinding = authorityResolver.resolveReview(new ProjectApplicationService().locate(activeProjectRoot), provider, connectionInstanceId);
                    String callerParticipant = WorkspaceCollaborationService.participantHandle(callerBinding.sessionId());
                    collaborationService.consumeLaneGrant(activeProjectRoot, consume.grantId(), callerParticipant, consume.intentId(), consume.claimEpoch());
                    yield completed(object("grantId", consume.grantId().toString(), "status", "CONSUMED"));
                }
                case McpCoordinationRequest.ContinueLane continuation -> {
                    ContinuationResult outcome = collaborationService.continueLane(activeProjectRoot,
                            provider,
                            connectionInstanceId,
                            continuation.grantId(),
                            continuation.sourceIntentId(),
                            continuation.claimEpoch());
                    Map<String, Object> continuationResult = new LinkedHashMap<>();
                    if (continuation.grantId() != null) {
                        continuationResult.put("grantId", continuation.grantId().toString());
                    }
                    continuationResult.put("sourceIntentId", continuation.sourceIntentId().toString());
                    continuationResult.put("claimEpoch", continuation.claimEpoch());
                    continuationResult.put("status", "COMMITTED".equals(outcome.status()) ? "CONTINUED" : outcome.status());
                    continuationResult.put("successorIntentId", outcome.successorIntentId().toString());
                    continuationResult.put("successorParticipant", outcome.successorParticipant());
                    continuationResult.put("callerOwnsSuccessor", outcome.callerOwnsSuccessor());
                    yield completed(continuationResult);
                }
            };
        } catch (Exception failure) {
            return policyDenied(AgentNextAction.RETRY, "error", String.valueOf(failure.getMessage()));
        }
    }

    private static AgentResponse completed(Object result) {
        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    /**
     * Dispatches one strict coordination response after validating its kind and payload.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse respondCoordination(Map<String, Object> arguments, Path activeProjectRoot) {
        if (arguments == null || !(arguments.get("kind") instanceof String)) {
            return policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD");
        }
        try {
            return switch (normalizeStrictResponse(arguments)) {
                case McpCoordinationResponse.ImplementationValidation validation -> validationService.validateImplementation(new ImplementationValidationService.ValidateRequest(activeProjectRoot,
                        provider,
                        connectionInstanceId,
                        validation.capabilityRequestHandle(),
                        validation.result(),
                        validation.reason(),
                        validation.implementationRevision(),
                        validation.failedAcceptanceTests()));
                case McpCoordinationResponse.ReviewValidation review -> reviewValidationService.validate(new ReviewValidationService.ValidateRequest(activeProjectRoot,
                        provider,
                        connectionInstanceId,
                        review.grantId(),
                        review.snapshotId(),
                        review.intentId(),
                        review.claimEpoch(),
                        review.result(),
                        review.reason()));
                case McpCoordinationResponse.AnswerChange answer ->
                        contractOwnerService.answerChange(new ContractOwnerService.AnswerRequest(activeProjectRoot, provider, connectionInstanceId, answer.changeRequestId(), answer.accepted(), answer.reason()));
                case McpCoordinationResponse.RespondToRequest respond -> {
                    collaborationService.respond(activeProjectRoot, provider, connectionInstanceId, respond.requestId(), respond.status(), respond.proposal());
                    yield completed(object("coordinationRequest", respond.requestText(), "status", respond.status().name()));
                }
                case McpCoordinationResponse.AcknowledgeInbox acknowledge -> {
                    collaborationService.acknowledgeInbox(activeProjectRoot, provider, connectionInstanceId, acknowledge.itemId());
                    yield completed(object("inboxItemId", acknowledge.itemId().toString(), "acknowledged", true, "resolved", false));
                }
                case McpCoordinationResponse.ResolveInbox resolve -> {
                    collaborationService.resolveInbox(activeProjectRoot, provider, connectionInstanceId, resolve.itemId(), resolve.resolution(), resolve.proposal());
                    yield completed(object("inboxItemId", resolve.itemId().toString(), "acknowledged", true, "resolved", true));
                }
            };
        } catch (McpCoordinationPayload.RefusedPayloadException failure) {
            return refusedPayload(failure);
        } catch (Exception failure) {
            return policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "error", String.valueOf(failure.getMessage()));
        }
    }

    private static AgentResponse refusedPayload(McpCoordinationPayload.RefusedPayloadException failure) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                failure.detail() == null ? object("error", failure.getMessage(), failure.acceptedName(), failure.accepted())
                        : object("error", failure.getMessage(), "detail", failure.detail(), failure.acceptedName(), failure.accepted()));
    }

    /**
     * Publishes a capability implementation once its evidence arguments are complete.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse publishCapabilityImplementation(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        try {
            String reqHandle = optionalStringArgument(arguments, "capabilityRequestHandle");
            String summary = optionalStringArgument(arguments, "summary");
            Long contractRevision = optionalLongArgument(arguments, "contractRevision", 1);
            UUID intentId = optionalUuidArgument(arguments, "intentId");
            Long claimEpoch = optionalLongArgument(arguments, "claimEpoch", 1);
            String expectedTreeHash = optionalStringArgument(arguments, "expectedTreeHash");

            if (reqHandle == null || reqHandle.isBlank()) {
                agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
            } else if (contractRevision == null || intentId == null || claimEpoch == null || expectedTreeHash == null || expectedTreeHash.isBlank()) {
                agentResponse = policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "CAPABILITY_PUBLICATION_EVIDENCE_REQUIRED");
            } else {
                ImplementationPublicationService.PublishRequest pubReq = new ImplementationPublicationService.PublishRequest(activeProjectRoot,
                        provider,
                        connectionInstanceId,
                        reqHandle,
                        summary,
                        contractRevision,
                        intentId,
                        claimEpoch,
                        expectedTreeHash);
                agentResponse = publicationService.publishImplementation(pubReq);
            }
        } catch (IllegalArgumentException invalidPublication) {
            agentResponse = McpRefusalGuidance.malformed(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "CAPABILITY_PUBLICATION_EVIDENCE_INVALID", invalidPublication);
        }
        return agentResponse;
    }

    /**
     * Completes the bound participant's current task with the caller's expected lane evidence.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse finishLane(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        try {
            String summary = optionalStringArgument(arguments, "summary");
            CompletionOutcome outcome = arguments != null && arguments.containsKey("outcome") ? CompletionOutcome.fromWire(requiredStringArgument(arguments)) : CompletionOutcome.SNAPSHOT;
            UUID expectedIntentId = optionalUuidArgument(arguments, "intentId");
            UUID expectedWorkGroupId = optionalUuidArgument(arguments, "workGroupId");
            Long expectedClaimEpoch = optionalLongArgument(arguments, "claimEpoch", 1);
            Long expectedWorkGroupVersion = optionalLongArgument(arguments, "workGroupVersion", 1);
            Long expectedRevision = optionalLongArgument(arguments, "expectedRevision", 0);
            String expectedParticipant = optionalStringArgument(arguments, "participant");
            boolean terminalSession = arguments != null && Boolean.TRUE.equals(arguments.get("terminalSession"));
            CompleteTaskRequest completeReq = new CompleteTaskRequest(activeProjectRoot,
                    provider,
                    connectionInstanceId,
                    summary,
                    outcome,
                    expectedIntentId,
                    expectedWorkGroupId,
                    expectedClaimEpoch,
                    expectedWorkGroupVersion,
                    expectedRevision,
                    expectedParticipant,
                    terminalSession);
            agentResponse = taskCompletionService.completeTask(completeReq);
        } catch (IllegalArgumentException invalidFinish) {
            agentResponse = McpRefusalGuidance.malformed(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "INVALID_FINISH_LANE_ARGUMENTS", invalidFinish);
        }
        return agentResponse;
    }

    /**
     * Cancels the bound participant's current task.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse cancelLane(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        String reason;
        try {
            reason = optionalStringArgument(arguments, "reason");
        } catch (IllegalArgumentException invalidReason) {
            return McpRefusalGuidance.malformed(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "INVALID_CANCEL_LANE_ARGUMENTS", invalidReason);
        }
        CancelTaskRequest cancelReq = new CancelTaskRequest(activeProjectRoot, provider, connectionInstanceId, reason);
        agentResponse = taskCancellationService.cancelTask(cancelReq);
        return agentResponse;
    }
}
