package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpJsonRpcResponses.createErrorResponse;
import static org.synesis.mcp.application.McpJsonRpcResponses.createResultResponse;
import static org.synesis.mcp.application.McpJsonRpcResponses.errorTextResultResponse;
import static org.synesis.mcp.application.McpJsonRpcResponses.manualAttestationRequiredResponse;
import static org.synesis.mcp.application.McpJsonRpcResponses.policyDenied;
import static org.synesis.mcp.application.McpJsonRpcResponses.textResultResponse;
import static org.synesis.mcp.application.McpTaskArguments.taskField;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.synesis.mcp.application.McpManagedAdmission.ManagedAdmissionState;
import org.synesis.mcp.contract.McpToolCatalog;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.collaboration.ReviewSnapshotAccessService;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.integration.CapabilityValidationSnapshotAccessService;
import org.synesis.workspace.application.project.ProjectCommandAdmissionService;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.workspace.mutation.WorkspacePatchService;
import org.synesis.workspace.application.workspace.read.WorkspaceReadService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;
import org.synesis.workspace.lifecycle.reconciliation.ReconciliationService;

/**
 * Handles JSON-RPC 2.0 requests for the Synesis Model Context Protocol (MCP) server over stdio.
 *
 * <p>Implements the standard MCP handshake ({@code initialize}), tool discovery
 * ({@code tools/list}),
 * and tool invocation ({@code tools/call}). Normal tool responses return Stage 1 concise
 * {@link AgentResponse} payloads wrapped in standard MCP content frames.
 *
 * <p>One handler belongs to one persistent MCP connection and therefore one
 * provider binding. Session resolution, workspace authority, durable command admission, and
 * lifecycle finalization remain behind the application services; this class only translates the
 * wire request and response. The authoritative {@link McpToolCatalog} supplies the exact ten-tool
 * surface.</p>
 *
 * @since 1.0
 */
public final class McpProtocolHandler {

    /**
     * Protocol version selected when a client omits its requested version.
     */
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
    /**
     * MCP protocol versions accepted by the handshake.
     */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
    /**
     * Version reported in {@code serverInfo}.
     */
    private static final String SERVER_VERSION = "0.1.0-SNAPSHOT";
    /**
     * Prefix of internal dispatch names; the wire contract advertises raw tool names only.
     */
    private static final String TOOL_PREFIX = "synesis.";
    /**
     * JSON-RPC 2.0 error code: the message was not valid JSON.
     */
    private static final int PARSE_ERROR = -32700;
    /**
     * JSON-RPC 2.0 error code: the message was not a valid request object.
     */
    private static final int INVALID_REQUEST = -32600;
    /**
     * JSON-RPC 2.0 error code: the method does not exist.
     */
    private static final int METHOD_NOT_FOUND = -32601;
    /**
     * JSON-RPC 2.0 error code: the method parameters were invalid.
     */
    private static final int INVALID_PARAMS = -32602;
    /**
     * JSON-RPC 2.0 error code: the handler failed internally.
     */
    private static final int INTERNAL_ERROR = -32603;

    /**
     * Service that reads and mutates durable collaboration state.
     */
    private final WorkspaceCollaborationService collaborationService;
    /**
     * Persists and closes this connection's session lease.
     */
    private final SessionLeaseService leaseService;
    /**
     * Validates provider manual attestation before authority-increasing calls.
     */
    private final ProviderManualService manualService;
    /**
     * Project root supplied by the launcher before MCP roots are resolved.
     */
    private final Path initialProjectRoot;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;
    /**
     * Process evidence captured once so later close callbacks remain PID-gated.
     */
    private final SessionProcessIdentity commandProcessIdentity;
    /**
     * Owns the project root this connection operates on and the root-authority diagnostic.
     */
    private final McpProjectRootSelection projectRoots;
    /**
     * Decides managed-continuity admission for each tool call.
     */
    private final McpManagedAdmission admission;
    /**
     * Renews the session lease and collaboration heartbeat after verified activity.
     */
    private final McpSessionLease lease;
    /**
     * Runs the {@code run_command} tool and owns the durable command process anchor.
     */
    private final McpDurableCommand commands;
    /**
     * Runs the {@code read_file} and {@code apply_patch} tools.
     */
    private final McpWorkspaceTools workspaceTools;
    /**
     * Runs recovery reconciliation and owns its one-time admission latch.
     */
    private final McpRecoveryReconciliation reconciliation;
    /**
     * Runs the coordination-facing tools.
     */
    private final McpCoordinationTools coordinationTools;
    /**
     * Establishes the provider session and owns whether this connection is bound to one.
     */
    private final McpSessionEstablishment sessionEstablishment;

