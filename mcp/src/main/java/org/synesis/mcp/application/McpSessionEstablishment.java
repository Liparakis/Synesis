package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpJsonRpcResponses.textResultResponse;
import static org.synesis.mcp.application.McpTaskArguments.claimsFieldSpecified;
import static org.synesis.mcp.application.McpTaskArguments.parseClaimSelectors;
import static org.synesis.mcp.application.McpTaskArguments.parseTaskIntent;
import static org.synesis.mcp.application.McpTaskArguments.taskField;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.AgentTaskIntent;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * Establishes this connection's provider session and announces the task claims carried by
 * {@code ensure_session}.
 *
 * <p>The session binding is owned here: {@code isSessionBound} is set at the single point where
 * the session service reports a verified admission and is read by the handler to decide when the
 * project root may still change and whether the recovery fence applies. The project root is not
 * owned here; it arrives as an argument and is never assigned.</p>
 */
final class McpSessionEstablishment {

    /**
     * Service that resolves and admits the provider's session.
     */
    private final AgentSessionService sessionService;
    /**
     * Service that completes a participant's current task.
     */
    private final AgentTaskCompletionService taskCompletionService;
    /**
     * Service that reads and mutates durable collaboration state.
     */
    private final WorkspaceCollaborationService collaborationService;
    /**
     * Resolves the exact provider and connection binding used for authority checks.
     */
    private final SessionAuthorityResolver authorityResolver;
    /**
     * Validates provider manual attestation before authority-increasing calls.
     */
    private final ProviderManualService manualService;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;
    /**
     * Whether the launcher authenticated this process as managed continuity.
     */
    private final boolean managedAdmission;
    /**
     * Renews this connection's lease once a session is verified.
     */
    private final McpSessionLease lease;
    /**
     * Re-arms command admission once a session is verified.
     */
    private final McpDurableCommand commands;
    /**
     * Runs the one-time admission reconciliation.
     */
    private final McpRecoveryReconciliation reconciliation;
    /**
     * Whether initialize has successfully bound this connection to a session.
     */
    private boolean isSessionBound;

    /**
     * Creates the session establishment path for one connection.
     *
     * @param sessionService        service that resolves and admits the provider's session
     * @param manualService         validates provider manual attestation before claims are announced
     * @param taskCompletionService service that completes a participant's current task
     * @param collaborationService  service that reads and mutates durable collaboration state
     * @param authorityResolver     resolver for the exact provider and connection binding
     * @param lease                 lease renewal run once the session is verified
     * @param commands              command path whose process anchor is re-armed once the session is verified
     * @param reconciliation        recovery reconciliation run once after the first verified admission
     * @param provider              stable provider ID associated with this connection's binding
     * @param connectionInstanceId  connection incarnation used to prevent cross-connection authority reuse
     * @param managedAdmission      whether the launcher authenticated this process as managed continuity
     */
    McpSessionEstablishment(AgentSessionService sessionService,
            ProviderManualService manualService,
            AgentTaskCompletionService taskCompletionService,
            WorkspaceCollaborationService collaborationService,
            SessionAuthorityResolver authorityResolver,
            McpSessionLease lease,
            McpDurableCommand commands,
            McpRecoveryReconciliation reconciliation,
            String provider,
            String connectionInstanceId,
            boolean managedAdmission) {
        this.sessionService = sessionService;
        this.manualService = manualService;
        this.taskCompletionService = taskCompletionService;
        this.collaborationService = collaborationService;
        this.authorityResolver = authorityResolver;
        this.lease = lease;
        this.commands = commands;
        this.reconciliation = reconciliation;
        this.provider = provider;
        this.connectionInstanceId = connectionInstanceId;
        this.managedAdmission = managedAdmission;
    }

    /**
     * Returns whether {@code ensure_session} has successfully bound this connection to a session.
     *
     * @return {@code true} once a verified admission has been observed
     */
    boolean isSessionBound() {
        return isSessionBound;
    }

