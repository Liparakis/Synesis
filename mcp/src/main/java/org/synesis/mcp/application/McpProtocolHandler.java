package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpCollaborationProjection.collaborationStatusMap;
import static org.synesis.mcp.application.McpCollaborationProjection.contractMap;
import static org.synesis.mcp.application.McpCollaborationProjection.requestMap;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.mcp.contract.McpToolCatalog;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService.CancelTaskRequest;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.completion.CompletionOutcome;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.AgentTaskIntent;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.collaboration.ReviewSnapshotAccessService;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.integration.IntegrationCompatibilityService;
import org.synesis.workspace.application.integration.WorkspaceIntegrationReadinessService;
import org.synesis.workspace.application.project.ProjectCommandAdmissionService;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderContinuityMode;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.workspace.mutation.PatchEdit;
import org.synesis.workspace.application.workspace.mutation.PatchRequest;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.read.ReadRequest;
import org.synesis.workspace.application.workspace.mutation.WorkspacePatchService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.application.workspace.read.WorkspaceReadService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.command.PhysicalWorktreeIdentity;
import org.synesis.workspace.lifecycle.command.ProjectCommandAuthoritySnapshot;
import org.synesis.workspace.lifecycle.command.ProjectCommandProcessAnchor;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
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
     * Project metadata file whose presence marks an initialized Synesis project.
     */
    private static final String PROJECT_METADATA = ".synesis/project.json";
    /**
     * Environment variables scanned for a workspace root when initialize yields none, in priority order.
     */
    private static final List<String> FALLBACK_ROOT_ENVIRONMENT_KEYS = List.of("WORKSPACE_ROOT", "WORKSPACE_FOLDER", "WORKSPACE_DIR", "PROJECT_ROOT", "PROJECT_DIR", "VSCODE_WORKSPACE_FOLDER", "INIT_CWD", "PWD");
    /**
     * Maximum number of {@code knownDependencies} entries.
     */
    private static final int MAX_KNOWN_DEPENDENCIES = 50;
    /**
     * Maximum length of one {@code knownDependencies} entry.
     */
    private static final int MAX_DEPENDENCY_LENGTH = 128;
    /**
     * Largest accepted {@code run_command} timeout, in seconds.
     */
    private static final int MAX_COMMAND_TIMEOUT_SECONDS = 3600;
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
     * Service that resolves and admits the provider's session.
     */
    private final AgentSessionService sessionService;
    /**
     * Service that reads project files under the admitted workspace authority.
     */
    private final WorkspaceReadService readService;
    /**
     * Service that applies workspace edits under the admitted workspace authority.
     */
    private final WorkspacePatchService patchService;
    /**
     * Service that admits durable project commands before execution.
     */
    private final ProjectCommandAdmissionService commandAdmissionService;
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
     * Service that grants reads of immutable review snapshots.
     */
    private final ReviewSnapshotAccessService reviewSnapshotAccessService;
    /**
     * Resolves the exact provider and connection binding used for authority checks.
     */
    private final SessionAuthorityResolver authorityResolver;
    /**
     * Persists and closes this connection's session lease.
     */
    private final SessionLeaseService leaseService;
    /**
     * Policy used to bound session-lease behavior.
     */
    private final SessionLeasePolicy leasePolicy;
    /**
     * Reconciles eligible durable work after this connection is admitted.
     */
    private final ReconciliationService reconciliationService;
    /**
     * Validates provider manual attestation before authority-increasing calls.
     */
    private final ProviderManualService manualService;
    /**
     * Project root supplied by the launcher before MCP roots are resolved.
     */
    private final Path initialProjectRoot;
    /**
     * Whether the launcher explicitly pinned the initial project root.
     */
    private final boolean projectRootPinned;
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
     * Whether the launcher authenticated this process as managed continuity.
     */
    private final boolean managedAdmission;
    /**
     * Binding and generation authenticated for a managed process-local connection.
     */
    private final String managedBindingSessionId;
    /**
     * Authenticated generation of the managed attachment for this connection.
     */
    private final long managedAttachmentGeneration;
    /**
     * Project root selected from the initialized request or roots notification.
     */
    private Path activeProjectRoot;
    /**
     * Whether initialize has successfully bound this connection to a session.
     */
    private boolean isSessionBound;
    /**
     * Whether the one-time verified-admission reconciliation hook has run for this connection.
     */
    private boolean admissionReconciliationAttempted;
    /**
     * Fail-closed root-authority diagnostic set before provider admission.
     */
    private String projectRootAuthorityFailure;
    /**
     * Durable command anchor refreshed only after verified session activity.
     */
    private ProjectCommandProcessAnchor commandProcessAnchor;

    /**
     * Creates an MCP protocol handler.
     *
     * @param sessionService       application session service
     * @param projectRoot          canonical control project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection-instance ID
     */
    public McpProtocolHandler(AgentSessionService sessionService, Path projectRoot, String provider, String connectionInstanceId) {
        this(sessionService, projectRoot, provider, connectionInstanceId, captureProcessIdentity(connectionInstanceId), false, false);
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
        this(sessionService, projectRoot, provider, connectionInstanceId, captureProcessIdentity(connectionInstanceId), projectRootPinned, false);
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
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.readService = new WorkspaceReadService();
        this.patchService = new WorkspacePatchService();
        ProjectCommandService commandService = new ProjectCommandService();
        this.commandAdmissionService = new ProjectCommandAdmissionService(commandService, AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
        this.nextActionService = new AgentNextActionService();
        this.capabilityRequestService = new CapabilityRequestService();
        this.capabilityResponseService = new CapabilityResponseService();
        this.publicationService = new ImplementationPublicationService();
        this.validationService = new ImplementationValidationService();
        this.reviewValidationService = new ReviewValidationService();
        this.taskCompletionService = new AgentTaskCompletionService();
        this.taskCancellationService = new AgentTaskCancellationService();
        this.collaborationService = new WorkspaceCollaborationService();
        this.reviewSnapshotAccessService = new ReviewSnapshotAccessService();
        this.authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
        this.leaseService = new SessionLeaseService();
        this.leasePolicy = new SessionLeasePolicy();
        this.reconciliationService = new ReconciliationService();
        this.manualService = new ProviderManualService();
        this.initialProjectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.projectRootPinned = projectRootPinned;
        this.activeProjectRoot = projectRoot;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.connectionInstanceId = Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        this.commandProcessIdentity = Objects.requireNonNull(processIdentity, "processIdentity");
        this.managedAdmission = managedAdmission;
        if (managedAdmission && managedAttachment == null) {
            throw new IllegalArgumentException("managed attachment metadata required");
        }
        if (managedAttachment != null && !managedAdmission) {
            throw new IllegalArgumentException("managed attachment requires managed admission");
        }
        this.managedBindingSessionId = managedAttachment == null ? null : managedAttachment.bindingSessionId();
        this.managedAttachmentGeneration = managedAttachment == null ? 0L : managedAttachment.generation();
        if (managedAttachment != null && managedAttachment.status() != ManagedAttachmentRecord.Status.ACTIVE && managedAttachment.status() != ManagedAttachmentRecord.Status.PENDING_ACTIVATION) {
            throw new IllegalArgumentException("managed attachment is not transport-admissible");
        }
    }

    /**
     * Safely converts a URI string or local path string into a normalized absolute {@link Path}.
     *
     * @param input URI or local path string
     * @return normalized absolute Path, or {@code null} if empty/invalid
     */
    public static Path parseUriOrPath(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.startsWith("file:")) {
            try {
                return Path.of(URI.create(trimmed)).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // Not a usable file URI; fall back to treating the text as a path.
            }
        }

        try {
            return Path.of(URLDecoder.decode(trimmed, StandardCharsets.UTF_8)).toAbsolutePath().normalize();
        } catch (Exception invalid) {
            return null;
        }
    }

    /**
     * Captures stable process evidence and a unique incarnation for this MCP connection.
     *
     * @param connectionInstanceId caller-selected connection identifier
     * @return immutable process identity used by lease and shutdown fencing
     */
    private static SessionProcessIdentity captureProcessIdentity(String connectionInstanceId) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElse("unknown");
        String commandLine = info.commandLine().orElse(executable);
        long start = info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        return new SessionProcessIdentity(ProcessHandle.current().pid(), executable, commandLine, start, connectionInstanceId + ":" + UUID.randomUUID());
    }

    /**
     * Reads the bounded validation evidence accepted by the integration-check compatibility adapter.
     * Explicit structured results remain authoritative; the legacy provider text forms are accepted
     * only for an unambiguous passing count and are rejected when failure wording is present.
     *
     * @param check provider-supplied integration evidence
     * @return whether the supplied validation evidence is a pass
     */
    private static boolean testsPassed(Map<?, ?> check) {
        Object explicit = check.get("testsPassed");
        if (explicit instanceof Boolean value) {
            return value;
        }
        Object testResult = check.get("testResult");
        if (testResult instanceof Map<?, ?> result) {
            Object outcome = result.get("outcome");
            Object exitCode = result.get("exitCode");
            return "completed".equalsIgnoreCase(String.valueOf(outcome)) && exitCode instanceof Number number && number.intValue() == 0;
        }
        if (testResult instanceof String text && passingTestText(text)) {
            return true;
        }
        Object tests = check.get("tests");
        if (tests instanceof List<?> entries) {
            return entries.stream().anyMatch(entry -> entry instanceof String text && passingTestText(text)) && entries.stream()
                    .noneMatch(entry -> entry instanceof String text && text.toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b.*"));
        }
        return false;
    }

    /**
     * Accepts a legacy test-output string only when it reports passes without failure wording.
     *
     * @param text provider-supplied test output
     * @return whether the text unambiguously reports a passing run
     */
    private static boolean passingTestText(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*\\b\\d+\\s+passed\\b.*") && !normalized.matches("(?s).*\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b.*");
    }

    /**
     * Reads a nonblank string field from the nested task object when present.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param field     field name inside the {@code task} object
     * @return the field value, or {@code null} when absent, blank, or not a string
     */
    private static String taskField(Map<String, Object> arguments, String field) {
        if (arguments == null || !(arguments.get("task") instanceof Map<?, ?> taskMap)) {
            return null;
        }
        Object value = taskMap.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Reads the required nonblank finish outcome field.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return the outcome text
     * @throws IllegalArgumentException when the outcome is missing, blank, or not a string
     */
    private static String requiredStringArgument(Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("outcome") instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("invalid outcome");
        }
        return value;
    }

    /**
     * Reads an optional string argument and rejects values of any other JSON type.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @return the value, or {@code null} when absent or JSON null
     * @throws IllegalArgumentException when the value is not a string
     */
    private static String optionalStringArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return null;
        }
        if (!(arguments.get(key) instanceof String value)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    /**
     * Reads an optional boolean argument, using {@code false} when omitted.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @return the value, or {@code false} when absent or JSON null
     * @throws IllegalArgumentException when the value is not a boolean
     */
    @SuppressWarnings("SameParameterValue")
    private static boolean optionalBooleanArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return false;
        }
        if (!(arguments.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    /**
     * Parses an optional UUID string while preserving the argument name in validation errors.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @return the parsed UUID, or {@code null} when absent
     * @throws IllegalArgumentException when the value is not a string holding a UUID
     */
    private static UUID optionalUuidArgument(Map<String, Object> arguments, String key) {
        String value = optionalStringArgument(arguments, key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid " + key, invalid);
        }
    }

    /**
     * Reads an optional integral JSON number constrained by a caller-supplied minimum.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @param minimum   smallest accepted value
     * @return the value, or {@code null} when absent
     * @throws IllegalArgumentException when the value is not an integral number or is below {@code minimum}
     */
    private static Long optionalLongArgument(Map<String, Object> arguments, String key, long minimum) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return null;
        }
        Object raw = arguments.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        long value = number.longValue();
        if ((raw instanceof Double || raw instanceof Float) && number.doubleValue() != value) {
            throw new IllegalArgumentException("invalid " + key);
        }
        if (value < minimum) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    /**
     * Returns the first non-blank string under the canonical key or a bounded provider-shaped alias.
     * Aliases are input normalization only: the raw ten-tool catalog remains canonical and
     * authorization is unchanged.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param canonical canonical wire key
     * @param alias     provider-shaped spelling accepted at dispatch
     * @return selected value or {@code null}
     */
    private static String stringArgument(Map<String, Object> arguments, String canonical, String alias) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(canonical);
        if (!(value instanceof String string) || string.isBlank()) {
            value = arguments.get(alias);
        }
        return value instanceof String string && !string.isBlank() ? string : null;
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
            case TOOL_PREFIX + McpToolCatalog.APPLY_PATCH, TOOL_PREFIX + McpToolCatalog.RUN_COMMAND, TOOL_PREFIX + McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION, TOOL_PREFIX + McpToolCatalog.FINISH_LANE -> true;
            case TOOL_PREFIX + McpToolCatalog.ENSURE_SESSION ->
                    arguments != null && (Boolean.TRUE.equals(arguments.get("unwindCompletion")) || taskField(arguments, "repairIntentId") != null || taskField(arguments, "repairSnapshotId") != null);
            case TOOL_PREFIX + McpToolCatalog.REQUEST_COORDINATION -> {
                // Status/discovery is a safe read.  Contract, scope, handoff,
                // continuation, and join operations increase authority.
                String operation = arguments == null ? null : String.valueOf(arguments.get("collaborationOperation"));
                String kind = arguments == null ? null : String.valueOf(arguments.get("kind"));
                yield !("status".equals(operation) || "status".equals(arguments == null ? null : arguments.get("coordinationRequest"))) && (kind == null || !kind.isBlank() || operation == null || !operation.isBlank());
            }
            case TOOL_PREFIX + McpToolCatalog.RESPOND_COORDINATION -> arguments == null || arguments.get("kind") != null;
            default -> false;
        };
    }

    /**
     * Validates the strict coordination request schema and maps its operation to dispatch fields.
     *
     * @param arguments top-level request_coordination arguments
     * @return the payload fields plus the dispatch fields derived from the kind
     * @throws IllegalArgumentException when the kind is unknown or the payload has a missing, extra, or inconsistent field
     */
    @SuppressWarnings({"unchecked"})
    private static Map<String, Object> normalizeStrictCoordination(Map<String, Object> arguments) {
        Object kindValue = arguments.get("kind");
        Object payloadValue = arguments.get("payload");
        if (!(kindValue instanceof String kind) || !(payloadValue instanceof Map<?, ?> rawPayload)) {
            throw new IllegalArgumentException("COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD");
        }
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        List<String> allowed = switch (kind) {
            case "capability_request" -> List.of("capability", "contract", "capabilityRequestHandle", "revisionResponse", "ownerAuthorityLineageId");
            case "collaboration_status" -> List.of();
            case "contract_proposal" -> List.of("contractId", "body", "selectors", "revision");
            case "contract_request" -> List.of("conflictingIntentId", "proposal", "contractId", "revision");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal", "artifact");
            case "work_group_join" -> List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant", "proposal", "snapshotId", "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
            case "work_group_create" -> List.of("workGroupId", "goal", "acceptance", "reviewPolicy");
            case "continuation" -> payload.containsKey("grantId") ? List.of("grantId", "intentId", "claimEpoch") : List.of("intentId", "claimEpoch");
            default -> throw new IllegalArgumentException("UNKNOWN_COORDINATION_KIND");
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("COORDINATION_FIELD_NOT_ALLOWED:" + key);
            }
        }
        List<String> required = switch (kind) {
            case "capability_request" -> List.of("capability", "contract");
            case "contract_proposal" -> List.of("contractId", "body");
            case "contract_request" -> List.of("conflictingIntentId", "proposal");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal");
            case "work_group_join" -> payload.containsKey("grantId") ? List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant") : List.of("workGroupId", "intentId", "proposal");
            case "work_group_create" -> List.of("workGroupId", "goal", "acceptance");
            case "continuation" -> payload.containsKey("grantId") ? List.of("grantId", "intentId", "claimEpoch") : List.of("intentId", "claimEpoch");
            default -> List.of();
        };
        for (String key : required) {
            if (!payload.containsKey(key) || payload.get(key) == null) {
                throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:" + key);
            }
        }
        if ("work_group_join".equals(kind)) {
            requireMatchingAlias(payload, "intentId", "reviewedIntentId");
            requireMatchingAlias(payload, "targetParticipant", "reviewerParticipant");
            requireOptionalText(payload, "reviewedParticipantId");
            requireOptionalText(payload, "reviewerParticipant");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        normalized.remove("kind");
        normalized.put("collaborationOperation", switch (kind) {
            case "capability_request" -> "capability_request";
            case "collaboration_status" -> "status";
            case "contract_proposal" -> "publish";
            case "contract_request", "scope_revision" -> "request_coordination";
            case "handoff" -> "handoff";
            case "work_group_join" -> payload.containsKey("grantId") ? "lane_grant_consume" : "request_coordination";
            case "work_group_create" -> "work_group_create";
            default -> kind;
        });
        if ("contract_proposal".equals(kind)) {
            normalized.put("collaborationContractId", payload.get("contractId"));
            normalized.put("collaborationBody", payload.get("body"));
            normalized.put("collaborationSelectors", payload.getOrDefault("selectors", List.of()));
        }
        if ("capability_request".equals(kind)) {
            if (!(payload.get("capability") instanceof String capability) || capability.isBlank()) {
                throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:capability");
            }
            normalized.put("capability", capability);
        }
        if ("work_group_join".equals(kind)) {
            normalized.put("targetParticipant", payload.get("targetParticipant"));
            if (!payload.containsKey("grantId")) {
                normalized.put("collaborationIntentId", payload.get("intentId"));
                normalized.put("collaborationRequestKind", "REVIEW");
                normalized.put("collaborationProposal", payload.get("proposal"));
            }
        }
        if ("work_group_create".equals(kind)) {
            validateReviewPolicyPayload(payload.get("reviewPolicy"));
            normalized.put("collaborationGoal", payload.get("goal"));
            normalized.put("collaborationAcceptance", payload.get("acceptance"));
        }
        if ("contract_request".equals(kind)) {
            normalized.putIfAbsent("collaborationIntentId", payload.get("conflictingIntentId"));
            normalized.putIfAbsent("collaborationRequestKind", "CONTRACT");
            normalized.putIfAbsent("collaborationProposal", payload.getOrDefault("proposal", ""));
        }
        if ("scope_revision".equals(kind)) {
            normalized.putIfAbsent("collaborationIntentId", payload.get("intentId"));
            normalized.putIfAbsent("collaborationRequestKind", "CONTRACT");
            normalized.putIfAbsent("collaborationProposal", payload.getOrDefault("proposal", ""));
        }
        if ("handoff".equals(kind)) {
            normalized.putIfAbsent("collaborationIntentId", payload.get("intentId"));
            normalized.putIfAbsent("collaborationTarget", payload.get("targetParticipant"));
            normalized.putIfAbsent("collaborationProposal", payload.getOrDefault("proposal", ""));
        }
        if ("continuation".equals(kind)) {
            normalized.putIfAbsent("grantId", payload.get("grantId"));
            normalized.putIfAbsent("collaborationSourceIntentId", payload.get("intentId"));
            normalized.putIfAbsent("claimEpoch", payload.get("claimEpoch"));
        }
        return normalized;
    }

    /**
     * Validates the strict coordination-response schema before service invocation.
     *
     * @param arguments top-level respond_coordination arguments
     * @return the validated {@code kind} and {@code payload}
     * @throws IllegalArgumentException when the kind is unknown or the payload fails validation
     */
    @SuppressWarnings({"unchecked", "ExtractMethodRecommender"})
    private static Map<String, Object> normalizeStrictResponse(Map<String, Object> arguments) {
        Object kindValue = arguments.get("kind");
        Object payloadValue = arguments.get("payload");
        if (!(kindValue instanceof String kind) || !(payloadValue instanceof Map<?, ?> rawPayload)) {
            throw new IllegalArgumentException("COORDINATION_RESPONSE_REQUIRES_KIND_AND_PAYLOAD");
        }
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        List<String> allowed = switch (kind) {
            case "capability_response" -> List.of("capabilityRequestHandle", "response", "revision", "reason");
            case "coordination_response" -> List.of("coordinationRequest", "coordinationStatus", "proposal");
            case "inbox_acknowledge" -> List.of("inboxItemId");
            case "inbox_resolve" -> List.of("inboxItemId", "resolution", "proposal");
            case "implementation_validation" -> List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result", "reason", "failedAcceptanceTests");
            case "review_validation" -> List.of("grantId", "snapshotId", "intentId", "claimEpoch", "result", "reason", "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
            default -> throw new IllegalArgumentException("UNKNOWN_COORDINATION_RESPONSE_KIND");
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:" + key);
            }
        }
        List<String> required = switch (kind) {
            case "capability_response" -> List.of("capabilityRequestHandle", "response");
            case "coordination_response" -> List.of("coordinationRequest", "coordinationStatus");
            case "inbox_acknowledge" -> List.of("inboxItemId");
            case "inbox_resolve" -> List.of("inboxItemId", "resolution");
            case "implementation_validation" -> List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result");
            case "review_validation" -> List.of("grantId", "snapshotId", "intentId", "claimEpoch", "result");
            default -> List.of();
        };
        for (String key : required) {
            if (!payload.containsKey(key)) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_FIELD_REQUIRED:" + key);
            }
        }
        if ("review_validation".equals(kind)) {
            requireMatchingAlias(payload, "intentId", "reviewedIntentId");
            requireOptionalText(payload, "reviewedParticipantId");
            requireOptionalText(payload, "reviewerParticipant");
        }
        if ("capability_response".equals(kind)) {
            Object response = payload.get("response");
            if (!(response instanceof String value) || !("accept".equals(value) || "revise".equals(value) || "reject".equals(value))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESPONSE");
            }
            if ("revise".equals(response) && !(payload.get("revision") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_REVISION_REQUIRED");
            }
        }
        if ("implementation_validation".equals(kind)) {
            Object result = payload.get("result");
            if (!(result instanceof String value) || !("accepted".equals(value) || "revision_required".equals(value))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESULT");
            }
            if ("revision_required".equals(result)) {
                Object reason = payload.get("reason");
                if (!(reason instanceof String reasonText) || reasonText.isBlank()) {
                    throw new IllegalArgumentException("COORDINATION_RESPONSE_REASON_REQUIRED");
                }
            }
        }
        if ("review_validation".equals(kind)) {
            Object result = payload.get("result");
            if (!(result instanceof String reviewResult) || !("accept".equalsIgnoreCase(reviewResult) || "accepted".equalsIgnoreCase(reviewResult) || "reject".equalsIgnoreCase(reviewResult)
                    || "rejected".equalsIgnoreCase(reviewResult))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESULT");
            }
            if (("reject".equalsIgnoreCase(String.valueOf(result)) || "rejected".equalsIgnoreCase(String.valueOf(result))) && (!(payload.get("reason") instanceof String reason) || reason.isBlank())) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_REASON_REQUIRED");
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("kind", kind);
        normalized.put("payload", payload);
        return normalized;
    }

    /**
     * Ensures a supplied provider-facing alias exactly matches its canonical field.
     *
     * @param payload   request payload
     * @param canonical canonical field name
     * @param alias     provider-facing alias
     * @throws IllegalArgumentException when both fields are present and differ or either is blank
     */
    private static void requireMatchingAlias(Map<String, Object> payload, String canonical, String alias) {
        if (!payload.containsKey(alias) || !payload.containsKey(canonical)) {
            return;
        }
        Object canonicalValue = payload.get(canonical);
        Object aliasValue = payload.get(alias);
        if (!(canonicalValue instanceof String canonicalText) || canonicalText.isBlank() || !(aliasValue instanceof String aliasText) || aliasText.isBlank() || !canonicalText.equals(aliasText)) {
            throw new IllegalArgumentException("COORDINATION_ALIAS_MISMATCH:" + alias);
        }
    }

    /**
     * Validates that an optional payload field, when present, is nonblank text.
     *
     * @param payload request payload
     * @param key     field name
     * @throws IllegalArgumentException when the field is present but not nonblank text
     */
    private static void requireOptionalText(Map<String, Object> payload, String key) {
        if (payload.containsKey(key) && (!(payload.get(key) instanceof String value) || value.isBlank())) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + key);
        }
    }

    /**
     * Keeps the string entries of a JSON array, silently dropping everything else.
     *
     * @param value untrusted value, possibly {@code null}
     * @return the string entries, empty when the value is not an array
     */
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * Decodes an optional JSON string array without silently dropping malformed entries.
     *
     * @param value untrusted value from a JSON object
     * @param field field name used in validation errors
     * @return {@code null} when absent, otherwise an immutable list of strings
     * @throws IllegalArgumentException when the value is not a string array
     */
    private static List<String> nullableStringList(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> strings = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String text)) {
                throw new IllegalArgumentException(field + " entries must be strings");
            }
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    /**
     * Returns whether a parsed JSON number has an integral representation.
     *
     * @param number parsed JSON number
     * @return {@code true} for a non-floating-point number whose value is exactly integral
     */
    private static boolean isStrictInteger(Number number) {
        if (number instanceof Double || number instanceof Float) {
            return false;
        }
        long value = number.longValue();
        return number.doubleValue() == value;
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
     * Parses the bounded structured capability identifiers supplied at admission.
     *
     * @param rawDependencies raw task field
     * @return immutable dependency identifiers
     * @throws IllegalArgumentException when the field is not a bounded string list
     */
    private static List<String> parseKnownDependencies(Object rawDependencies) {
        if (rawDependencies == null) {
            return List.of();
        }
        if (!(rawDependencies instanceof List<?> entries)) {
            throw new IllegalArgumentException("knownDependencies must be an array");
        }
        if (entries.size() > MAX_KNOWN_DEPENDENCIES) {
            throw new IllegalArgumentException("knownDependencies exceeds 50 items");
        }
        List<String> dependencies = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String dependency) || dependency.isBlank() || dependency.length() > MAX_DEPENDENCY_LENGTH) {
                throw new IllegalArgumentException("knownDependencies entries must be bounded strings");
            }
            dependencies.add(dependency);
        }
        return List.copyOf(dependencies);
    }

    /**
     * Returns the currently resolved active control project root path.
     *
     * @return active control project root path
     */
    public Path activeProjectRoot() {
        return activeProjectRoot;
    }

    /**
     * Renews the exact session lease after verified MCP activity.
     *
     * @return {@code true} when the lease was renewed, {@code false} for an unbound connection
     */
    private boolean renewLease() {
        try {
            renewLeaseAndHeartbeat();
            return true;
        } catch (Exception ignored) {
            // Unbound requests are handled by the session and workspace policy paths.
            return false;
        }
    }

    /**
     * Runs the existing durable recovery plan once after this exact connection has been admitted and
     * its own lease has been renewed. Process absence and the configured grace period remain the only
     * recovery trigger; this hook does not transfer claims or issue continuation authority.
     */
    private void reconcileAfterAdmission() {
        if (admissionReconciliationAttempted) {
            return;
        }
        admissionReconciliationAttempted = true;
        try {
            reconciliationService.reconcileOnAdmission(activeProjectRoot);
        } catch (Exception failure) {
            System.err.println("SYNESIS_MCP_RECONCILIATION_FAILURE=" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    /**
     * Marks a clean stdio shutdown and detaches this connection's lane.
     */
    public void close() {
        try {
            leaseService.markClosedCleanly(activeProjectRoot, connectionInstanceId, commandProcessIdentity.pid());
            collaborationService.detach(activeProjectRoot, provider, connectionInstanceId);
        } catch (Exception ignored) {
            // Recovery reconciles unclean or partially completed shutdowns.
        }
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
            leaseService.markTerminalDisconnected(activeProjectRoot, connectionInstanceId, commandProcessIdentity.pid());
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
                case "notifications/roots/list_changed", "roots/list_changed" -> handleRootsListChanged(request.get("params"));
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, request.get("params"));
                case "roots/list" -> handleRootsList(id);
                default -> createErrorResponse(id, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (Throwable failure) {
            System.err.println("[synesis-mcp] Error handling method " + method + ": " + failure);
            failure.printStackTrace(System.err);
            return createErrorResponse(id, INTERNAL_ERROR, "Internal error: " + failure.getMessage());
        }
    }

    /**
     * Applies an early roots notification only while this connection remains unbound.
     *
     * @param paramsObj notification params, possibly {@code null}
     * @return always {@code null}; notifications get no response
     */
    @SuppressWarnings("unchecked")
    private String handleRootsListChanged(Object paramsObj) {
        if (!isSessionBound && paramsObj instanceof Map<?, ?> pMap) {
            List<Path> candidates = extractCandidateRoots((Map<String, Object>) pMap);
            Path resolved = resolveProjectRootFromCandidates(candidates);
            if (resolved != null) {
                this.activeProjectRoot = resolved;
            }
        }
        return null;
    }

    /**
     * Creates the initialize response and resolves the initial project root before session binding.
     *
     * @param id     JSON-RPC request identifier
     * @param params initialize params, possibly {@code null}
     * @return the initialize result response
     */
    private String handleInitialize(Object id, Map<String, Object> params) {
        if (!isSessionBound) {
            // Primary: extract from MCP params (rootUri, workspaceFolders, roots)
            List<Path> paramCandidates = extractCandidateRoots(params);
            Path resolved = resolveProjectRootFromCandidates(paramCandidates);

            // Fallback: scan supported environment variables when params yield nothing
            if (resolved == null) {
                List<Path> fallbackCandidates = extractFallbackCandidates();
                resolved = resolveProjectRootFromCandidates(fallbackCandidates);
            }

            if (resolved != null) {
                this.activeProjectRoot = resolved;
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
     * Returns the one project root currently selected for this connection.
     *
     * @param id JSON-RPC request identifier
     * @return the roots/list result response
     */
    private String handleRootsList(Object id) {
        Map<String, Object> rootItem = new LinkedHashMap<>();
        rootItem.put("uri", activeProjectRoot.toUri().toString());
        rootItem.put("name", activeProjectRoot.getFileName() != null ? activeProjectRoot.getFileName().toString() : "root");

        Map<String, Object> result = Map.of("roots", List.of(rootItem));
        return createResultResponse(id, result);
    }

    /**
     * Reads the current managed attachment fence for this exact process.
     *
     * <p>The startup proof authenticates the process-local launch. The durable
     * status is intentionally consulted again for every tool call so a pending connection cannot
     * create coordination state and the same connection becomes eligible only after trusted lifecycle
     * activation.</p>
     *
     * @param toolName internal dispatch name of the requested tool
     * @return current managed admission state
     */
    private ManagedAdmissionState managedAdmissionState(String toolName) {
        if (!managedAdmission) {
            return ManagedAdmissionState.ACTIVE;
        }
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            var binding = authorityResolver.resolveReview(location, provider, connectionInstanceId);
            if (!managedBindingSessionId.equals(binding.sessionId())) {
                return ManagedAdmissionState.REJECTED;
            }
            boolean completed = "COMPLETED".equalsIgnoreCase(binding.status());
            if (completed && !completedReviewTool(toolName)) {
                return ManagedAdmissionState.REJECTED;
            }
            ManagedAttachmentRecord record = ManagedAttachmentService.storeFor(location, managedBindingSessionId).read().orElse(null);
            if (record == null || record.mode() != ProviderContinuityMode.MANAGED_CONTINUITY || record.generation() != managedAttachmentGeneration || !provider.equals(record.provider())) {
                return ManagedAdmissionState.REJECTED;
            }
            if (completed) {
                return ManagedAdmissionState.REVIEW_ONLY;
            }
            return switch (record.status()) {
                case PENDING_ACTIVATION -> ManagedAdmissionState.PENDING;
                case ACTIVE -> ManagedAdmissionState.ACTIVE;
                case DISCONNECTED, TERMINAL -> ManagedAdmissionState.REJECTED;
            };
        } catch (Exception rejected) {
            return ManagedAdmissionState.REJECTED;
        }
    }

    /**
     * Creates the deterministic response for a quarantined or fenced managed transport without
     * invoking any application mutation service.
     *
     * @param id    JSON-RPC request identifier
     * @param state the non-admissible state, {@code PENDING} or {@code REJECTED}
     * @return serialized error result
     */
    private String managedAdmissionResponse(Object id, ManagedAdmissionState state) {
        String reason = state == ManagedAdmissionState.PENDING ? "managed_attachment_pending" : "managed_attachment_rejected";
        Map<String, Object> text = Map.of("status", "blocked", "reason", reason);
        return createResultResponse(id, Map.of("content", List.of(Map.of("type", "text", "text", ProviderJson.write(text))), "isError", true));
    }

    /**
     * Extracts candidate workspace project root paths from MCP {@code initialize} request parameters
     * only. Does not include environment variables, file system scans, or process working directory.
     *
     * @param params JSON-RPC initialize request params map, or {@code null}
     * @return list of parsed candidate paths from protocol params
     */
    public List<Path> extractCandidateRoots(Map<String, Object> params) {
        List<Path> candidates = new ArrayList<>();
        if (params == null) {
            return candidates;
        }

        if (params.get("rootUri") instanceof String rootUriStr) {
            Path p = parseUriOrPath(rootUriStr);
            if (p != null) {
                candidates.add(p);
            }
        }

        if (params.get("workspaceFolders") instanceof List<?> folders) {
            addUris(folders, candidates);
        }

        if (params.get("roots") instanceof List<?> rootsList) {
            addUris(rootsList, candidates);
        }

        return candidates;
    }

    /**
     * Adds the parseable, not yet present {@code uri} of each folder or root entry.
     *
     * @param entries    JSON array of objects that may carry a {@code uri} string
     * @param candidates list receiving the parsed paths
     */
    private static void addUris(List<?> entries, List<Path> candidates) {
        for (Object item : entries) {
            if (item instanceof Map<?, ?> map && map.get("uri") instanceof String uriStr) {
                Path p = parseUriOrPath(uriStr);
                if (p != null && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
        }
    }

    /**
     * Collects fallback candidate project roots from supported environment variables, the explicit
     * {@code --project} argument, and the process working directory. Only called when MCP
     * {@code initialize} params yield no initialized root.
     *
     * @return list of fallback candidate paths
     */
    List<Path> extractFallbackCandidates() {
        if (Files.exists(initialProjectRoot.resolve(PROJECT_METADATA))) {
            return List.of(initialProjectRoot.toAbsolutePath().normalize());
        }
        List<Path> candidates = new ArrayList<>();

        // Environment variables
        for (String envKey : FALLBACK_ROOT_ENVIRONMENT_KEYS) {
            String envVal = System.getenv(envKey);
            if (envVal != null && !envVal.isBlank()) {
                Path p = parseUriOrPath(envVal);
                if (p != null && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
        }

        // Explicit initial project root (e.g. --project argument)
        if (!candidates.contains(initialProjectRoot)) {
            candidates.add(initialProjectRoot.toAbsolutePath().normalize());
        }

        // Current working directory (last resort)
        try {
            Path cwd = Path.of(".").toAbsolutePath().normalize();
            if (!candidates.contains(cwd)) {
                candidates.add(cwd);
            }
        } catch (Exception ignored) {
            // No usable working directory; the other candidates stand.
        }

        return candidates;
    }

    /**
     * Resolves the single initialized Synesis control project root from candidate paths.
     *
     * @param candidates candidate workspace paths
     * @return resolved control project root, or {@code null} if none or ambiguous
     */
    public Path resolveProjectRootFromCandidates(List<Path> candidates) {
        if (projectRootAuthorityFailure != null) {
            return activeProjectRoot;
        }
        String userHome = System.getProperty("user.home");
        Path homePath = (userHome != null && !userHome.isBlank()) ? Path.of(userHome).toAbsolutePath().normalize() : null;
        if (projectRootPinned) {
            McpProjectRootAuthority.Selection selection = McpProjectRootAuthority.select(initialProjectRoot, candidates, homePath);
            if (selection.rejected()) {
                projectRootAuthorityFailure = selection.rejection();
                System.err.println("SYNESIS_DIAGNOSTIC=" + selection.rejection());
                return initialProjectRoot;
            }
            return selection.root();
        }

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        List<Path> initializedRoots = new ArrayList<>();

        for (Path candidate : candidates) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (McpProjectRootAuthority.isAssignedWorkspace(normalized)) {
                    projectRootAuthorityFailure = "PROJECT_ROOT_ASSIGNED_WORKTREE_REJECTED";
                    System.err.println("SYNESIS_DIAGNOSTIC=" + projectRootAuthorityFailure);
                    return activeProjectRoot;
                }
                if (normalized.equals(homePath)) {
                    continue; // Reject user home directory as control project root
                }
                Path mainCheckout = McpProjectRootAuthority.resolveMainCheckout(normalized);
                if (mainCheckout != null && !initializedRoots.contains(mainCheckout)) {
                    initializedRoots.add(mainCheckout);
                }
            } catch (Exception ignored) {
                // An unreadable candidate is skipped; the others decide the root.
            }
        }

        if (initializedRoots.size() == 1) {
            projectRootAuthorityFailure = null;
            return initializedRoots.getFirst();
        }

        if (initializedRoots.size() > 1) {
            System.err.println("SYNESIS_DIAGNOSTIC=PROJECT_ROOT_AMBIGUOUS count=" + initializedRoots.size());
            projectRootAuthorityFailure = "PROJECT_ROOT_AMBIGUOUS";
            return activeProjectRoot;
        }

        return null;
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
     * Admits, executes, and records one command under the refreshed session authority fence.
     *
     * @param request   validated command request
     * @param requestId JSON-RPC request identifier
     * @return the command outcome, or a retry or blocked response when authority cannot be verified
     */
    private AgentResponse runDurableCommand(ProjectCommandService.CommandRequest request, Object requestId) {
        if (projectRootAuthorityFailure != null) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, Map.of("error", projectRootAuthorityFailure));
        }
        ProjectLocation location;
        ReadinessResult readiness;
        try {
            location = new ProjectApplicationService().locate(activeProjectRoot);
            readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, Map.of("error", "WORKSPACE_UNVERIFIED"));
        }
        if (!readiness.ready()) {
            return readiness.response();
        }

        PhysicalWorktreeIdentity worktree;
        try {
            worktree = PhysicalWorktreeIdentity.capture(activeProjectRoot, readiness.worktree(), new LifecyclePathVerifier());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_STALE, AgentNextAction.ENSURE_SESSION, Map.of("error", "WORKTREE_IDENTITY_UNVERIFIED"));
        }
        if (commandProcessAnchor == null) {
            commandProcessAnchor = ProjectCommandProcessAnchor.capture(worktree.locator(), commandProcessIdentity, Instant.now().toEpochMilli());
        } else if (!commandProcessAnchor.scopeLocator().equals(worktree.locator())) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COMMAND_ADMISSION_STALE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "MCP_PROCESS_SCOPE_CHANGED"));
        }
        ProjectCommandAuthoritySnapshot before = captureAuthoritySnapshot(readiness, worktree);
        return commandAdmissionService.execute(request, requestId, commandProcessAnchor, worktree, before, () -> {
            try {
                return renewLeaseForCommand(worktree);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Re-arms command admission only after a successful session resolution has verified a different
     * isolated worktree. A provider connection may move from a stale clean worktree to a recovery
     * worktree, while the immutable MCP process identity remains attached to the same connection.
     */
    private void refreshCommandProcessAnchorForVerifiedSession() {
        if (commandProcessAnchor == null) {
            return;
        }
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            ReadinessResult readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
            if (!readiness.ready()) {
                return;
            }
            PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(activeProjectRoot, readiness.worktree(), new LifecyclePathVerifier());
            if (!commandProcessAnchor.scopeLocator().equals(worktree.locator())) {
                commandProcessAnchor = null;
            }
        } catch (Exception ignored) {
            // Preserve fail-closed command admission when the new scope cannot
            // be independently verified after session resolution.
        }
    }

    /**
     * Resolves the exact bound session, renews its lease, and heartbeats its collaboration lane.
     *
     * @return the located project
     * @throws Exception when the project, binding, identity, or lease cannot be resolved or persisted
     */
    private ProjectLocation renewLeaseAndHeartbeat() throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
        var binding = authorityResolver.resolve(location, provider, connectionInstanceId);
        String nodeId = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
        leaseService.createOrRenewLease(activeProjectRoot, location.projectId().toString(), provider, connectionInstanceId, nodeId, binding.sessionId(), leasePolicy);
        collaborationService.heartbeatIfPresent(activeProjectRoot, provider, connectionInstanceId);
        return location;
    }

    /**
     * Renews the connection lease and captures the authority snapshot required for command admission.
     *
     * @param worktree verified physical worktree identity
     * @return authority snapshot taken after the renewal
     * @throws IllegalArgumentException when the lease cannot be renewed or the workspace is no longer ready
     */
    private ProjectCommandAuthoritySnapshot renewLeaseForCommand(PhysicalWorktreeIdentity worktree) throws Exception {
        ProjectLocation location = renewLeaseAndHeartbeat();
        ReadinessResult readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
        if (!readiness.ready()) {
            throw new IllegalStateException("COMMAND_AUTHORITY_REFRESH_FAILED");
        }
        return captureAuthoritySnapshot(readiness, worktree);
    }

    /**
     * Captures the current readiness, worktree identity, and exact lease for durable command checks.
     *
     * @param readiness current workspace readiness
     * @param worktree  verified physical worktree identity
     * @return snapshot used to fence the command
     */
    private ProjectCommandAuthoritySnapshot captureAuthoritySnapshot(ReadinessResult readiness, PhysicalWorktreeIdentity worktree) {
        SessionLeaseRecord lease = new SessionLeaseStore().load(activeProjectRoot, connectionInstanceId).orElse(null);
        return ProjectCommandAuthoritySnapshot.capture(readiness.binding(), worktree, lease, "none");
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

        if (projectRootAuthorityFailure != null) {
            AgentResponse mismatch = new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, Map.of("error", projectRootAuthorityFailure));
            return textResultResponse(id, mismatch.toJson());
        }

        ManagedAdmissionState managedState = managedAdmissionState(name);
        if (managedState != ManagedAdmissionState.ACTIVE && managedState != ManagedAdmissionState.REVIEW_ONLY) {
            return managedAdmissionResponse(id, managedState);
        }

        AgentResponse agentResponse;
        boolean durableCommand = name.equals(TOOL_PREFIX + McpToolCatalog.RUN_COMMAND);
        boolean noChangeCompletion = name.equals(TOOL_PREFIX + McpToolCatalog.FINISH_LANE) && arguments != null && arguments.get("outcome") instanceof String outcome && "no_change".equalsIgnoreCase(outcome);
        boolean snapshotCompletion = name.equals(TOOL_PREFIX + McpToolCatalog.FINISH_LANE);
        // finish_lane carries an optimistic event-log revision in its
        // projected evidence.  The ordinary lease heartbeat appends a
        // collaboration event, so doing it before completion would invalidate
        // the exact server-issued finish envelope before the completion
        // service can consume it.  Completion itself remains authoritative;
        // all other non-command calls retain the normal activity heartbeat.
        if (managedState != ManagedAdmissionState.REVIEW_ONLY && !durableCommand && !noChangeCompletion && !snapshotCompletion) {
            renewLease();
        }

        if (requiresManualAttestation(name, arguments)) {
            var attestation = manualService.attest(provider);
            if (!attestation.valid()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("manual", attestation.reason());
                details.put("authorityReductionAllowed", true);
                return textResultResponse(id, "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\",\"details\":" + ProviderJson.write(details) + "}");
            }
        }

        switch (name) {
            case TOOL_PREFIX + McpToolCatalog.ENSURE_SESSION -> {
                AgentTaskIntent taskIntent;
                try {
                    taskIntent = parseTaskIntent(arguments);
                } catch (IllegalArgumentException invalidTask) {
                    String reason = "TASK_FIELDS_MUST_BE_NESTED".equals(invalidTask.getMessage()) ? "TASK_FIELDS_MUST_BE_NESTED" : "INVALID_TASK_INTENT";
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", reason));
                    break;
                }
                boolean refresh = arguments != null && Boolean.TRUE.equals(arguments.get("refresh"));

                if (!parseClaimSelectors(arguments).isEmpty() && !manualService.attest(provider).valid()) {
                    return textResultResponse(id, "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\",\"claims\":\"REJECTED\"}");
                }

                SessionResolutionRequest resolutionRequest = new SessionResolutionRequest(activeProjectRoot, provider, connectionInstanceId, taskIntent, refresh, managedAdmission);

                agentResponse = sessionService.ensureSession(resolutionRequest);
                if (agentResponse.status() == AgentStatus.READY) {
                    isSessionBound = true;
                    refreshCommandProcessAnchorForVerifiedSession();
                    // The first verified ensure_session is activity too. Establish the lease
                    // before any claim is announced so an abruptly deleted chat remains
                    // recoverable even when it sends no follow-up MCP request.
                    if (renewLease()) {
                        reconcileAfterAdmission();
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
                    } else if (!selectors.isEmpty() && !recoveryAdmission) {
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
                                    taskIntent == null ? List.of() : taskIntent.knownDependencies());
                            if (!claimResult.acquired()) {
                                Map<String, Object> details = new LinkedHashMap<>();
                                details.put("conflicts",
                                        claimResult.conflicts().stream().map(conflict -> Map.of("participant",
                                                conflict.participant(),
                                                "intent",
                                                conflict.intentId(),
                                                "kind",
                                                conflict.selector().kind().name(),
                                                "path",
                                                conflict.selector().value())).toList());
                                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_HUMAN_HELP, details);
                            }
                        } catch (Exception failure) {
                            if ("SESSION_EPOCH_FENCED".equals(failure.getMessage())) {
                                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_GENERATION_CHANGED, AgentNextAction.RETRY, null);
                            } else {
                                agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
                            }
                        }
                    }
                }
            }
            case TOOL_PREFIX + McpToolCatalog.READ_FILE -> {
                String path = stringArgument(arguments, "path", "relativePath");
                Integer startLine = (arguments != null && arguments.get("startLine") instanceof Number n) ? n.intValue() : null;
                Integer endLine = (arguments != null && arguments.get("endLine") instanceof Number n) ? n.intValue() : null;
                Integer maxBytes = (arguments != null && arguments.get("maxBytes") instanceof Number n) ? n.intValue() : null;

                ReadRequest readReq = new ReadRequest(activeProjectRoot, provider, connectionInstanceId, path, startLine, endLine, maxBytes);
                agentResponse = readService.readFile(readReq);
            }
            case TOOL_PREFIX + McpToolCatalog.APPLY_PATCH -> {
                String path = stringArgument(arguments, "path", "relativePath");
                boolean create = arguments != null && Boolean.TRUE.equals(arguments.get("create"));
                String content = stringArgument(arguments, "content", "newContent");
                String expectedHash = stringArgument(arguments, "expectedHash", "expectedRevision");

                List<PatchEdit> patchEdits = new ArrayList<>();
                if (arguments != null && arguments.get("edits") instanceof List<?> editsList) {
                    for (Object item : editsList) {
                        if (item instanceof Map<?, ?> editMap) {
                            String find = (String) editMap.get("find");
                            String replace = (String) editMap.get("replace");
                            int expectedOccurrences = (editMap.get("expectedOccurrences") instanceof Number n) ? n.intValue() : 0;
                            if (find != null && replace != null && expectedOccurrences >= 1) {
                                patchEdits.add(new PatchEdit(find, replace, expectedOccurrences));
                            }
                        }
                    }
                }

                PatchRequest patchReq = new PatchRequest(activeProjectRoot, provider, connectionInstanceId, path, create, content, expectedHash, patchEdits);
                agentResponse = patchService.applyPatch(patchReq);
            }
            case TOOL_PREFIX + McpToolCatalog.RUN_COMMAND -> {
                List<String> argv = new ArrayList<>();
                boolean unsupportedField = arguments != null && arguments.keySet().stream().anyMatch(key -> !Set.of("argv", "workingDirectory", "timeoutSeconds").contains(key));
                if (arguments != null && arguments.get("argv") instanceof List<?> list) {
                    for (Object item : list) {
                        if (!(item instanceof String s)) {
                            argv = null;
                            break;
                        }
                        argv.add(s);
                    }
                } else {
                    argv = null;
                }
                boolean malformedWorkingDirectory = arguments != null && arguments.containsKey("workingDirectory") && !(arguments.get("workingDirectory") instanceof String);
                String workingDirectory = arguments != null && arguments.get("workingDirectory") instanceof String value ? value : ".";
                Integer timeoutSeconds = null;
                boolean malformedTimeout = false;
                if (arguments != null && arguments.containsKey("timeoutSeconds")) {
                    Object rawTimeout = arguments.get("timeoutSeconds");
                    if (rawTimeout instanceof Number number && isStrictInteger(number) && number.longValue() >= 1 && number.longValue() <= MAX_COMMAND_TIMEOUT_SECONDS) {
                        timeoutSeconds = number.intValue();
                    } else {
                        malformedTimeout = true;
                    }
                }
                if (argv == null || argv.isEmpty() || malformedWorkingDirectory || malformedTimeout || unsupportedField) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    try {
                        AgentResponse reviewCommand = reviewSnapshotAccessService.runReviewCommand(activeProjectRoot, provider, connectionInstanceId, argv, workingDirectory, timeoutSeconds);
                        if (reviewCommand != null) {
                            agentResponse = reviewCommand;
                        } else {
                            ProjectCommandService.CommandRequest cmdReq = new ProjectCommandService.CommandRequest(activeProjectRoot, provider, connectionInstanceId, argv, workingDirectory, timeoutSeconds);
                            agentResponse = runDurableCommand(cmdReq, id);
                        }
                    } catch (IllegalArgumentException ex) {
                        agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                    }
                }
            }
            case TOOL_PREFIX + McpToolCatalog.GET_NEXT_ACTION -> {
                boolean completionRequested;
                try {
                    completionRequested = optionalBooleanArgument(arguments, "completionRequested");
                } catch (IllegalArgumentException invalidCompletionRequest) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("reason", "INVALID_COMPLETION_REQUEST"));
                    break;
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
                    NextActionRequest nextReq = new NextActionRequest(activeProjectRoot, provider, connectionInstanceId, completionRequested);
                    agentResponse = nextActionService.getNextAction(nextReq);
                }
            }
            case TOOL_PREFIX + McpToolCatalog.REQUEST_COORDINATION -> {
                if (arguments == null || !arguments.containsKey("kind")) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
                    break;
                }
                try {
                    arguments = normalizeStrictCoordination(arguments);
                } catch (IllegalArgumentException failure) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
                    break;
                }
                String collaborationOperation = (String) arguments.get("collaborationOperation");
                if (collaborationOperation != null) {
                    try {
                        if ("capability_request".equals(collaborationOperation)) {
                            CapabilityContract contract = parseContract(arguments.get("contract"));
                            agentResponse = capabilityRequestService.describeRequiredCapability(new CapabilityRequestService.DescribeCapabilityRequest(activeProjectRoot,
                                    provider,
                                    connectionInstanceId,
                                    String.valueOf(arguments.get("capability")),
                                    contract,
                                    arguments.get("capabilityRequestHandle") instanceof String value ? value : null,
                                    arguments.get("revisionResponse") instanceof String value ? value : null,
                                    arguments.get("ownerAuthorityLineageId") instanceof String value ? UUID.fromString(value) : null));
                            break;
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
                            case "bind" -> {
                                UUID intentId = UUID.fromString(String.valueOf(arguments.get("collaborationIntentId")));
                                UUID contractId = UUID.fromString(String.valueOf(arguments.get("collaborationContractId")));
                                long revision = ((Number) arguments.get("collaborationRevision")).longValue();
                                collaborationService.bindContract(activeProjectRoot, provider, connectionInstanceId, intentId, contractId, revision);
                                yield Map.of("intent", intentId.toString(), "contract", contractId.toString(), "revision", revision);
                            }
                            case "request", "request_coordination" -> {
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
                            case "lane_grant_issue" -> {
                                UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                                UUID groupId = UUID.fromString(String.valueOf(arguments.get("workGroupId")));
                                UUID intentId = UUID.fromString(String.valueOf(arguments.get("intentId")));
                                LaneGrant grant = new LaneGrant(grantId,
                                        groupId,
                                        intentId,
                                        String.valueOf(arguments.get("targetParticipant")),
                                        ((Number) arguments.getOrDefault("claimEpoch", 1)).longValue(),
                                        !Boolean.FALSE.equals(arguments.get("singleUse")));
                                collaborationService.issueLaneGrant(activeProjectRoot, grant);
                                yield Map.of("grantId", grantId.toString(), "status", "ISSUED");
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
                            case "lane_grant_revoke" -> {
                                UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                                collaborationService.revokeLaneGrant(activeProjectRoot, grantId);
                                yield Map.of("grantId", grantId.toString(), "status", "REVOKED");
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
                            case "work_group_close" -> {
                                UUID groupId = UUID.fromString(String.valueOf(arguments.get("workGroupId")));
                                WorkGroup.Status status = WorkGroup.Status.valueOf(String.valueOf(arguments.getOrDefault("groupStatus", "COMPLETED")).toUpperCase(Locale.ROOT));
                                long version = ((Number) arguments.getOrDefault("groupVersion", 1)).longValue();
                                collaborationService.closeWorkGroup(activeProjectRoot, groupId, status, version);
                                yield Map.of("workGroupId", groupId.toString(), "status", status.name());
                            }
                            default -> throw new IllegalArgumentException("unknown collaboration operation");
                        };
                        agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null, result);
                        break;
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of("error", failure.getMessage()));
                        break;
                    }
                }
                throw new IllegalStateException("strict coordination request did not produce an operation");
            }
            case TOOL_PREFIX + McpToolCatalog.RESPOND_COORDINATION -> {
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
                            case "capability_response" -> agentResponse = capabilityResponseService.respondToOwnerRequest(new CapabilityResponseService.OwnerResponseRequest(activeProjectRoot,
                                    provider,
                                    connectionInstanceId,
                                    String.valueOf(payload.get("capabilityRequestHandle")),
                                    String.valueOf(payload.get("response")),
                                    parseContract(payload.get("revision")),
                                    payload.get("reason") == null ? null : String.valueOf(payload.get("reason"))));
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
                        break;
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
                        break;
                    }
                }
                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
            }
            case TOOL_PREFIX + McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION -> {
                String reqHandle = arguments != null ? (String) arguments.get("capabilityRequestHandle") : null;
                String summary = arguments != null ? (String) arguments.get("summary") : null;

                if (reqHandle == null || reqHandle.isBlank()) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    ImplementationPublicationService.PublishRequest pubReq = new ImplementationPublicationService.PublishRequest(activeProjectRoot, provider, connectionInstanceId, reqHandle, summary);
                    agentResponse = publicationService.publishImplementation(pubReq);
                }
            }
            case TOOL_PREFIX + McpToolCatalog.FINISH_LANE -> {
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
            }
            case TOOL_PREFIX + McpToolCatalog.CANCEL_LANE -> {
                String reason = arguments != null ? (String) arguments.get("reason") : null;
                CancelTaskRequest cancelReq = new CancelTaskRequest(activeProjectRoot, provider, connectionInstanceId, reason);
                agentResponse = taskCancellationService.cancelTask(cancelReq);
            }
            default -> {
                Map<String, Object> textContent = Map.of("type", "text", "text", "Unknown tool: " + name);
                Map<String, Object> result = Map.of("content", List.of(textContent), "isError", true);
                return createResultResponse(id, result);
            }
        }

        return textResultResponse(id, agentResponse.toJson());
    }

    /**
     * Decodes a capability contract from the JSON-compatible contract object.
     *
     * @param contractObj untrusted contract value
     * @return validated contract or {@code null} when required fields are absent or invalid
     */
    private CapabilityContract parseContract(Object contractObj) {
        if (!(contractObj instanceof Map<?, ?> map)) {
            return null;
        }
        Object rawInputs = map.get("inputs");
        Object rawOutput = map.get("output");
        if (!(rawInputs instanceof String inputs) || !(rawOutput instanceof String output)) {
            return null;
        }
        List<String> requiredBehavior;
        List<String> acceptanceTests;
        try {
            requiredBehavior = Objects.requireNonNullElse(nullableStringList(map.get("requiredBehavior"), "requiredBehavior"), List.of());
            acceptanceTests = Objects.requireNonNullElse(nullableStringList(map.get("acceptanceTests"), "acceptanceTests"), List.of());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        try {
            return new CapabilityContract(inputs, output, requiredBehavior, acceptanceTests);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Decodes nested task intent fields while preserving the bounded flattened-provider form.
     *
     * @param arguments validated top-level tool arguments, or {@code null}
     * @return typed task intent, or {@code null} when the tool call declares no task
     * @throws IllegalArgumentException when nested task shape or field types violate the wire contract
     */
    private AgentTaskIntent parseTaskIntent(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object taskObj = arguments.get("task");
        Map<?, ?> map;
        if (taskObj instanceof Map<?, ?> taskMap) {
            if (Stream.of("goal", "acceptance", "likelyScopes", "knownDependencies", "workGroupId", "role", "reviewTargets", "claims").anyMatch(arguments::containsKey)) {
                throw new IllegalArgumentException("TASK_FIELDS_MUST_BE_NESTED");
            }
            map = taskMap;
        } else if (arguments.containsKey("goal") || arguments.containsKey("acceptance") || arguments.containsKey("claims") || arguments.containsKey("role") || arguments.containsKey("reviewTargets")) {
            // A few provider renderers flatten the task object.  Normalize
            // that bounded shape without changing the advertised schema.
            map = arguments;
        } else {
            return null;
        }
        Object rawGoal = map.get("goal");
        Object rawAcceptance = map.get("acceptance");
        if (rawGoal != null && !(rawGoal instanceof String) || rawAcceptance != null && !(rawAcceptance instanceof String)) {
            throw new IllegalArgumentException("goal and acceptance must be strings");
        }
        String goal = (String) rawGoal;
        String acceptance = (String) rawAcceptance;
        List<String> likelyScopes = nullableStringList(map.get("likelyScopes"), "likelyScopes");
        List<String> knownDependencies = parseKnownDependencies(map.get("knownDependencies"));
        UUID workGroupId = null;
        if (map.get("workGroupId") instanceof String value && !value.isBlank()) {
            try {
                workGroupId = UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (map.containsKey("completionMode")) {
            throw new IllegalArgumentException("completionMode was removed; use get_next_action completionRequested");
        }
        WorkIntent.Role role = WorkIntent.Role.PRODUCER;
        Object rawRole = map.get("role");
        if (rawRole != null) {
            if (!(rawRole instanceof String value)) {
                throw new IllegalArgumentException("role must be a string");
            }
            role = WorkIntent.Role.fromWire(value);
        }
        if (map.containsKey("reviewTargets") && map.get("reviewTargets") == null) {
            throw new IllegalArgumentException("reviewTargets must be an array");
        }
        List<ResourceSelector> reviewTargetSelectors = parseSelectorList(map.get("reviewTargets"));
        return new AgentTaskIntent(goal, acceptance, likelyScopes, knownDependencies, workGroupId, role, reviewTargetSelectors);
    }

    /**
     * Validates the bounded two-boolean review policy accepted by work-group creation.
     *
     * @param rawPolicy untrusted review policy value, possibly {@code null}
     * @throws IllegalArgumentException when the policy is not an object with exactly boolean {@code mandatory} and {@code independenceRequired}
     */
    private static void validateReviewPolicyPayload(Object rawPolicy) {
        if (rawPolicy == null) {
            return;
        }
        if (!(rawPolicy instanceof Map<?, ?> policy)) {
            throw new IllegalArgumentException("REVIEW_POLICY_SCHEMA_INVALID");
        }
        if (policy.keySet().stream().anyMatch(key -> !"mandatory".equals(key) && !"independenceRequired".equals(key))) {
            throw new IllegalArgumentException("REVIEW_POLICY_FIELD_NOT_ALLOWED");
        }
        if (!(policy.get("mandatory") instanceof Boolean) || !(policy.get("independenceRequired") instanceof Boolean)) {
            throw new IllegalArgumentException("REVIEW_POLICY_FIELDS_REQUIRED");
        }
    }

    /**
     * Converts a validated review policy object into the domain policy value.
     *
     * @param rawPolicy untrusted review policy value, possibly {@code null}
     * @return the parsed policy, or {@link ReviewPolicy#none()} when absent
     * @throws IllegalArgumentException when the payload is invalid or the policy is unsupported
     */
    private static ReviewPolicy parseReviewPolicy(Object rawPolicy) {
        if (rawPolicy == null) {
            return ReviewPolicy.none();
        }
        validateReviewPolicyPayload(rawPolicy);
        Map<?, ?> policy = (Map<?, ?>) rawPolicy;
        try {
            return new ReviewPolicy((Boolean) policy.get("mandatory"), (Boolean) policy.get("independenceRequired"));
        } catch (IllegalArgumentException invalidPolicy) {
            throw new IllegalArgumentException("REVIEW_POLICY_UNSUPPORTED", invalidPolicy);
        }
    }

    /**
     * Parses the bounded non-ownership selectors used to identify review targets.
     *
     * @param rawSelectors untrusted review target array, possibly {@code null}
     * @return immutable review-target selectors, empty when absent
     * @throws IllegalArgumentException when the shape, path, or kind is invalid
     */
    private List<ResourceSelector> parseSelectorList(Object rawSelectors) {
        if (rawSelectors == null) {
            return List.of();
        }
        if (!(rawSelectors instanceof List<?> entries)) {
            throw new IllegalArgumentException("reviewTargets must be an array");
        }
        List<ResourceSelector> selectors = new ArrayList<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> selector)) {
                throw new IllegalArgumentException("review target selector must be an object");
            }
            Object path = selector.get("path");
            if (!(path instanceof String)) {
                path = selector.get("relativePath");
            }
            if (!(path instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("review target selector path is required");
            }
            Object rawKind = selector.get("kind");
            String kind = rawKind == null ? "path_exact" : rawKind instanceof String kindValue ? kindValue.toLowerCase(Locale.ROOT) : null;
            if (kind == null) {
                throw new IllegalArgumentException("review target selector kind must be a string");
            }
            switch (kind) {
                case "path_exact" -> selectors.add(ResourceSelector.pathExact(value));
                case "path_subtree" -> selectors.add(ResourceSelector.pathSubtree(value));
                default -> throw new IllegalArgumentException("unknown review target selector kind: " + rawKind);
            }
        }
        return List.copyOf(selectors);
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

    /**
     * Parses explicitly requested ownership claims without treating likelyScopes as authority.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return immutable claim selectors, empty when none were supplied
     */
    private List<ResourceSelector> parseClaimSelectors(Map<String, Object> arguments) {
        if (arguments == null) {
            return List.of();
        }
        Object taskObj = arguments.get("task");
        Object claimsObj = taskObj instanceof Map<?, ?> taskMap ? taskMap.get("claims") : arguments.get("claims");
        if (!(claimsObj instanceof List<?> claims)) {
            return List.of();
        }
        List<ResourceSelector> selectors = new ArrayList<>();
        for (Object item : claims) {
            if (item instanceof Map<?, ?> claim) {
                Object path = claim.get("path");
                if (!(path instanceof String)) {
                    path = claim.get("relativePath");
                }
                String kind = claim.get("kind") instanceof String value ? value.toLowerCase(Locale.ROOT) : "path_exact";
                if (path instanceof String value) {
                    selectors.add("path_subtree".equals(kind) ? ResourceSelector.pathSubtree(value) : ResourceSelector.pathExact(value));
                }
            }
        }
        return List.copyOf(selectors);
    }

    /**
     * Distinguishes an omitted claims field from an explicitly supplied claims array.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return {@code true} when a {@code claims} key is present, in the nested task object if there is one
     */
    private boolean claimsFieldSpecified(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        return arguments.get("task") instanceof Map<?, ?> taskMap ? taskMap.containsKey("claims") : arguments.containsKey("claims");
    }

    /**
     * Encodes one successful JSON-RPC response envelope.
     *
     * @param id     JSON-RPC request identifier
     * @param result result object
     * @return serialized response frame
     */
    private String createResultResponse(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return ProviderJson.write(response);
    }

    /**
     * Encodes one JSON-RPC error response envelope.
     *
     * @param id      JSON-RPC request identifier, possibly {@code null}
     * @param code    JSON-RPC error code
     * @param message error message
     * @return serialized response frame
     */
    private String createErrorResponse(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return ProviderJson.write(response);
    }

    /**
     * Encodes a successful tool result carrying one text content item.
     *
     * @param id   JSON-RPC request identifier
     * @param text text content
     * @return serialized response frame
     */
    private String textResultResponse(Object id, String text) {
        Map<String, Object> content = Map.of("type", "text", "text", text);
        return createResultResponse(id, Map.of("content", List.of(content)));
    }

    /**
     * Managed connection admission states visible only inside this handler.
     */
    private enum ManagedAdmissionState {
        /**
         * The exact managed attachment is active.
         */
        ACTIVE,
        /**
         * The completed binding may continue exact review coordination only.
         */
        REVIEW_ONLY,
        /**
         * The proof-bearing transport is connected but has no authority.
         */
        PENDING,
        /**
         * The attachment or exact binding is no longer admissible.
         */
        REJECTED
    }
}