    /**
     * Creates an MCP protocol handler.
     *
     * @param sessionService       application session service
     * @param projectRoot          canonical control project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection-instance ID
     */
    public McpProtocolHandler(AgentSessionService sessionService, Path projectRoot, String provider, String connectionInstanceId) {
        this(sessionService, projectRoot, provider, connectionInstanceId, McpSessionLease.captureProcessIdentity(connectionInstanceId), false, false);
    }

    /**
     * Creates an MCP protocol handler with process identity captured by the server entrypoint.
     *
     * @param sessionService       application session service
     * @param projectRoot          canonical control project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection-instance ID
     * @param processIdentity      one immutable identity captured for this MCP process
     */
    public McpProtocolHandler(AgentSessionService sessionService, Path projectRoot, String provider, String connectionInstanceId, SessionProcessIdentity processIdentity) {
        this(sessionService, projectRoot, provider, connectionInstanceId, processIdentity, false);
    }

    /**
     * Creates an MCP protocol handler for tests that pin the root and capture process identity themselves.
     *
     * @param sessionService       application session service
     * @param projectRoot          initial launcher project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection-instance ID
     * @param projectRootPinned    whether {@code projectRoot} came from an explicit launcher setting
     */
    McpProtocolHandler(AgentSessionService sessionService, Path projectRoot, String provider, String connectionInstanceId, boolean projectRootPinned) {
        this(sessionService, projectRoot, provider, connectionInstanceId, McpSessionLease.captureProcessIdentity(connectionInstanceId), projectRootPinned, false);
    }

    /**
     * Creates an MCP protocol handler with explicit launcher-root provenance.
     *
     * @param sessionService       application session service
     * @param projectRoot          initial launcher project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection-instance ID
     * @param processIdentity      one immutable identity captured for this MCP process
     * @param projectRootPinned    whether {@code projectRoot} came from an explicit launcher setting
     */
    public McpProtocolHandler(AgentSessionService sessionService, Path projectRoot, String provider, String connectionInstanceId, SessionProcessIdentity processIdentity, boolean projectRootPinned) {
        this(sessionService, projectRoot, provider, connectionInstanceId, processIdentity, projectRootPinned, false);
    }

    /**
     * Creates an MCP handler after an explicit managed attachment admission.
     *
     * @param sessionService       application session service
     * @param projectRoot          initial launcher project root
     * @param provider             stable provider name
     * @param connectionInstanceId exact connection selector
     * @param processIdentity      captured process identity
     * @param projectRootPinned    whether the root was launcher-pinned
     * @param managedAdmission     whether exact managed proof was verified at startup
     */
    public McpProtocolHandler(AgentSessionService sessionService,
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            SessionProcessIdentity processIdentity,
            boolean projectRootPinned,
            boolean managedAdmission) {
        this(sessionService, projectRoot, provider, connectionInstanceId, processIdentity, projectRootPinned, managedAdmission, null);
    }