    /**
     * Admits the provider session and announces the requested task claims.
     *
     * <p>Unlike the other tool methods this one returns the complete JSON-RPC frame: the
     * manual-attestation rejection for claims is a raw text result that has no {@link AgentResponse}
     * form, so it cannot be returned through the shared {@code agentResponse} path.</p>
     *
     * @param id        JSON-RPC request identifier
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the serialized {@code tools/call} response frame
     */
    String ensureSession(Object id, Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        AgentTaskIntent taskIntent;
        try {
            taskIntent = parseTaskIntent(arguments);
        } catch (IllegalArgumentException invalidTask) {
            String reason = "TASK_FIELDS_MUST_BE_NESTED".equals(invalidTask.getMessage()) ? "TASK_FIELDS_MUST_BE_NESTED" : "INVALID_TASK_INTENT";
            agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", reason));
            return textResultResponse(id, agentResponse.toJson());
        }
        boolean refresh = arguments != null && Boolean.TRUE.equals(arguments.get("refresh"));

        if (!parseClaimSelectors(arguments).isEmpty() && !manualService.attest(provider).valid()) {
            return textResultResponse(id, "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\",\"claims\":\"REJECTED\"}");
        }

        SessionResolutionRequest resolutionRequest = new SessionResolutionRequest(activeProjectRoot, provider, connectionInstanceId, taskIntent, refresh, managedAdmission);

        agentResponse = sessionService.ensureSession(resolutionRequest);
        if (agentResponse.status() == AgentStatus.READY) {
            isSessionBound = true;
            commands.refreshCommandProcessAnchorForVerifiedSession(activeProjectRoot);
            // The first verified ensure_session is activity too. Establish the lease
            // before any claim is announced so an abruptly deleted chat remains
            // recoverable even when it sends no follow-up MCP request.
            if (lease.renewLease(activeProjectRoot)) {
                reconciliation.reconcileAfterAdmission(activeProjectRoot);
            }
            if (arguments != null && Boolean.TRUE.equals(arguments.get("unwindCompletion"))) {
                agentResponse = taskCompletionService.unwindPrepared(new CompleteTaskRequest(activeProjectRoot, provider, connectionInstanceId, null));
            }
            String repairIntentText = taskField(arguments, "repairIntentId");
            String repairSnapshotId = taskField(arguments, "repairSnapshotId");
            if (repairIntentText != null || repairSnapshotId != null) {
                try {
                    if (repairIntentText == null || repairSnapshotId == null) {
                        throw new IllegalArgumentException("repair intent and snapshot are both required");
                    }
                    UUID repairIntentId = UUID.fromString(repairIntentText);
                    var joined = collaborationService.joinRepair(activeProjectRoot, provider, connectionInstanceId, repairIntentId, repairSnapshotId);
                    if (!joined.acquired()) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("conflicts", joined.conflicts()));
                    } else {
                        agentResponse = new AgentResponse(AgentStatus.READY,
                                null,
                                AgentNextAction.RETRY,
                                Map.of("repairJoined", true, "intentId", joined.intent().intentId().toString(), "claimEpoch", joined.intent().version()));
                    }
                } catch (Exception repairFailure) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                            AgentReason.POLICY_DENIED,
                            AgentNextAction.REQUEST_HUMAN_HELP,
                            Map.of("reason", repairFailure.getMessage() == null ? "REPAIR_JOIN_FAILED" : repairFailure.getMessage()));
                }
            }
            List<ResourceSelector> selectors = parseClaimSelectors(arguments);
            boolean claimsSpecified = claimsFieldSpecified(arguments);
            WorkIntent.Role requestedRole = taskIntent == null ? WorkIntent.Role.PRODUCER : taskIntent.role();
            boolean recoveryAdmission =
                    !refresh && collaborationService.recoveryContinuationAdmission(activeProjectRoot, connectionInstanceId, requestedRole, selectors, taskIntent == null ? null : taskIntent.workGroupId());
            if (refresh && claimsSpecified && selectors.isEmpty()) {
                try {
                    collaborationService.release(activeProjectRoot, provider, connectionInstanceId);
                } catch (IOException missing) {
                    if ("INTENT_NOT_FOUND".equals(missing.getMessage()) && taskIntent != null && taskIntent.role() == WorkIntent.Role.REVIEWER) {
                        agentResponse = reviewerClaimRequiredResponse();
                    } else if (!"INTENT_NOT_FOUND".equals(missing.getMessage())) {
                        agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
                    }
                } catch (Exception failure) {
                    agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
                }
            } else if (taskIntent != null && taskIntent.role() == WorkIntent.Role.REVIEWER && selectors.isEmpty() && !recoveryAdmission) {
                agentResponse = reviewerClaimRequiredResponse();
            } else if ((!selectors.isEmpty() || taskIntent != null && taskIntent.intentId() != null) && !recoveryAdmission) {
                try {
                    ClaimResult claimResult = collaborationService.announce(activeProjectRoot,
                            provider,
                            connectionInstanceId,
                            taskIntent == null ? null : taskIntent.goal(),
                            taskIntent == null ? null : taskIntent.acceptance(),
                            selectors,
                            taskIntent == null ? null : taskIntent.workGroupId(),
                            taskIntent == null ? WorkIntent.Role.PRODUCER : taskIntent.role(),
                            taskIntent == null ? List.of() : taskIntent.reviewTargetSelectors(),
                            taskIntent == null ? List.of() : taskIntent.knownDependencies(),
                            taskIntent == null ? null : taskIntent.intentId(),
                            taskIntent == null ? null : taskIntent.claimEpoch());
                    if (!claimResult.acquired()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("conflicts",
                                claimResult.conflicts().stream().map(conflict -> Map.of("participant",
                                        conflict.participant(),
                                        "intent",
                                        conflict.intentId(),
                                        "ownerAuthorityLineageId",
                                        conflict.ownerAuthorityLineageId() == null ? "" : conflict.ownerAuthorityLineageId().toString(),
                                        "kind",
                                        conflict.selector().kind().name(),
                                        "path",
                                        conflict.selector().value())).toList());
                        details.put("coordinationKind", "capability_request");
                        details.put("requiredCapabilityFields", List.of("capability", "contract", "ownerAuthorityLineageId"));
                        details.put("claimRule", "Keep the owner claim exclusive. Claim a disjoint consumer path before requesting its API capability.");
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_COORDINATION, details);
                    }
                } catch (Exception failure) {
                    if ("SESSION_EPOCH_FENCED".equals(failure.getMessage())) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_GENERATION_CHANGED, AgentNextAction.RETRY, null);
                    } else if (failure instanceof WorkspaceCollaborationService.ScopeExtensionRequiredException required) {
                        agentResponse = scopeExtensionRequiredResponse(required.currentIntent(), taskIntent, selectors);
                    } else if (isScopeExtensionFailure(failure.getMessage())) {
                        AgentReason reason = "OVERLAPPING_CLAIM".equals(failure.getMessage())
                                ? AgentReason.OVERLAPPING_CLAIM
                                : AgentReason.POLICY_DENIED;
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                                reason,
                                AgentNextAction.REQUEST_HUMAN_HELP,
                                Map.of("reason", failure.getMessage()));
                    } else if (isAppendContention(failure)) {
                        // Another session held the project log; nothing was recorded and the
                        // same announcement is safe to repeat.
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of("reason", "EVENT_APPEND_LOCK_UNAVAILABLE"));
                    } else {
                        System.err.println("SYNESIS_MCP_ANNOUNCE_FAILURE=" + failureDiagnostic(failure));
                        agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
                    }
                }
            }
        }
        return textResultResponse(id, agentResponse.toJson());
    }

    /**
     * Reports whether this exact connection's binding is durably completed.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     * @return {@code true} only when the completed binding resolves; unreadable or active state
     *         is {@code false}, so callers keep their fail-closed path
     */
    boolean exactBindingCompleted(Path activeProjectRoot) {
        try {
            authorityResolver.resolveCompleted(new ProjectApplicationService().locate(activeProjectRoot), provider, connectionInstanceId);
            return true;
        } catch (Exception notCompletedOrUnreadable) {
            return false;
        }
    }

    /**
     * Explains the explicit ownership required before a reviewer lane can be admitted.
     *
     * @return retryable reviewer admission response
     */
    private AgentResponse reviewerClaimRequiredResponse() {
        return new AgentResponse(AgentStatus.BLOCKED,
                AgentReason.POLICY_DENIED,
                AgentNextAction.ENSURE_SESSION,
                Map.of("reason", "REVIEW_CLAIM_REQUIRED", "message", "A reviewer must explicitly claim a disjoint report or test path; " + "reviewTargets identify evidence to inspect but do not grant ownership."));
    }

    private static boolean isScopeExtensionFailure(String message) {
        return message != null && (message.startsWith("SCOPE_EXTENSION_") || "CLAIM_EPOCH_STALE".equals(message)
                || "OVERLAPPING_CLAIM".equals(message) || "INTENT_OWNER_MISMATCH".equals(message)
                || "INTENT_NOT_ACTIVE".equals(message));
    }

    private static AgentResponse scopeExtensionRequiredResponse(WorkIntent current,
            AgentTaskIntent requested,
            List<ResourceSelector> requestedSelectors) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("goal", requested.goal() == null ? current.goal() : requested.goal());
        task.put("acceptance", requested.acceptance() == null ? current.acceptance() : requested.acceptance());
        task.put("intentId", current.intentId().toString());
        task.put("claimEpoch", current.claimEpoch().value());
        task.put("workGroupId", current.workGroupId().toString());
        task.put("role", current.role().wireValue());
        List<ResourceSelector> selectors = new ArrayList<>(current.selectors());
        for (ResourceSelector selector : requestedSelectors) {
            if (!selectors.contains(selector)) {
                selectors.add(selector);
            }
        }
        task.put("claims", selectors.stream().map(selector -> Map.of("kind",
                selector.kind() == ResourceSelector.Kind.PATH_EXACT ? "path_exact" : "path_subtree",
                "path",
                selector.value())).toList());
        java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>(current.knownDependencies());
        dependencies.addAll(requested.knownDependencies());
        task.put("knownDependencies", List.copyOf(dependencies));
        if (!current.reviewTargetSelectors().isEmpty()) {
            task.put("reviewTargets", current.reviewTargetSelectors().stream().map(selector -> Map.of("kind",
                    selector.kind() == ResourceSelector.Kind.PATH_EXACT ? "path_exact" : "path_subtree",
                    "path",
                    selector.value())).toList());
        }
        Map<String, Object> arguments = Map.of("task", task);
        return new AgentResponse(AgentStatus.BLOCKED,
                AgentReason.POLICY_DENIED,
                AgentNextAction.ENSURE_SESSION,
                Map.of("reason",
                        "SCOPE_EXTENSION_PRECONDITION_REQUIRED",
                        "recommendedTool",
                        "ensure_session",
                        "arguments",
                        arguments,
                        "nextProtocolKind",
                        "ensure_session",
                        "nextProtocolPayload",
                        Map.of("tool", "ensure_session", "arguments", arguments)));
    }

    /**
     * Recognizes a failure to obtain the project append lock within its bound.
     *
     * @param failure failure raised while recording a coordination event
     * @return {@code true} when nothing was appended because the lock was busy
     */
    private static boolean isAppendContention(Exception failure) {
        String message = failure.getMessage();
        return "event append lock timeout".equals(message) || "event append lock unavailable".equals(message);
    }

    /**
     * Renders a failure for the server diagnostic stream: its type and a bounded single-line
     * message, so an otherwise opaque internal failure can be traced without exposing payloads.
     */
    private static String failureDiagnostic(Exception failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().replaceAll("\\s+", " ");
        return failure.getClass().getSimpleName() + (message.isEmpty() ? "" : ":" + message.substring(0, Math.min(message.length(), 200)));
    }
}
