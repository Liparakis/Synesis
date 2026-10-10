package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpCollaborationProjection.collaborationStatusMap;
import static org.synesis.mcp.application.McpCollaborationProjection.contractMap;
import static org.synesis.mcp.application.McpCollaborationProjection.requestMap;
import static org.synesis.mcp.application.McpCoordinationPayload.normalizeStrictCoordination;
import static org.synesis.mcp.application.McpCoordinationPayload.normalizeStrictResponse;
import static org.synesis.mcp.application.McpCoordinationPayload.parseContract;
import static org.synesis.mcp.application.McpCoordinationPayload.parseReviewPolicy;
import static org.synesis.mcp.application.McpIntegrationCheckEvidence.testsPassed;
import static org.synesis.mcp.application.McpToolArguments.optionalBooleanArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalLongArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalStringArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalUuidArgument;
import static org.synesis.mcp.application.McpToolArguments.requiredStringArgument;
import static org.synesis.mcp.application.McpToolArguments.strings;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;

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
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.integration.IntegrationCompatibilityService;
import org.synesis.workspace.application.integration.WorkspaceIntegrationReadinessService;
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
    private final CapabilityResponseService capabilityResponseService;
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
     * @param capabilityResponseService service that processes capability-owner responses
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
            CapabilityResponseService capabilityResponseService,
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
        this.capabilityResponseService = capabilityResponseService;
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
            agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "INVALID_COMPLETION_REQUEST"));
            return agentResponse;
        }
        if (arguments != null && arguments.get("integrationCheck") instanceof Map<?, ?> check) {
            try {
                String head = String.valueOf(check.get("controlHead"));
                String base = String.valueOf(check.get("base"));
                List<String> paths = strings(check.get("paths"));
                List<String> claims = strings(check.get("claims"));
                var snapshot = new IntegrationCompatibilityService.SnapshotInput("mcp-candidate", base, paths, claims.stream().map(ResourceSelector::pathExact).toList(), List.of(), List.of());
                var result = new WorkspaceIntegrationReadinessService().check(new IntegrationCompatibilityService.CheckRequest(head, List.of(snapshot), List.of(), testsPassed(check)));
                List<String> failureCodes = result.failures().stream().map(Enum::name).toList();
                agentResponse = new AgentResponse(result.accepted() ? AgentStatus.COMPLETED : AgentStatus.BLOCKED,
                        result.accepted() ? null : AgentReason.INTEGRATION_CONFLICT,
                        result.accepted() ? null : AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("accepted", result.accepted(), "failures", failureCodes, "actions", result.actions()));
            } catch (Exception failure) {
                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of("error", failure.getMessage()));
            }
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
     * <p>A request whose normalized arguments name no collaboration operation is an internal error,
     * not a policy denial.</p>
     *
     * @param arguments tool arguments, possibly {@code null}; the normalized form replaces them locally
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     * @throws IllegalStateException when the strict request produced no operation
     */
    AgentResponse requestCoordination(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        if (arguments == null || !arguments.containsKey("kind")) {
            agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
            return agentResponse;
        }
        try {
            arguments = normalizeStrictCoordination(arguments);
        } catch (IllegalArgumentException failure) {
            agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
            return agentResponse;
        }
        String collaborationOperation = (String) arguments.get("collaborationOperation");
        if (collaborationOperation != null) {
            try {
                if ("capability_request".equals(collaborationOperation)) {
                    CapabilityContract contract = parseContract(arguments.get("contract"));
                    if (contract == null) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                                Map.of("error", "CAPABILITY_CONTRACT_INVALID"));
                        return agentResponse;
                    }
                    agentResponse = capabilityRequestService.describeRequiredCapability(new CapabilityRequestService.DescribeCapabilityRequest(activeProjectRoot,
                            provider,
                            connectionInstanceId,
                            String.valueOf(arguments.get("capability")),
                            contract,
                            arguments.get("capabilityRequestHandle") instanceof String value ? value : null,
                            arguments.get("revisionResponse") instanceof String value ? value : null,
                            arguments.get("ownerAuthorityLineageId") instanceof String value ? UUID.fromString(value) : null,
                            arguments.get("contractRevision") instanceof Number value ? value.longValue() : -1));
                    return agentResponse;
                }
                var result = switch (collaborationOperation) {
                    case "status" -> {
                        var snapshot = collaborationService.contractStatus(activeProjectRoot);
                        Map<String, Object> status = new LinkedHashMap<>(collaborationStatusMap(collaborationService.status(activeProjectRoot)));
                        status.put("contracts", snapshot.contracts().stream().map(McpCollaborationProjection::contractMap).toList());
                        status.put("dependencies", snapshot.dependencies().stream().map(McpCollaborationProjection::dependencyMap).toList());
                        yield status;
                    }
                    case "publish" -> {
                        UUID contractId = UUID.fromString(String.valueOf(arguments.get("collaborationContractId")));
                        String body = String.valueOf(arguments.getOrDefault("collaborationBody", ""));
                        List<String> selectors = arguments.get("collaborationSelectors") instanceof List<?> values ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
                        var contract = collaborationService.publishContract(activeProjectRoot, provider, connectionInstanceId, contractId, body, selectors);
                        yield Map.of("contract", contractMap(contract));
                    }
                    case "request_coordination" -> {
                        UUID intentId = UUID.fromString(String.valueOf(arguments.get("collaborationIntentId")));
                        CoordinationRequest.Kind kind = CoordinationRequest.Kind.valueOf(String.valueOf(arguments.getOrDefault("collaborationRequestKind", "CONTRACT")).toUpperCase(Locale.ROOT));
                        String proposal = String.valueOf(arguments.getOrDefault("collaborationProposal", ""));
                        var request = collaborationService.request(activeProjectRoot,
                                provider,
                                connectionInstanceId,
                                intentId,
                                kind,
                                proposal,
                                arguments.get("snapshotId") instanceof String snapshotId ? snapshotId : null);
                        yield Map.of("request", requestMap(request));
                    }
                    case "handoff" -> {
                        UUID intentId = UUID.fromString(String.valueOf(arguments.get("collaborationIntentId")));
                        String target = String.valueOf(arguments.get("collaborationTarget"));
                        String proposal = String.valueOf(arguments.getOrDefault("collaborationProposal", ""));
                        var request = collaborationService.handoff(activeProjectRoot, provider, connectionInstanceId, intentId, target, proposal);
                        yield Map.of("request", requestMap(request));
                    }
                    case "work_group_create" -> {
                        UUID groupId = UUID.fromString(String.valueOf(arguments.get("workGroupId")));
                        collaborationService.createWorkGroup(activeProjectRoot,
                                groupId,
                                String.valueOf(arguments.getOrDefault("collaborationGoal", "")),
                                String.valueOf(arguments.getOrDefault("collaborationAcceptance", "")),
                                parseReviewPolicy(arguments.get("reviewPolicy")));
                        yield Map.of("workGroupId", groupId.toString(), "status", "ACTIVE");
                    }
                    case "lane_grant_consume" -> {
                        UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                        var callerBinding = authorityResolver.resolveReview(new ProjectApplicationService().locate(activeProjectRoot), provider, connectionInstanceId);
                        String callerParticipant = WorkspaceCollaborationService.participantHandle(callerBinding.sessionId());
                        collaborationService.consumeLaneGrant(activeProjectRoot,
                                grantId,
                                callerParticipant,
                                UUID.fromString(String.valueOf(arguments.get("intentId"))),
                                ((Number) arguments.getOrDefault("claimEpoch", 1)).longValue());
                        yield Map.of("grantId", grantId.toString(), "status", "CONSUMED");
                    }
                    case "continuation" -> {
                        Object rawGrantId = arguments.get("grantId");
                        UUID grantId = rawGrantId == null || String.valueOf(rawGrantId).isBlank() ? null : UUID.fromString(String.valueOf(rawGrantId));
                        UUID sourceIntentId = UUID.fromString(String.valueOf(arguments.get("collaborationSourceIntentId")));
                        long epoch = ((Number) arguments.getOrDefault("claimEpoch", 1)).longValue();
                        ContinuationResult continuationOutcome = collaborationService.continueLane(activeProjectRoot, provider, connectionInstanceId, grantId, sourceIntentId, epoch);
                        Map<String, Object> continuationResult = new LinkedHashMap<>();
                        if (grantId != null) {
                            continuationResult.put("grantId", grantId.toString());
                        }
                        continuationResult.put("sourceIntentId", sourceIntentId.toString());
                        continuationResult.put("claimEpoch", epoch);
                        continuationResult.put("status", "COMMITTED".equals(continuationOutcome.status()) ? "CONTINUED" : continuationOutcome.status());
                        continuationResult.put("successorIntentId", continuationOutcome.successorIntentId().toString());
                        continuationResult.put("successorParticipant", continuationOutcome.successorParticipant());
                        continuationResult.put("callerOwnsSuccessor", continuationOutcome.callerOwnsSuccessor());
                        yield continuationResult;
                    }
                    default -> throw new IllegalArgumentException("unknown collaboration operation");
                };
                agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null, result);
                return agentResponse;
            } catch (Exception failure) {
                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of("error", failure.getMessage()));
                return agentResponse;
            }
        }
        throw new IllegalStateException("strict coordination request did not produce an operation");
    }

    /**
     * Dispatches one strict coordination response after validating its kind and payload.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse respondCoordination(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        if (arguments != null && arguments.get("kind") instanceof String) {
            try {
                Map<String, Object> strict = normalizeStrictResponse(arguments);
                String kind = String.valueOf(strict.get("kind"));
                @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) strict.get("payload");
                switch (kind) {
                    case "implementation_validation" -> {
                        List<String> failedTests = payload.get("failedAcceptanceTests") instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
                        agentResponse = validationService.validateImplementation(new ImplementationValidationService.ValidateRequest(activeProjectRoot,
                                provider,
                                connectionInstanceId,
                                String.valueOf(payload.get("capabilityRequestHandle")),
                                String.valueOf(payload.get("result")),
                                payload.get("reason") == null ? null : String.valueOf(payload.get("reason")),
                                ((Number) payload.get("implementationRevision")).intValue(),
                                failedTests));
                    }
                    case "review_validation" -> agentResponse = reviewValidationService.validate(new ReviewValidationService.ValidateRequest(activeProjectRoot,
                            provider,
                            connectionInstanceId,
                            UUID.fromString(String.valueOf(payload.get("grantId"))),
                            String.valueOf(payload.get("snapshotId")),
                            UUID.fromString(String.valueOf(payload.get("intentId"))),
                            ((Number) payload.get("claimEpoch")).longValue(),
                            String.valueOf(payload.get("result")),
                            payload.get("reason") == null ? null : String.valueOf(payload.get("reason"))));
                    case "capability_response" -> {
                        CapabilityContract revision = parseContract(payload.get("revision"));
                        if ("revise".equals(payload.get("response")) && revision == null) {
                            agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                                    Map.of("error", "CAPABILITY_REVISION_INVALID"));
                            break;
                        }
                        agentResponse = capabilityResponseService.respondToOwnerRequest(new CapabilityResponseService.OwnerResponseRequest(activeProjectRoot,
                                provider,
                                connectionInstanceId,
                                String.valueOf(payload.get("capabilityRequestHandle")),
                                String.valueOf(payload.get("response")),
                                revision,
                                payload.get("reason") == null ? null : String.valueOf(payload.get("reason")),
                                ((Number) payload.get("contractRevision")).longValue()));
                    }
                    case "coordination_response" -> {
                        CoordinationRequest.Status status = CoordinationRequest.Status.valueOf(String.valueOf(payload.get("coordinationStatus")));
                        collaborationService.respond(activeProjectRoot,
                                provider,
                                connectionInstanceId,
                                UUID.fromString(String.valueOf(payload.get("coordinationRequest"))),
                                status,
                                String.valueOf(payload.getOrDefault("proposal", "")));
                        agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("coordinationRequest", payload.get("coordinationRequest"), "status", status.name()));
                    }
                    case "inbox_acknowledge", "inbox_resolve" -> {
                        UUID itemId = UUID.fromString(String.valueOf(payload.get("inboxItemId")));
                        boolean resolve = "inbox_resolve".equals(kind);
                        if (resolve) {
                            CoordinationRequest.Status status = CoordinationRequest.Status.valueOf(String.valueOf(payload.get("resolution")));
                            collaborationService.resolveInbox(activeProjectRoot, provider, connectionInstanceId, itemId, status, String.valueOf(payload.getOrDefault("proposal", "")));
                        } else {
                            collaborationService.acknowledgeInbox(activeProjectRoot, provider, connectionInstanceId, itemId);
                        }
                        agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("inboxItemId", itemId.toString(), "acknowledged", true, "resolved", resolve));
                    }
                    default -> throw new IllegalArgumentException("unknown coordination response kind");
                }
                return agentResponse;
            } catch (Exception failure) {
                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
                return agentResponse;
            }
        }
        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
        return agentResponse;
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
                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("reason", "CAPABILITY_PUBLICATION_EVIDENCE_REQUIRED"));
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
            agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.POLICY_DENIED,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of("reason", "CAPABILITY_PUBLICATION_EVIDENCE_INVALID"));
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
            agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "INVALID_FINISH_LANE_ARGUMENTS"));
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
        String reason = arguments != null ? (String) arguments.get("reason") : null;
        CancelTaskRequest cancelReq = new CancelTaskRequest(activeProjectRoot, provider, connectionInstanceId, reason);
        agentResponse = taskCancellationService.cancelTask(cancelReq);
        return agentResponse;
    }
}