    /**
     * Creates an MCP handler after managed transport admission.
     *
     * <p>A pending record authenticates only the launch-local transport. Tool
     * calls remain quarantined until the durable record is promoted to {@code ACTIVE}; the handler
     * re-reads that record so the same process connection can be promoted without resupplying
     * proof.</p>
     *
     * @param sessionService       application session service
     * @param projectRoot          initial launcher project root
     * @param provider             stable provider name
     * @param connectionInstanceId exact connection selector
     * @param processIdentity      captured process identity
     * @param projectRootPinned    whether the root was launcher-pinned
     * @param managedAdmission     whether managed transport proof was verified
     * @param managedAttachment    authenticated non-secret attachment metadata
     */
    public McpProtocolHandler(AgentSessionService sessionService,
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            SessionProcessIdentity processIdentity,
            boolean projectRootPinned,
            boolean managedAdmission,
            ManagedAttachmentRecord managedAttachment) {
        Objects.requireNonNull(sessionService, "sessionService");
        WorkspaceReadService readService = new WorkspaceReadService();
        WorkspacePatchService patchService = new WorkspacePatchService();
        ProjectCommandService commandService = new ProjectCommandService();
        ProjectCommandAdmissionService commandAdmissionService = new ProjectCommandAdmissionService(commandService, AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
        AgentNextActionService nextActionService = new AgentNextActionService();
        CapabilityRequestService capabilityRequestService = new CapabilityRequestService();
        CapabilityResponseService capabilityResponseService = new CapabilityResponseService();
        ImplementationPublicationService publicationService = new ImplementationPublicationService();
        ImplementationValidationService validationService = new ImplementationValidationService();
        ReviewValidationService reviewValidationService = new ReviewValidationService();
        AgentTaskCompletionService taskCompletionService = new AgentTaskCompletionService();
        AgentTaskCancellationService taskCancellationService = new AgentTaskCancellationService();
        this.collaborationService = new WorkspaceCollaborationService();
        ReviewSnapshotAccessService reviewSnapshotAccessService = new ReviewSnapshotAccessService();
        CapabilityValidationSnapshotAccessService validationSnapshotAccessService = new CapabilityValidationSnapshotAccessService();
        SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
        this.leaseService = new SessionLeaseService();
        SessionLeasePolicy leasePolicy = new SessionLeasePolicy();
        ReconciliationService reconciliationService = new ReconciliationService();
        this.manualService = new ProviderManualService();
        this.initialProjectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.connectionInstanceId = Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        this.commandProcessIdentity = Objects.requireNonNull(processIdentity, "processIdentity");
        if (managedAdmission && managedAttachment == null) {
            throw new IllegalArgumentException("managed attachment metadata required");
        }
        if (managedAttachment != null && !managedAdmission) {
            throw new IllegalArgumentException("managed attachment requires managed admission");
        }
        String managedBindingSessionId = managedAttachment == null ? null : managedAttachment.bindingSessionId();
        long managedAttachmentGeneration = managedAttachment == null ? 0L : managedAttachment.generation();
        if (managedAttachment != null && managedAttachment.status() != ManagedAttachmentRecord.Status.ACTIVE && managedAttachment.status() != ManagedAttachmentRecord.Status.PENDING_ACTIVATION) {
            throw new IllegalArgumentException("managed attachment is not transport-admissible");
        }
        this.projectRoots = new McpProjectRootSelection(projectRoot, projectRootPinned);
        this.admission = new McpManagedAdmission(managedAdmission, managedBindingSessionId, managedAttachmentGeneration, provider, connectionInstanceId, authorityResolver);
        this.lease = new McpSessionLease(authorityResolver, collaborationService, leaseService, leasePolicy, provider, connectionInstanceId);
        this.commands = new McpDurableCommand(commandAdmissionService, reviewSnapshotAccessService, validationSnapshotAccessService, provider, connectionInstanceId, processIdentity, lease);
        this.workspaceTools = new McpWorkspaceTools(readService, patchService, provider, connectionInstanceId);
        this.reconciliation = new McpRecoveryReconciliation(reconciliationService);
        this.coordinationTools = new McpCoordinationTools(nextActionService,
                capabilityRequestService,
                capabilityResponseService,
                publicationService,
                validationService,
                reviewValidationService,
                taskCompletionService,
                taskCancellationService,
                collaborationService,
                authorityResolver,
                reconciliation,
                provider,
                connectionInstanceId);
        this.sessionEstablishment = new McpSessionEstablishment(sessionService,
                manualService,
                taskCompletionService,
                collaborationService,
                authorityResolver,
                lease,
                commands,
                reconciliation,
                provider,
                connectionInstanceId,
                managedAdmission);
        Path launchRoot = resolveProjectRootFromCandidates(projectRootPinned ? List.of() : List.of(projectRoot));
        if (launchRoot != null && projectRoots.projectRootAuthorityFailure() == null) {
            projectRoots.adopt(launchRoot);
        }
    }

    /**
     * Safely converts a URI string or local path string into a normalized absolute {@link Path}.
     *
     * @param input URI or local path string
     * @return normalized absolute Path, or {@code null} if empty/invalid
     */
    public static Path parseUriOrPath(String input) {
        return McpRootCandidates.parseUriOrPath(input);
    }

    /**
     * Determines whether this operation can increase authority and therefore needs attestation.
     *
     * @param name      internal dispatch name, including the tool prefix
     * @param arguments tool arguments, possibly {@code null}
     * @return {@code true} when the call needs a valid provider manual attestation
     */
    private static boolean requiresManualAttestation(String name, Map<String, Object> arguments) {
        return switch (name) {
            // Every coordination request needs an attestation, the status read included. The decision
            // must not depend on argument keys the payload normalizer ignores, or a caller could add one
            // to an authority-increasing request and skip the gate.
            case TOOL_PREFIX + McpToolCatalog.APPLY_PATCH, TOOL_PREFIX + McpToolCatalog.RUN_COMMAND, TOOL_PREFIX + McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION, TOOL_PREFIX + McpToolCatalog.FINISH_LANE,
                 TOOL_PREFIX + McpToolCatalog.REQUEST_COORDINATION -> true;
            case TOOL_PREFIX + McpToolCatalog.ENSURE_SESSION ->
                    arguments != null && (Boolean.TRUE.equals(arguments.get("unwindCompletion")) || taskField(arguments, "repairIntentId") != null || taskField(arguments, "repairSnapshotId") != null);
            case TOOL_PREFIX + McpToolCatalog.RESPOND_COORDINATION -> arguments == null || arguments.get("kind") != null;
            default -> false;
        };
    }

    /**
     * Restricts completed managed connections to the three permitted review coordination calls.
     *
     * @param toolName internal dispatch name
     * @return {@code true} for get_next_action, request_coordination, and respond_coordination
     */
    private static boolean completedReviewTool(String toolName) {
        return (TOOL_PREFIX + McpToolCatalog.GET_NEXT_ACTION).equals(toolName) || (TOOL_PREFIX + McpToolCatalog.REQUEST_COORDINATION).equals(toolName) || (TOOL_PREFIX + McpToolCatalog.RESPOND_COORDINATION).equals(
                toolName);
    }

    /**
     * Returns the currently resolved active control project root path.
     *
     * @return active control project root path
     */
    public Path activeProjectRoot() {
        return projectRoots.activeProjectRoot();
    }

    /** Marks clean transport EOF and immediately preserves any unfinished lane for recovery. */
    public void close() {
        if (projectRoots.activeProjectRoot() == null) {
            return;
        }
        boolean closed = leaseService.markClosedCleanly(projectRoots.activeProjectRoot(), connectionInstanceId, commandProcessIdentity.pid());
        if (!closed) {
            System.err.println("SYNESIS_MCP_TRANSPORT_FINALIZATION=clean_close_unconfirmed");
            return;
        }
        System.err.println("SYNESIS_MCP_TRANSPORT_FINALIZATION=clean_eof");
        reconciliation.reconcileRecoveryBeforeInboxPoll(projectRoots.activeProjectRoot());
    }

    /**
     * Records an abnormal stdio shutdown for this exact MCP process.
     *
     * <p>The process identity fence prevents a later connection that reuses the
     * connection identifier from claiming the earlier runtime's abnormal outcome.  Nonterminal leases
     * remain in their existing stale/recovery lifecycle.
     */
    public void closeAbnormally() {
        try {
            leaseService.markTerminalDisconnected(projectRoots.activeProjectRoot(), connectionInstanceId, commandProcessIdentity.pid());
        } catch (Exception ignored) {
            // Recovery reconciles an abnormal finalization that could not be persisted.
        }
    }

    /**
     * Processes a raw JSON-RPC message string and returns the JSON-RPC response frame string, or
     * {@code null} if the request was a notification.
     *
     * <p>Malformed input and handler failures are converted into JSON-RPC error
     * responses where a request ID is available. Notification requests produce no response, while
     * successful activity renews the exact bound session rather than a provider-wide fallback.</p>
     *
     * @param jsonMessage raw JSON-RPC request message
     * @return serialized JSON-RPC response frame, or {@code null} for notifications
     */
    @SuppressWarnings("unchecked")
    public String handleMessage(String jsonMessage) {
        if (jsonMessage == null || jsonMessage.isBlank()) {
            return null;
        }

        Object parsed;
        try {
            parsed = ProviderJson.parse(jsonMessage);
        } catch (Throwable parseFailure) {
            System.err.println("[synesis-mcp] Parse error: " + parseFailure);
            parseFailure.printStackTrace(System.err);
            return createErrorResponse(null, PARSE_ERROR, "Parse error: " + parseFailure.getMessage());
        }

        if (!(parsed instanceof Map<?, ?> map)) {
            return createErrorResponse(null, INVALID_REQUEST, "Invalid Request: expected JSON object");
        }

        Map<String, Object> request = (Map<String, Object>) map;
        Object id = request.get("id");
        String method = (String) request.get("method");

        if (method == null || method.isBlank()) {
            if (id != null) {
                return createErrorResponse(id, INVALID_REQUEST, "Invalid Request: missing method");
            }
            return null;
        }

        if (id == null && ("initialized".equals(method) || "notifications/initialized".equals(method))) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> handleInitialize(id, (Map<String, Object>) request.get("params"));
                case "initialized", "notifications/initialized" -> null;
                case "notifications/roots/list_changed", "roots/list_changed" -> projectRoots.handleRootsListChanged(request.get("params"), sessionEstablishment.isSessionBound());
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, request.get("params"));
                case "roots/list" -> projectRoots.handleRootsList(id);
                default -> createErrorResponse(id, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (Throwable failure) {
            System.err.println("[synesis-mcp] Error handling method " + method + ": " + failure);
            failure.printStackTrace(System.err);
            return createErrorResponse(id, INTERNAL_ERROR, "Internal error: " + failure.getMessage());
        }
    }

    /**
     * Creates the initialize response and resolves the initial project root before session binding.
     *
     * @param id     JSON-RPC request identifier
     * @param params initialize params, possibly {@code null}
     * @return the initialize result response
     */
    private String handleInitialize(Object id, Map<String, Object> params) {
        if (!sessionEstablishment.isSessionBound()) {
            // Primary: extract from MCP params (rootUri, workspaceFolders, roots)
            List<Path> paramCandidates = extractCandidateRoots(params);
            Path resolved = resolveProjectRootFromCandidates(paramCandidates);

            // Fallback: scan supported environment variables when params yield nothing
            if (resolved == null) {
                List<Path> fallbackCandidates = McpRootCandidates.extractFallbackCandidates(initialProjectRoot);
                resolved = resolveProjectRootFromCandidates(fallbackCandidates);
            }

            if (resolved != null) {
                projectRoots.adopt(resolved);
            }
        }

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "synesis");
        serverInfo.put("version", SERVER_VERSION);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new LinkedHashMap<>());
        capabilities.put("roots", Map.of("listChanged", true));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiateProtocolVersion(params));
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        return createResultResponse(id, result);
    }

    /**
     * Negotiates the MCP protocol version for an initialize request.
     *
     * <p>The client-selected version is echoed only when this implementation supports it;
     * otherwise the current baseline is returned.
     *
     * @param params initialize request parameters, or {@code null}
     * @return negotiated protocol version
     */
    private String negotiateProtocolVersion(Map<String, Object> params) {
        if (params != null && params.get("protocolVersion") instanceof String requested && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
            return requested;
        }
        return DEFAULT_PROTOCOL_VERSION;
    }

    /**
     * Extracts candidate workspace project root paths from MCP {@code initialize} request parameters
     * only. Does not include environment variables, file system scans, or process working directory.
     *
     * @param params JSON-RPC initialize request params map, or {@code null}
     * @return list of parsed candidate paths from protocol params
     */
    public List<Path> extractCandidateRoots(Map<String, Object> params) {
        return McpRootCandidates.extractCandidateRoots(params);
    }

    /**
     * Resolves the single initialized Synesis control project root from candidate paths.
     *
     * @param candidates candidate workspace paths
     * @return resolved control project root, or {@code null} if none or ambiguous
     */
    public Path resolveProjectRootFromCandidates(List<Path> candidates) {
        return projectRoots.resolveProjectRootFromCandidates(candidates);
    }

    /**
     * Returns the authoritative tool catalog without exposing internal dispatch names.
     *
     * @param id JSON-RPC request identifier
     * @return the tools/list result response
     */
    private String handleToolsList(Object id) {
        return createResultResponse(id, Map.of("tools", McpToolCatalog.toolsList()));
    }

    /**
     * Validates and dispatches one tools/call envelope after enforcing connection authority.
     *
     * @param id     JSON-RPC request identifier, or {@code null} for a notification
     * @param params untrusted parsed JSON value from the request's params field
     * @return JSON-RPC response, or {@code null} when no response is required
     */
    private String handleToolsCall(Object id, Object params) {
        McpToolCall call;
        try {
            call = McpToolCall.decode(params);
        } catch (IllegalArgumentException invalidCall) {
            return createErrorResponse(id, INVALID_PARAMS, invalidCall.getMessage());
        }

        // The wire contract advertises raw names only; the prefix is internal dispatch notation.
        String name = TOOL_PREFIX + call.name();
        Map<String, Object> arguments = call.arguments().orElse(null);

        if (projectRoots.projectRootAuthorityFailure() != null) {
            AgentResponse mismatch = new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, Map.of("error", projectRoots.projectRootAuthorityFailure()));
            return textResultResponse(id, mismatch.toJson());
        }
        ManagedAdmissionState managedState = admission.managedAdmissionState(completedReviewTool(name), projectRoots.activeProjectRoot(), projectRoots.projectRootResolved());
        if (managedState != ManagedAdmissionState.ACTIVE && managedState != ManagedAdmissionState.REVIEW_ONLY) {
            return admission.managedAdmissionResponse(id, managedState);
        }

        AgentResponse agentResponse;
        boolean durableCommand = name.equals(TOOL_PREFIX + McpToolCatalog.RUN_COMMAND);
        boolean noChangeCompletion = name.equals(TOOL_PREFIX + McpToolCatalog.FINISH_LANE) && arguments != null && arguments.get("outcome") instanceof String outcome && "no_change".equalsIgnoreCase(outcome);
        boolean snapshotCompletion = name.equals(TOOL_PREFIX + McpToolCatalog.FINISH_LANE);
        // finish_lane carries an optimistic event-log revision in its
        // projected evidence.  Heartbeats no longer invalidate that envelope,
        // but a lane that is being closed has no liveness to renew, so the
        // ordinary lease heartbeat is still skipped here.  Completion itself
        // remains authoritative; all other non-command calls retain the
        // normal activity heartbeat.
        if (managedState != ManagedAdmissionState.REVIEW_ONLY && !durableCommand && !noChangeCompletion && !snapshotCompletion) {
            lease.renewLease(projectRoots.activeProjectRoot());
        }

        // Active bindings still pass through the normal recovery fence below. Completed
        // bindings reach only the completion service's exact durable-result replay checks.
        boolean exactCompletedNoChangeReplay = sessionEstablishment.isSessionBound() && noChangeCompletion && sessionEstablishment.exactBindingCompleted(projectRoots.activeProjectRoot());
        if (sessionEstablishment.isSessionBound() && !exactCompletedNoChangeReplay) {
            try {
                if (collaborationService.recoveryFenced(projectRoots.activeProjectRoot(), provider, connectionInstanceId)) {
                    return textResultResponse(id, policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "SESSION_EPOCH_FENCED").toJson());
                }
            } catch (Exception failure) {
                // A lane that completed has no active binding left to fence, and its agent's
                // closing inbox read must be told so rather than sent to a human. Only that
                // read is let through: every mutating tool still fails closed here.
                boolean closingRead = name.equals(TOOL_PREFIX + McpToolCatalog.GET_NEXT_ACTION) && sessionEstablishment.exactBindingCompleted(projectRoots.activeProjectRoot());
                if (!closingRead) {
                    System.err.println("SYNESIS_MCP_RECOVERY_FENCE_CHECK_FAILURE=" + failure.getClass().getSimpleName());
                    return textResultResponse(id, policyDenied(AgentNextAction.REQUEST_HUMAN_HELP, "reason", "SESSION_RECOVERY_STATE_UNAVAILABLE").toJson());
                }
            }
        }

        if (requiresManualAttestation(name, arguments)) {
            var attestation = manualService.attest(provider);
            if (!attestation.valid()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("manual", attestation.reason());
                details.put("authorityReductionAllowed", true);
                return manualAttestationRequiredResponse(id, details);
            }
        }

        switch (name) {
            case TOOL_PREFIX + McpToolCatalog.ENSURE_SESSION -> {
                return sessionEstablishment.ensureSession(id, arguments, projectRoots.activeProjectRoot());
            }
            case TOOL_PREFIX + McpToolCatalog.READ_FILE -> agentResponse = workspaceTools.readFile(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.APPLY_PATCH -> agentResponse = workspaceTools.applyPatch(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.RUN_COMMAND -> agentResponse = commands.runCommand(id, arguments, projectRoots.activeProjectRoot(), projectRoots.projectRootAuthorityFailure());
            case TOOL_PREFIX + McpToolCatalog.GET_NEXT_ACTION -> agentResponse = coordinationTools.getNextAction(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.REQUEST_COORDINATION -> agentResponse = coordinationTools.requestCoordination(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.RESPOND_COORDINATION -> agentResponse = coordinationTools.respondCoordination(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION -> agentResponse = coordinationTools.publishCapabilityImplementation(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.FINISH_LANE -> agentResponse = coordinationTools.finishLane(arguments, projectRoots.activeProjectRoot());
            case TOOL_PREFIX + McpToolCatalog.CANCEL_LANE -> agentResponse = coordinationTools.cancelLane(arguments, projectRoots.activeProjectRoot());
            default -> {
                return errorTextResultResponse(id, "Unknown tool: " + name);
            }
        }

        return textResultResponse(id, agentResponse.toJson());
    }
}
