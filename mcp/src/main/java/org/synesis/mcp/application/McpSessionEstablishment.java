package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpJsonRpcResponses.claimsRejectedResponse;
import static org.synesis.mcp.application.McpJsonRpcResponses.object;
import static org.synesis.mcp.application.McpJsonRpcResponses.policyDenied;
import static org.synesis.mcp.application.McpJsonRpcResponses.textResultResponse;
import static org.synesis.mcp.application.McpTaskArguments.claimsFieldSpecified;
import static org.synesis.mcp.application.McpTaskArguments.parseClaimSelectors;
import static org.synesis.mcp.application.McpTaskArguments.parseTaskIntent;
import static org.synesis.mcp.application.McpTaskArguments.taskField;
import static org.synesis.mcp.application.McpTaskArguments.wireKind;

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
import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.AgentTaskIntent;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.project.ProjectLocation;
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
        AgentTaskIntent task;
        try {
            task = parseTaskIntent(arguments);
        } catch (IllegalArgumentException invalidTask) {
            String reason = "TASK_FIELDS_MUST_BE_NESTED".equals(invalidTask.getMessage()) ? "TASK_FIELDS_MUST_BE_NESTED" : "INVALID_TASK_INTENT";
            return textResultResponse(id, McpRefusalGuidance.of(McpRefusalGuidance.malformed(AgentNextAction.REQUEST_HUMAN_HELP, "reason", reason, invalidTask)).toJson());
        }
        boolean refresh = arguments != null && Boolean.TRUE.equals(arguments.get("refresh"));
        List<ResourceSelector> selectors;
        try {
            selectors = parseClaimSelectors(arguments);
        } catch (IllegalArgumentException invalidClaim) {
            // An absolute, escaping or protected claim path used to surface as a JSON-RPC internal error.
            return textResultResponse(id, McpRefusalGuidance.of(new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, null,
                    object("reason", "INVALID_CLAIM", "detail", String.valueOf(invalidClaim.getMessage())))).toJson());
        }

        if (!selectors.isEmpty() && !manualService.attest(provider).valid()) {
            return claimsRejectedResponse(id);
        }

        AgentResponse admitted = sessionService.ensureSession(new SessionResolutionRequest(activeProjectRoot, provider, connectionInstanceId, task, refresh, managedAdmission));
        if (admitted.status() != AgentStatus.READY) {
            return textResultResponse(id, McpRefusalGuidance.of(admitted).toJson());
        }
        bindVerifiedSession(activeProjectRoot);
        // Every phase runs, and when several produce a reply the later one wins: the claim
        // phase over the repair join over the unwind over the admission reply.
        AgentResponse unwound = unwindCompletion(arguments, activeProjectRoot);
        AgentResponse repaired = repairJoin(arguments, activeProjectRoot);
        AgentResponse claimed = claimPhase(task, selectors, refresh, claimsFieldSpecified(arguments), activeProjectRoot);
        AgentResponse reply = claimed != null ? claimed : repaired != null ? repaired : unwound != null ? unwound : withOpenReviewCandidates(admitted, activeProjectRoot);
        return textResultResponse(id, McpRefusalGuidance.of(reply).toJson());
    }

    /**
     * Adds the review candidates this session could take up to an admission that announced nothing,
     * so an agent that announces right after {@code ensure_session} sees them beside the unclaimed
     * parts. A held lane that comes before any review is listed instead, as
     * {@code continuationCandidates}: its author cannot admit a reviewer until it is continued.
     * Guidance only: a failure to read them leaves the admission reply as it was.
     *
     * @param admitted          the admission reply
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the reply, with {@code reviewCandidates} or {@code continuationCandidates} in its
     *         result when there are any
     */
    private AgentResponse withOpenReviewCandidates(AgentResponse admitted, Path activeProjectRoot) {
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            String sessionId = authorityResolver.resolve(location, provider, connectionInstanceId).sessionId();
            Map<String, Object> choices = CollaborationResponseMapper.openLaneChoices(location, WorkspaceCollaborationService.participantHandle(sessionId), provider);
            if (choices.isEmpty() || !(admitted.toMap().get("result") instanceof Map<?, ?> current)) {
                return admitted;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            current.forEach((key, value) -> result.put(String.valueOf(key), value));
            result.putAll(choices);
            return new AgentResponse(admitted.status(), admitted.reason(), admitted.nextAction(), result);
        } catch (Exception unreadable) {
            return admitted;
        }
    }

    /**
     * Marks the connection session-bound and starts its lease, so an abruptly deleted chat stays
     * recoverable even when it sends no follow-up request. The first verified {@code ensure_session}
     * is activity too, and the lease is established before any claim is announced.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     */
    private void bindVerifiedSession(Path activeProjectRoot) {
        isSessionBound = true;
        commands.refreshCommandProcessAnchorForVerifiedSession(activeProjectRoot);
        if (lease.renewLease(activeProjectRoot)) {
            reconciliation.reconcileAfterAdmission(activeProjectRoot);
        }
    }

    /**
     * Unwinds a prepared completion when the caller asks for it.
     *
     * @param arguments         tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the unwind reply, or {@code null} when none was requested
     */
    private AgentResponse unwindCompletion(Map<String, Object> arguments, Path activeProjectRoot) {
        if (arguments == null || !Boolean.TRUE.equals(arguments.get("unwindCompletion"))) {
            return null;
        }
        return taskCompletionService.unwindPrepared(new CompleteTaskRequest(activeProjectRoot, provider, connectionInstanceId, null));
    }

    /**
     * Joins the caller to a repair of a named intent and snapshot when it supplies them.
     *
     * @param arguments         tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the repair reply, or {@code null} when neither repair field was supplied
     */
    private AgentResponse repairJoin(Map<String, Object> arguments, Path activeProjectRoot) {
        String repairIntentText = taskField(arguments, "repairIntentId");
        String repairSnapshotId = taskField(arguments, "repairSnapshotId");
        if (repairIntentText == null && repairSnapshotId == null) {
            return null;
        }
        try {
            if (repairIntentText == null || repairSnapshotId == null) {
                throw new IllegalArgumentException("repair intent and snapshot are both required");
            }
            UUID repairIntentId = UUID.fromString(repairIntentText);
            var joined = collaborationService.joinRepair(activeProjectRoot, provider, connectionInstanceId, repairIntentId, repairSnapshotId);
            if (!joined.acquired()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("conflicts", joined.conflicts()));
            }
            return new AgentResponse(AgentStatus.READY,
                    null,
                    AgentNextAction.RETRY,
                    object("repairJoined", true, "intentId", joined.intent().intentId().toString(), "claimEpoch", joined.intent().version()));
        } catch (Exception repairFailure) {
            return policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "reason", repairFailure.getMessage() == null ? "REPAIR_JOIN_FAILED" : repairFailure.getMessage());
        }
    }

    /**
     * Applies the ownership claims of the call: releases them for an empty refresh, requires a
     * reviewer to claim explicitly, or announces the claims.
     *
     * @param task              task the call declares
     * @param selectors         claim selectors the call names
     * @param refresh           whether the call refreshes an existing session
     * @param claimsSpecified   whether the call carries a {@code claims} field, even an empty one
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the reply, or {@code null} when claims need no reply
     */
    private AgentResponse claimPhase(AgentTaskIntent task, List<ResourceSelector> selectors, boolean refresh, boolean claimsSpecified, Path activeProjectRoot) {
        boolean recoveryAdmission = !refresh && collaborationService.recoveryContinuationAdmission(activeProjectRoot, connectionInstanceId, task.role(), selectors, task.workGroupId());
        if (refresh && claimsSpecified && selectors.isEmpty()) {
            return releaseClaims(task, activeProjectRoot);
        }
        if (task.role() == WorkIntent.Role.REVIEWER && selectors.isEmpty() && !recoveryAdmission) {
            return reviewerClaimRequiredResponse();
        }
        if ((!selectors.isEmpty() || task.intentId() != null) && !recoveryAdmission) {
            return announceClaims(task, selectors, activeProjectRoot);
        }
        return null;
    }

    private AgentResponse releaseClaims(AgentTaskIntent task, Path activeProjectRoot) {
        try {
            collaborationService.release(activeProjectRoot, provider, connectionInstanceId);
            return null;
        } catch (IOException missing) {
            if (!"INTENT_NOT_FOUND".equals(missing.getMessage())) {
                return AgentFailures.internal(activeProjectRoot, "lane.release_claims", missing, null);
            }
            return task.role() == WorkIntent.Role.REVIEWER ? reviewerClaimRequiredResponse() : null;
        } catch (Exception failure) {
            return AgentFailures.internal(activeProjectRoot, "lane.release_claims", failure, null);
        }
    }

    private AgentResponse announceClaims(AgentTaskIntent task, List<ResourceSelector> selectors, Path activeProjectRoot) {
        try {
            ClaimResult claimResult = collaborationService.announce(activeProjectRoot,
                    provider,
                    connectionInstanceId,
                    task.goal(),
                    task.acceptance(),
                    selectors,
                    task.workGroupId(),
                    task.role(),
                    task.reviewTargetSelectors(),
                    task.knownDependencies(),
                    task.intentId(),
                    task.claimEpoch());
            if (claimResult.acquired()) {
                return null;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("conflicts",
                    claimResult.conflicts().stream().map(conflict -> object("participant",
                            conflict.participant(),
                            "intent",
                            conflict.intentId(),
                            "ownerAuthorityLineageId",
                            conflict.ownerAuthorityLineageId() == null ? "" : conflict.ownerAuthorityLineageId().toString(),
                            "kind",
                            conflict.selector().kind().name(),
                            "path",
                            conflict.selector().value())).toList());
            details.put("coordinationKind", "contract_bind");
            details.put("requiredCapabilityFields", List.of("capability", "ownerPath", "revision"));
            // The lane a caller collides with is the owner of that path only. Telling it to send
            // its capability request to that lane's lineage sent requests to unrelated lanes.
            details.put("claimRule", "These paths belong to the lanes shown; nothing was claimed. Choose work no lane has claimed, or claim only your own disjoint files. "
                    + "If your work builds on a file another lane claims, bind to that file's public contract with contract_bind, or say what you need with contract_change_request: "
                    + "ownerPath names the file, and the lane claiming it answers, which need not be a lane listed here.");
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_COORDINATION, details);
        } catch (Exception failure) {
            if ("SESSION_EPOCH_FENCED".equals(failure.getMessage())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_GENERATION_CHANGED, AgentNextAction.RETRY, null);
            }
            if (failure instanceof WorkspaceCollaborationService.ScopeExtensionRequiredException required) {
                return scopeExtensionRequiredResponse(required.currentIntent(), task, selectors);
            }
            if (isScopeExtensionFailure(failure.getMessage())) {
                AgentReason reason = "OVERLAPPING_CLAIM".equals(failure.getMessage())
                        ? AgentReason.OVERLAPPING_CLAIM
                        : AgentReason.POLICY_DENIED;
                return new AgentResponse(AgentStatus.BLOCKED,
                        reason,
                        AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("reason", failure.getMessage()));
            }
            if (isAppendContention(failure)) {
                // Another session held the project log; nothing was recorded and the
                // same announcement is safe to repeat.
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of("reason", "EVENT_APPEND_LOCK_UNAVAILABLE"));
            }
            if ("REVIEWER_REQUIRES_PRODUCER".equals(failure.getMessage())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.ENSURE_SESSION,
                        object("reason", "REVIEWER_REQUIRES_PRODUCER", "instruction",
                                "No producer lane exists in this work group for a reviewer to review, so the reviewer lane was not announced and this session holds no lane. "
                                        + "If the implementation in the project goal is unclaimed, announce it with ensure_session as a producer. "
                                        + "Otherwise call ensure_session with the reviewer task again once get_next_action lists a producer lane."));
            }
            if (failure instanceof org.synesis.coordination.exception.CoordinationException || failure instanceof IllegalArgumentException) {
                // The announcement itself was refused: a closed work group, a blank goal, review
                // targets on a producer. Answering an internal failure hid which.
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", String.valueOf(failure.getMessage())));
            }
            System.err.println("SYNESIS_MCP_ANNOUNCE_FAILURE=" + failureDiagnostic(failure));
            return AgentFailures.internal(activeProjectRoot, "lane.announce", failure, null);
        }
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
                object("reason", "REVIEW_CLAIM_REQUIRED", "message", "A reviewer must explicitly claim a disjoint report or test path; " + "reviewTargets identify evidence to inspect but do not grant ownership."));
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
        task.put("claims", selectors.stream().map(McpSessionEstablishment::wireSelector).toList());
        java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>(current.knownDependencies());
        dependencies.addAll(requested.knownDependencies());
        task.put("knownDependencies", List.copyOf(dependencies));
        if (!current.reviewTargetSelectors().isEmpty()) {
            task.put("reviewTargets", current.reviewTargetSelectors().stream().map(McpSessionEstablishment::wireSelector).toList());
        }
        Map<String, Object> arguments = Map.of("task", task);
        return new AgentResponse(AgentStatus.BLOCKED,
                AgentReason.POLICY_DENIED,
                AgentNextAction.ENSURE_SESSION,
                object("reason",
                        "SCOPE_EXTENSION_PRECONDITION_REQUIRED",
                        "recommendedTool",
                        "ensure_session",
                        "arguments",
                        arguments,
                        "nextProtocolKind",
                        "ensure_session",
                        "nextProtocolPayload",
                        object("tool", "ensure_session", "arguments", arguments)));
    }

    private static Map<String, Object> wireSelector(ResourceSelector selector) {
        return object("kind", wireKind(selector), "path", selector.value());
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
