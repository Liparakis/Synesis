package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpCollaborationProjection.collaborationStatusMap;
import static org.synesis.mcp.application.McpCollaborationProjection.requestMap;
import static org.synesis.mcp.application.McpCoordinationPayload.normalizeStrictCoordination;
import static org.synesis.mcp.application.McpCoordinationPayload.normalizeStrictResponse;
import static org.synesis.mcp.application.McpCoordinationPayload.parseReviewPolicy;
import static org.synesis.mcp.application.McpJsonRpcResponses.object;
import static org.synesis.mcp.application.McpJsonRpcResponses.policyDenied;
import static org.synesis.mcp.application.McpToolArguments.nullableStringList;
import static org.synesis.mcp.application.McpToolArguments.optionalBooleanArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalLongArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalStringArgument;
import static org.synesis.mcp.application.McpToolArguments.optionalUuidArgument;
import static org.synesis.mcp.application.McpToolArguments.requiredStringArgument;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.agent.workflow.ProjectChangeWait;
import org.synesis.workspace.application.agent.run.LaneWake;
import org.synesis.workspace.application.capability.ContractConsumerService;
import org.synesis.workspace.application.capability.ContractOwnerService;
import org.synesis.workspace.application.conversation.ConversationService;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
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
     * Service through which a lane binds to a contract and files change requests.
     */
    private final ContractConsumerService contractConsumerService;
    /**
     * Service through which a lane publishes its contracts and answers change requests.
     */
    private final ContractOwnerService contractOwnerService;
    /**
     * Service through which lanes send and are handed messages.
     */
    private final ConversationService conversationService;
    /**
     * How far this connection has been handed the messages of the lane it holds. The next call
     * from this connection is what shows the answer carrying a batch arrived, so the cursor is
     * kept here and not in the log: a connection that ends first leaves the batch to whoever
     * continues the lane.
     */
    private ConversationService.Cursor messagesHanded = ConversationService.Cursor.START;
    /**
     * How long a caller told to wait is held before it is answered.
     */
    private Duration waitHold = Duration.ZERO;
    /**
     * Whether this connection's identity came from the run controller, whose agents' Stop hooks read the wait notes.
     */
    private boolean notesWaitsForWake;
    /**
     * The wait the last next-action call answered, when it is to be held.
     */
    private PendingWait pendingWait;
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
     * @param contractConsumerService   service through which a lane binds to a contract and files change requests
     * @param contractOwnerService   service through which a lane publishes its contracts and answers change requests
     * @param conversationService       service through which lanes send and are handed messages
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
            ContractConsumerService contractConsumerService,
            ContractOwnerService contractOwnerService,
            ConversationService conversationService,
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
        this.contractConsumerService = contractConsumerService;
        this.contractOwnerService = contractOwnerService;
        this.conversationService = conversationService;
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
        AgentResponse answer = nextAction(arguments, activeProjectRoot);
        noteForWake(activeProjectRoot, answer);
        return answer;
    }

    private AgentResponse nextAction(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        boolean completionRequested;
        pendingWait = null;
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
            ProjectLocation location = null;
            long head = 0L;
            if (!waitHold.isZero()) {
                try {
                    location = new org.synesis.workspace.application.ProjectApplicationService().locate(activeProjectRoot);
                    head = ProjectChangeWait.head(location);
                } catch (Exception notLocated) {
                    location = null;
                }
            }
            AgentResponse answer = nextActionService.getNextAction(nextReq);
            agentResponse = withUnreadMessages(answer, activeProjectRoot);
            // Held only when told to wait with nothing to hand over: a message that was already
            // waiting is handed over now, not after the hold.
            pendingWait = location != null && answer.nextAction() == AgentNextAction.WAIT && agentResponse == answer ? new PendingWait(location, head) : null;
        }
        return agentResponse;
    }

    /**
     * Says whether this connection's next-action answers are noted for its agent's Stop hook (ADR-0099). Only a
     * connection whose identity the run controller gave its process has a hook that can find the note.
     *
     * @param notes whether to note them
     */
    void noteWaitsForWake(boolean notes) {
        this.notesWaitsForWake = notes;
    }

    /**
     * Notes, after any messages were handed over, that the caller was told WAIT and what its lane looked like then, or
     * that it was given something else. The note only lets the agent's Stop hook hold it and wake it; a note that cannot
     * be written means the agent is not held, and the run controller resumes it once it stops.
     */
    private void noteForWake(Path activeProjectRoot, AgentResponse answer) {
        if (!notesWaitsForWake) {
            return;
        }
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            var binding = answer.nextAction() == AgentNextAction.WAIT ? new ProviderSessionBindingService().find(location, provider, connectionInstanceId) : java.util.Optional.<ProviderSessionBindingService.Binding>empty();
            if (binding.isPresent()) {
                LaneWake.Requester requester = new LaneWake.Requester(location.loadOrCreateIdentity().nodeId(), binding.get().supervisorId(), binding.get().workerId());
                LaneWake.noteWait(location, connectionInstanceId, WorkspaceCollaborationService.participantHandle(binding.get().sessionId()), requester, waitEnds(answer));
            } else {
                LaneWake.clearWait(location, connectionInstanceId);
            }
        } catch (Exception unavailable) {
            System.err.println("SYNESIS_MCP_WAIT_NOTE_FAILURE=" + (unavailable.getMessage() == null ? unavailable.getClass().getSimpleName() : unavailable.getMessage()));
        }
    }

    /** The moment a WAIT answer names as the end of the wait: a recovery deadline or a requester's answer period. */
    private static java.util.OptionalLong waitEnds(AgentResponse answer) {
        if (!(answer.result() instanceof Map<?, ?> result)) {
            return java.util.OptionalLong.empty();
        }
        return java.util.stream.Stream.of("recoveryDeadlineEpochMillis", "requesterAnswerDeadlineEpochMillis").map(result::get)
                .filter(Number.class::isInstance).mapToLong(value -> ((Number) value).longValue()).min();
    }

    /**
     * Sets how long a caller told to wait is held before it is answered. Zero, the default,
     * answers at once; the stdio server sets it for real provider sessions.
     *
     * @param hold longest time one waiting call is held
     */
    void holdWaitsFor(Duration hold) {
        this.waitHold = hold == null || hold.isNegative() ? Duration.ZERO : hold;
    }

    /**
     * Holds the caller whose last next action was a wait with nothing to hand over, until the
     * project has something new for it or the hold passes.
     *
     * <p>The protocol handler calls this after it has answered such a call, and when it returns
     * true runs the whole call again, through every gate a call passes. Nothing is projected
     * here: the hold changes when an answer is given, never which.</p>
     *
     * @return {@code true} when the project changed and the call should be made again
     */
    boolean heldUntilTheProjectChanges() {
        PendingWait held = pendingWait;
        pendingWait = null;
        return held != null && ProjectChangeWait.awaitChange(held.location(), held.head(), waitHold);
    }

    /** Forgets the wait the call made again after a hold recorded: that call is answered at once. */
    void endHold() {
        pendingWait = null;
    }

    /** A wait that was just answered: where to watch, and the log head the answer was projected from. */
    private record PendingWait(ProjectLocation location, long head) {
    }

    /**
     * Puts the messages addressed to the caller's lane beside its next action (ADR-0093). The
     * action is the one projected without them: no message changes what a lane may do.
     */
    private AgentResponse withUnreadMessages(AgentResponse response, Path activeProjectRoot) {
        if (response.result() != null && !(response.result() instanceof Map<?, ?>)) {
            return response;
        }
        ConversationService.Unread unread = conversationService.unread(activeProjectRoot, provider, connectionInstanceId, messagesHanded);
        messagesHanded = unread.cursor();
        if (unread.messages().isEmpty()) {
            return response;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (response.result() instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        result.put("messages", unread.shown());
        return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
    }

    /**
     * Sends one message from the caller's lane to other lanes or to the operator.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse sendMessage(Map<String, Object> arguments, Path activeProjectRoot) {
        List<String> to;
        UUID conversation;
        String text;
        try {
            Object named = arguments == null ? null : arguments.get("to");
            // One name where a list is expected is a list of one: a model writes either.
            to = named == null ? List.of() : named instanceof String one ? List.of(one) : nullableStringList(named, "to");
            conversation = optionalUuidArgument(arguments, "conversation");
            text = optionalStringArgument(arguments, "text");
        } catch (IllegalArgumentException invalid) {
            return McpRefusalGuidance.malformed(AgentNextAction.RETRY, "reason", "INVALID_SEND_MESSAGE_ARGUMENTS", invalid);
        }
        return conversationService.send(new ConversationService.SendRequest(activeProjectRoot, provider, connectionInstanceId, to, conversation, text, messagesHanded));
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
            return refusedArgument(failure);
        }
        try {
            return switch (request) {
                case McpCoordinationRequest.PublishContract publish ->
                        contractOwnerService.publish(new ContractOwnerService.PublishRequest(activeProjectRoot, provider, connectionInstanceId, publish.paths(), publish.terms(), publish.answers()));
                case McpCoordinationRequest.BindContract bind ->
                        contractConsumerService.bind(new ContractConsumerService.BindRequest(activeProjectRoot, provider, connectionInstanceId, bind.capability(), bind.ownerPath(), bind.revision()));
                case McpCoordinationRequest.RequestChange change ->
                        contractConsumerService.requestChange(new ContractConsumerService.ChangeRequest(activeProjectRoot, provider, connectionInstanceId, change.ownerPath(), change.need()));
                case McpCoordinationRequest.Status ignored -> {
                    // Every agent may read every contract and every change request (ADR-0092).
                    var contracts = new org.synesis.workspace.application.ProjectApplicationService().locate(activeProjectRoot).openCoordinationStore().contractProjection();
                    Map<String, Object> status = new LinkedHashMap<>(collaborationStatusMap(collaborationService.status(activeProjectRoot)));
                    status.put("contracts", contracts.contracts().stream().map(org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper::contractMap).toList());
                    status.put("changeRequests", contracts.changeRequests().stream().map(org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper::changeRequestMap).toList());
                    // Only the caller's own conversations: Synesis serves a conversation to its members.
                    status.put("conversations", conversationService.history(activeProjectRoot, provider, connectionInstanceId));
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

    /**
     * Refuses a malformed request argument. A provider-facing alias that differs from its canonical field is the one
     * refusal an agent corrects itself, by copying the projected arguments (s16a-run1: a retyped participant id differed
     * by a character, the refusal named a person, and the agent put an operator request on the project that ended both
     * runs). The authority check is the same: nothing is accepted, and an alias never stands for another participant.
     */
    private static AgentResponse refusedArgument(IllegalArgumentException failure) {
        String message = String.valueOf(failure.getMessage());
        if (message.startsWith("COORDINATION_ALIAS_MISMATCH:")) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, object("error", message, "instruction",
                    "Two fields that name the same value differ, so nothing was done. Copy the projected arguments exactly, character for character, instead of retyping identifiers; "
                            + "no person is needed."));
        }
        return policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "error", message);
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
