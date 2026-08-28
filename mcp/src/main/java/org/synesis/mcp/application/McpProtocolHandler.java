package org.synesis.mcp.application;

import org.synesis.mcp.contract.McpToolCatalog;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.application.agent.AgentNextActionService;
import org.synesis.workspace.application.agent.AgentTaskCompletionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.collaboration.ReviewSnapshotAccessService;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.integration.IntegrationCompatibilityService;
import org.synesis.workspace.application.integration.WorkspaceIntegrationReadinessService;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.project.ProjectCommandAdmissionService;
import org.synesis.workspace.application.workspace.WorkspacePatchService;
import org.synesis.workspace.application.workspace.WorkspaceReadService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.command.PhysicalWorktreeIdentity;
import org.synesis.workspace.lifecycle.command.ProjectCommandAuthoritySnapshot;
import org.synesis.workspace.lifecycle.command.ProjectCommandProcessAnchor;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Handles JSON-RPC 2.0 requests for the Synesis Model Context Protocol (MCP) server over stdio.
 *
 * <p>Implements the standard MCP handshake ({@code initialize}), tool discovery ({@code tools/list}),
 * and tool invocation ({@code tools/call}). Normal tool responses return Stage 1 concise {@link AgentResponse}
 * payloads wrapped in standard MCP content frames.
 *
 * @since 1.0
 */
public final class McpProtocolHandler {

    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private final AgentSessionService sessionService;
    private final WorkspaceReadService readService;
    private final WorkspacePatchService patchService;
    private final ProjectCommandService commandService;
    private final ProjectCommandAdmissionService commandAdmissionService;
    private final AgentNextActionService nextActionService;
    private final CapabilityRequestService capabilityRequestService;
    private final CapabilityResponseService capabilityResponseService;
    private final ImplementationPublicationService publicationService;
    private final ImplementationValidationService validationService;
    private final ReviewValidationService reviewValidationService;
    private final AgentTaskCompletionService taskCompletionService;
    private final org.synesis.workspace.application.agent.AgentTaskCancellationService taskCancellationService;
    private final WorkspaceCollaborationService collaborationService;
    private final ReviewSnapshotAccessService reviewSnapshotAccessService;
    private final SessionAuthorityResolver authorityResolver;
    private final SessionLeaseService leaseService;
    private final SessionLeasePolicy leasePolicy;
    private final ProviderManualService manualService;
    private final Path initialProjectRoot;
    private Path activeProjectRoot;
    private boolean isSessionBound;
    private final String provider;
    private final String connectionInstanceId;
    private final SessionProcessIdentity commandProcessIdentity;
    private ProjectCommandProcessAnchor commandProcessAnchor;
    private Path antigravityProjectsDir;

    /**
     * Creates an MCP protocol handler.
     *
     * @param sessionService       application session service
     * @param projectRoot          canonical control project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection-instance ID
     */
    public McpProtocolHandler(AgentSessionService sessionService,
            Path projectRoot,
            String provider,
            String connectionInstanceId) {
        this(sessionService, projectRoot, provider, connectionInstanceId, captureProcessIdentity(connectionInstanceId));
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
    public McpProtocolHandler(AgentSessionService sessionService,
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            SessionProcessIdentity processIdentity) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.readService = new WorkspaceReadService();
        this.patchService = new WorkspacePatchService();
        this.commandService = new ProjectCommandService();
        this.commandAdmissionService = new ProjectCommandAdmissionService(commandService,
                AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
        this.nextActionService = new AgentNextActionService();
        this.capabilityRequestService = new CapabilityRequestService();
        this.capabilityResponseService = new CapabilityResponseService();
        this.publicationService = new ImplementationPublicationService();
        this.validationService = new ImplementationValidationService();
        this.reviewValidationService = new ReviewValidationService();
        this.taskCompletionService = new AgentTaskCompletionService();
        this.taskCancellationService = new org.synesis.workspace.application.agent.AgentTaskCancellationService();
        this.collaborationService = new WorkspaceCollaborationService();
        this.reviewSnapshotAccessService = new ReviewSnapshotAccessService();
        this.authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
        this.leaseService = new SessionLeaseService();
        this.leasePolicy = new SessionLeasePolicy();
        this.manualService = new ProviderManualService();
        this.initialProjectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.activeProjectRoot = projectRoot;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.connectionInstanceId = Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        this.commandProcessIdentity = Objects.requireNonNull(processIdentity, "processIdentity");
        String userHome = System.getProperty("user.home");
        this.antigravityProjectsDir = (userHome != null)
                ? Path.of(userHome, ".gemini", "config", "projects")
                : null;
    }

    /**
     * Overrides the Antigravity per-project config directory used during fallback root scanning.
     * Package-private for use in unit tests.
     *
     * @param dir override path, or {@code null} to disable the scan
     */
    void setAntigravityProjectsDir(Path dir) {
        this.antigravityProjectsDir = dir;
    }

    /**
     * Returns the currently resolved active control project root path.
     *
     * @return active control project root path
     */
    public Path activeProjectRoot() {
        return activeProjectRoot;
    }

    /** Renews the exact session lease after verified MCP activity. */
    private void renewLease() {
        try {
            ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            var binding = authorityResolver.resolve(location, provider, connectionInstanceId);
            String nodeId = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
            leaseService.createOrRenewLease(activeProjectRoot, location.projectId().toString(), provider,
                    connectionInstanceId, nodeId, binding.sessionId(), leasePolicy);
            collaborationService.heartbeat(activeProjectRoot, provider, connectionInstanceId);
        } catch (Exception ignored) {
            // Unbound requests are handled by the session and workspace policy paths.
        }
    }

    /** Marks a clean stdio shutdown and detaches this connection's lane. */
    public void close() {
        try {
            leaseService.markClosedCleanly(activeProjectRoot, connectionInstanceId);
            collaborationService.detach(activeProjectRoot, provider, connectionInstanceId);
        } catch (Exception ignored) {
            // Recovery reconciles unclean or partially completed shutdowns.
        }
    }

    /**
     * Processes a raw JSON-RPC message string and returns the JSON-RPC response frame string,
     * or {@code null} if the request was a notification.
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
            return createErrorResponse(null, -32700, "Parse error: " + parseFailure.getMessage());
        }

        if (!(parsed instanceof Map<?, ?> map)) {
            return createErrorResponse(null, -32600, "Invalid Request: expected JSON object");
        }

        Map<String, Object> request = (Map<String, Object>) map;
        Object id = request.get("id");
        String method = (String) request.get("method");

        if (method == null || method.isBlank()) {
            if (id != null) {
                return createErrorResponse(id, -32600, "Invalid Request: missing method");
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
                case "notifications/roots/list_changed", "roots/list_changed" ->
                        handleRootsListChanged(request.get("params"));
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, (Map<String, Object>) request.get("params"));
                case "roots/list" -> handleRootsList(id);
                default -> createErrorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (Throwable failure) {
            System.err.println("[synesis-mcp] Error handling method " + method + ": " + failure);
            failure.printStackTrace(System.err);
            return createErrorResponse(id, -32603, "Internal error: " + failure.getMessage());
        }
    }

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

    private String handleInitialize(Object id, Map<String, Object> params) {
        if (!isSessionBound) {
            // Primary: extract from MCP params (rootUri, workspaceFolders, roots)
            List<Path> paramCandidates = extractCandidateRoots(params);
            Path resolved = resolveProjectRootFromCandidates(paramCandidates);

            // Fallback: scan Antigravity per-project configs and env vars when params yield nothing
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
        serverInfo.put("version", "0.1.0-SNAPSHOT");

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
        if (params != null && params.get("protocolVersion") instanceof String requested
                && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
            return requested;
        }
        return DEFAULT_PROTOCOL_VERSION;
    }

    private String handleRootsList(Object id) {
        Map<String, Object> rootItem = new LinkedHashMap<>();
        rootItem.put("uri",
                activeProjectRoot.toUri()
                        .toString());
        rootItem.put("name",
                activeProjectRoot.getFileName() != null ? activeProjectRoot.getFileName()
                                                          .toString() : "root");

        Map<String, Object> result = Map.of("roots", List.of(rootItem));
        return createResultResponse(id, result);
    }

    /**
     * Extracts candidate workspace project root paths from MCP {@code initialize} request parameters only.
     * Does not include environment variables, file system scans, or process working directory.
     *
     * @param params JSON-RPC initialize request params map, or {@code null}
     * @return list of parsed candidate paths from protocol params
     */
    public List<Path> extractCandidateRoots(Map<String, Object> params) {
        List<Path> candidates = new java.util.ArrayList<>();
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
            for (Object item : folders) {
                if (item instanceof Map<?, ?> map && map.get("uri") instanceof String uriStr) {
                    Path p = parseUriOrPath(uriStr);
                    if (p != null && !candidates.contains(p)) {
                        candidates.add(p);
                    }
                }
            }
        }

        if (params.get("roots") instanceof List<?> rootsList) {
            for (Object item : rootsList) {
                if (item instanceof Map<?, ?> map && map.get("uri") instanceof String uriStr) {
                    Path p = parseUriOrPath(uriStr);
                    if (p != null && !candidates.contains(p)) {
                        candidates.add(p);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Collects fallback candidate project roots from environment variables, the Antigravity
     * per-project config directory, the explicit {@code --project} argument, and the process
     * working directory. Only called when MCP {@code initialize} params yield no initialized root.
     *
     * @return list of fallback candidate paths
     */
    List<Path> extractFallbackCandidates() {
        if (initialProjectRoot != null
                && java.nio.file.Files.exists(initialProjectRoot.resolve(".synesis/project.json"))) {
            return List.of(initialProjectRoot.toAbsolutePath()
                    .normalize());
        }
        List<Path> candidates = new java.util.ArrayList<>();

        // Environment variables
        String[] envKeys = {
                "WORKSPACE_ROOT", "WORKSPACE_FOLDER", "WORKSPACE_DIR",
                "PROJECT_ROOT", "PROJECT_DIR",
                "GEMINI_WORKSPACE", "ANTIGRAVITY_WORKSPACE", "ANTIGRAVITY_PROJECT",
                "VSCODE_WORKSPACE_FOLDER", "INIT_CWD", "PWD"
        };
        for (String envKey : envKeys) {
            String envVal = System.getenv(envKey);
            if (envVal != null && !envVal.isBlank()) {
                Path p = parseUriOrPath(envVal);
                if (p != null && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
        }

        // Explicit initial project root (e.g. --project argument)
        if (initialProjectRoot != null && !candidates.contains(initialProjectRoot)) {
            candidates.add(initialProjectRoot.toAbsolutePath()
                    .normalize());
        }

        // Scan Antigravity per-project configs (~/.gemini/config/projects/*.json)
        try {
            if (antigravityProjectsDir != null && java.nio.file.Files.isDirectory(antigravityProjectsDir)) {
                try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(antigravityProjectsDir)) {
                    files.filter(f -> f.getFileName()
                                    .toString()
                                    .endsWith(".json"))
                            .forEach(configFile -> {
                                try {
                                    String json = java.nio.file.Files.readString(configFile);
                                    int idx = 0;
                                    while ((idx = json.indexOf("\"folderUri\"", idx)) != -1) {
                                        int colon = json.indexOf(':', idx);
                                        int quote1 = json.indexOf('"', colon + 1);
                                        int quote2 = json.indexOf('"', quote1 + 1);
                                        if (colon != -1 && quote1 != -1 && quote2 != -1) {
                                            String uri = json.substring(quote1 + 1, quote2);
                                            Path p = parseUriOrPath(uri);
                                            if (p != null && !candidates.contains(p)) {
                                                candidates.add(p);
                                            }
                                        }
                                        idx = quote2 + 1;
                                    }
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }

        // Current working directory (last resort)
        try {
            Path cwd = Path.of(".")
                    .toAbsolutePath()
                    .normalize();
            if (!candidates.contains(cwd)) {
                candidates.add(cwd);
            }
        } catch (Exception ignored) {
        }

        return candidates;
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
        try {
            if (trimmed.startsWith("file:")) {
                return Path.of(java.net.URI.create(trimmed))
                        .toAbsolutePath()
                        .normalize();
            }
        } catch (Exception ignored) {
        }

        try {
            return Path.of(java.net.URLDecoder.decode(trimmed, java.nio.charset.StandardCharsets.UTF_8))
                    .toAbsolutePath()
                    .normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Resolves the single initialized Synesis control project root from candidate paths.
     *
     * @param candidates candidate workspace paths
     * @return resolved control project root, or {@code null} if none or ambiguous
     */
    public Path resolveProjectRootFromCandidates(List<Path> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        List<Path> initializedRoots = new java.util.ArrayList<>();
        String userHome = System.getProperty("user.home");
        Path homePath = (userHome != null && !userHome.isBlank()) ? Path.of(userHome)
                                                                    .toAbsolutePath()
                                                                    .normalize() : null;

        for (Path candidate : candidates) {
            try {
                Path normalized = candidate.toAbsolutePath()
                        .normalize();
                String normStr = normalized.toString()
                        .replace('\\', '/');
                if (normStr.contains("/.synesis/local/worktrees/")) {
                    continue; // Reject assigned worktree path as control project root
                }
                if (normalized.equals(homePath)) {
                    continue; // Reject user home directory as control project root
                }
                if (java.nio.file.Files.exists(normalized.resolve(".synesis/project.json"))) {
                    if (!initializedRoots.contains(normalized)) {
                        initializedRoots.add(normalized);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (initializedRoots.size() == 1) {
            return initializedRoots.getFirst();
        }

        if (initializedRoots.size() > 1) {
            System.err.println("SYNESIS_DIAGNOSTIC=PROJECT_ROOT_AMBIGUOUS count=" + initializedRoots.size());
            return Path.of(System.getProperty("java.io.tmpdir"));
        }

        return null;
    }

    private String handleToolsList(Object id) {
        return createResultResponse(id, Map.of("tools", McpToolCatalog.toolsList()));
    }

    private static SessionProcessIdentity captureProcessIdentity(String connectionInstanceId) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElse("unknown");
        String commandLine = info.commandLine().orElse(executable);
        long start = info.startInstant().map(java.time.Instant::toEpochMilli)
                .orElse(System.currentTimeMillis());
        return new SessionProcessIdentity(ProcessHandle.current().pid(), executable, commandLine, start,
                connectionInstanceId + ":" + UUID.randomUUID());
    }

    private AgentResponse runDurableCommand(ProjectCommandService.CommandRequest request, Object requestId) {
        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        try {
            location = new ProjectApplicationService().locate(activeProjectRoot);
            readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION, Map.of("error", "WORKSPACE_UNVERIFIED"));
        }
        if (!readiness.ready()) {
            return readiness.response();
        }

        PhysicalWorktreeIdentity worktree;
        try {
            worktree = PhysicalWorktreeIdentity.capture(activeProjectRoot, readiness.worktree(),
                    new LifecyclePathVerifier());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_STALE,
                    AgentNextAction.ENSURE_SESSION, Map.of("error", "WORKTREE_IDENTITY_UNVERIFIED"));
        }
        if (commandProcessAnchor == null) {
            commandProcessAnchor = ProjectCommandProcessAnchor.capture(worktree.locator(), commandProcessIdentity,
                    java.time.Instant.now().toEpochMilli());
        } else if (!commandProcessAnchor.scopeLocator().equals(worktree.locator())) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COMMAND_ADMISSION_STALE,
                    AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "MCP_PROCESS_SCOPE_CHANGED"));
        }
        ProjectCommandAuthoritySnapshot before = captureAuthoritySnapshot(readiness, worktree);
        return commandAdmissionService.execute(request, requestId, commandProcessAnchor, worktree, before,
                () -> renewLeaseForCommand(worktree));
    }

    /**
     * Re-arms command admission only after a successful session resolution has
     * verified a different isolated worktree. A provider connection may move
     * from a stale clean worktree to a recovery worktree, while the immutable
     * MCP process identity remains attached to the same connection.
     */
    private void refreshCommandProcessAnchorForVerifiedSession() {
        if (commandProcessAnchor == null) {
            return;
        }
        try {
            ProjectApplicationService.ProjectLocation location =
                    new ProjectApplicationService().locate(activeProjectRoot);
            WorkspaceReadinessService.ReadinessResult readiness =
                    new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
            if (!readiness.ready()) {
                return;
            }
            PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(activeProjectRoot,
                    readiness.worktree(), new LifecyclePathVerifier());
            if (!commandProcessAnchor.scopeLocator().equals(worktree.locator())) {
                commandProcessAnchor = null;
            }
        } catch (Exception ignored) {
            // Preserve fail-closed command admission when the new scope cannot
            // be independently verified after session resolution.
        }
    }

    private ProjectCommandAuthoritySnapshot renewLeaseForCommand(PhysicalWorktreeIdentity worktree) throws Exception {
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
        var binding = authorityResolver.resolve(location, provider, connectionInstanceId);
        String nodeId = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
        leaseService.createOrRenewLease(activeProjectRoot, location.projectId().toString(), provider,
                connectionInstanceId, nodeId, binding.sessionId(), leasePolicy);
        collaborationService.heartbeatIfPresent(activeProjectRoot, provider, connectionInstanceId);
        WorkspaceReadinessService.ReadinessResult readiness = new WorkspaceReadinessService()
                .assess(location, provider, connectionInstanceId);
        if (!readiness.ready()) {
            throw new IllegalStateException("COMMAND_AUTHORITY_REFRESH_FAILED");
        }
        return captureAuthoritySnapshot(readiness, worktree);
    }

    private ProjectCommandAuthoritySnapshot captureAuthoritySnapshot(
            WorkspaceReadinessService.ReadinessResult readiness, PhysicalWorktreeIdentity worktree) {
        SessionLeaseRecord lease = new SessionLeaseStore().load(activeProjectRoot, connectionInstanceId).orElse(null);
        return ProjectCommandAuthoritySnapshot.capture(readiness.binding(), worktree, lease, "none");
    }

    @SuppressWarnings("unchecked")
    private String handleToolsCall(Object id, Map<String, Object> params) {
        if (params == null) {
            return createErrorResponse(id, -32602, "Invalid params: missing params object");
        }

        String name = (String) params.get("name");
        // The wire contract advertises raw names only.
        if (name == null || name.startsWith("synesis.")) {
            return createErrorResponse(id, -32602, "raw MCP tool name required");
        }
        name = "synesis." + name;
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        AgentResponse agentResponse;
        boolean durableCommand = name.equals("synesis." + McpToolCatalog.RUN_COMMAND);
        boolean noChangeCompletion = name.equals("synesis." + McpToolCatalog.FINISH_LANE)
                && arguments != null && arguments.get("outcome") instanceof String outcome
                && ("no_change".equalsIgnoreCase(outcome)
                || "no_change_allowed".equalsIgnoreCase(outcome));
        boolean snapshotCompletion = name.equals("synesis." + McpToolCatalog.FINISH_LANE);
        // finish_lane carries an optimistic event-log revision in its
        // projected evidence.  The ordinary lease heartbeat appends a
        // collaboration event, so doing it before completion would invalidate
        // the exact server-issued finish envelope before the completion
        // service can consume it.  Completion itself remains authoritative;
        // all other non-command calls retain the normal activity heartbeat.
        if (!durableCommand && !noChangeCompletion && !snapshotCompletion) {
            renewLease();
        }

        if (requiresManualAttestation(name, arguments) && !manualService.attest(provider).valid()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("manual", manualService.attest(provider).reason());
            details.put("authorityReductionAllowed", true);
            return createResultResponse(id, Map.of("content", List.of(Map.of("type", "text",
                    "text", "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\",\"details\":"
                            + ProviderJson.write(details) + "}"))));
        }

        switch (name) {
            case "synesis." + McpToolCatalog.ENSURE_SESSION -> {
                AgentSessionService.AgentTaskIntent taskIntent;
                try {
                    taskIntent = parseTaskIntent(arguments);
                } catch (IllegalArgumentException invalidTask) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                            AgentNextAction.REQUEST_HUMAN_HELP,
                            Map.of("reason", "INVALID_TASK_INTENT"));
                    break;
                }
                boolean refresh = arguments != null && Boolean.TRUE.equals(arguments.get("refresh"));

                if (!parseClaimSelectors(arguments).isEmpty()
                        && !manualService.attest(provider).valid()) {
                    return createResultResponse(id, Map.of("content", List.of(Map.of("type", "text",
                            "text", "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\",\"claims\":\"REJECTED\"}"))));
                }

                AgentSessionService.SessionResolutionRequest resolutionRequest = new AgentSessionService.SessionResolutionRequest(
                        activeProjectRoot, provider, connectionInstanceId, taskIntent, refresh);

                agentResponse = sessionService.ensureSession(resolutionRequest);
                if (agentResponse.status() == AgentStatus.READY) {
                    isSessionBound = true;
                    refreshCommandProcessAnchorForVerifiedSession();
                    // The first verified ensure_session is activity too. Establish the lease
                    // before any claim is announced so an abruptly deleted chat remains
                    // recoverable even when it sends no follow-up MCP request.
                    renewLease();
                    if (arguments != null && Boolean.TRUE.equals(arguments.get("unwindCompletion"))) {
                        agentResponse = taskCompletionService.unwindPrepared(
                                new AgentTaskCompletionService.CompleteTaskRequest(
                                activeProjectRoot, provider, connectionInstanceId, null));
                    }
                    String repairIntentText = taskField(arguments, "repairIntentId");
                    String repairSnapshotId = taskField(arguments, "repairSnapshotId");
                    if (repairIntentText != null || repairSnapshotId != null) {
                        try {
                            if (repairIntentText == null || repairSnapshotId == null) {
                                throw new IllegalArgumentException("repair intent and snapshot are both required");
                            }
                            UUID repairIntentId = UUID.fromString(repairIntentText);
                            var joined = collaborationService.joinRepair(activeProjectRoot, provider,
                                    connectionInstanceId, repairIntentId, repairSnapshotId);
                            if (!joined.acquired()) {
                                agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                                        AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_HUMAN_HELP,
                                        Map.of("conflicts", joined.conflicts()));
                            } else {
                                agentResponse = new AgentResponse(AgentStatus.READY, null,
                                        AgentNextAction.RETRY, Map.of("repairJoined", true,
                                                "intentId", joined.intent().intentId().toString(),
                                                "claimEpoch", joined.intent().version()));
                            }
                        } catch (Exception repairFailure) {
                            agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                                    AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                                    Map.of("reason", repairFailure.getMessage() == null
                                            ? "REPAIR_JOIN_FAILED" : repairFailure.getMessage()));
                        }
                    }
                    List<ResourceSelector> selectors = parseClaimSelectors(arguments);
                    boolean claimsSpecified = claimsFieldSpecified(arguments);
                    if (refresh && claimsSpecified && selectors.isEmpty()) {
                        try {
                            collaborationService.release(activeProjectRoot, provider, connectionInstanceId);
                        } catch (java.io.IOException missing) {
                            if (!"INTENT_NOT_FOUND".equals(missing.getMessage())) {
                                agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                                        AgentNextAction.REQUEST_HUMAN_HELP, null);
                            }
                        } catch (Exception failure) {
                            agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                                    AgentNextAction.REQUEST_HUMAN_HELP, null);
                        }
                    } else if (!selectors.isEmpty()) {
                        try {
                            AgentSessionService.AgentTaskIntent intent = taskIntent;
                            ClaimResult claimResult = collaborationService.announce(activeProjectRoot, provider,
                                    connectionInstanceId, intent == null ? null : intent.goal(),
                                    intent == null ? null : intent.acceptance(), selectors,
                                    intent == null ? null : intent.workGroupId(),
                                    intent == null ? WorkIntent.CompletionMode.SNAPSHOT_REQUIRED
                                            : intent.completionMode(),
                                    intent == null ? WorkIntent.Role.PRODUCER : intent.role(),
                                    intent == null ? List.of() : intent.reviewTargetSelectors());
                            if (!claimResult.acquired()) {
                                Map<String, Object> details = new LinkedHashMap<>();
                                details.put("conflicts", claimResult.conflicts().stream().map(conflict -> Map.of(
                                        "participant", conflict.participant(),
                                        "intent", conflict.intentId(),
                                        "kind", conflict.selector().kind().name(),
                                        "path", conflict.selector().value())).toList());
                                agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                                        AgentReason.OVERLAPPING_CLAIM, AgentNextAction.REQUEST_HUMAN_HELP, details);
                            }
                        } catch (Exception failure) {
                            if ("SESSION_EPOCH_FENCED".equals(failure.getMessage())) {
                                agentResponse = new AgentResponse(AgentStatus.BLOCKED,
                                        AgentReason.WORKSPACE_GENERATION_CHANGED, AgentNextAction.RETRY, null);
                            } else {
                                agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                                        AgentNextAction.REQUEST_HUMAN_HELP, null);
                            }
                        }
                    }
                }
            }
            case "synesis." + McpToolCatalog.READ_FILE -> {
                String path = stringArgument(arguments, "path", "relativePath");
                Integer startLine =
                        (arguments != null && arguments.get("startLine") instanceof Number n) ? n.intValue() : null;
                Integer endLine =
                        (arguments != null && arguments.get("endLine") instanceof Number n) ? n.intValue() : null;
                Integer maxBytes =
                        (arguments != null && arguments.get("maxBytes") instanceof Number n) ? n.intValue() : null;

                WorkspaceReadService.ReadRequest readReq = new WorkspaceReadService.ReadRequest(
                        activeProjectRoot, provider, connectionInstanceId, path, startLine, endLine, maxBytes);
                agentResponse = readService.readFile(readReq);
            }
            case "synesis." + McpToolCatalog.APPLY_PATCH -> {
                String path = stringArgument(arguments, "path", "relativePath");
                boolean create = arguments != null && Boolean.TRUE.equals(arguments.get("create"));
                String content = stringArgument(arguments, "content", "newContent");
                String expectedHash = stringArgument(arguments, "expectedHash", "expectedRevision");

                List<WorkspacePatchService.PatchEdit> patchEdits = new java.util.ArrayList<>();
                if (arguments != null && arguments.get("edits") instanceof List<?> editsList) {
                    for (Object item : editsList) {
                        if (item instanceof Map<?, ?> editMap) {
                            String find = (String) editMap.get("find");
                            String replace = (String) editMap.get("replace");
                            int expectedOccurrences =
                                    (editMap.get("expectedOccurrences") instanceof Number n) ? n.intValue() : 0;
                            if (find != null && replace != null && expectedOccurrences >= 1) {
                                patchEdits.add(new WorkspacePatchService.PatchEdit(find, replace, expectedOccurrences));
                            }
                        }
                    }
                }

                WorkspacePatchService.PatchRequest patchReq = new WorkspacePatchService.PatchRequest(
                        activeProjectRoot,
                        provider,
                        connectionInstanceId,
                        path,
                        create,
                        content,
                        expectedHash,
                        patchEdits);
                agentResponse = patchService.applyPatch(patchReq);
            }
            case "synesis." + McpToolCatalog.RUN_COMMAND -> {
                List<String> argv = new java.util.ArrayList<>();
                boolean unsupportedField = arguments != null && arguments.keySet().stream()
                        .anyMatch(key -> !Set.of("argv", "workingDirectory", "timeoutSeconds").contains(key));
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
                boolean malformedWorkingDirectory = arguments != null && arguments.containsKey("workingDirectory")
                        && !(arguments.get("workingDirectory") instanceof String);
                String workingDirectory = arguments != null && arguments.get("workingDirectory") instanceof String value
                        ? value : ".";
                Integer timeoutSeconds = null;
                boolean malformedTimeout = false;
                if (arguments != null && arguments.containsKey("timeoutSeconds")) {
                    Object rawTimeout = arguments.get("timeoutSeconds");
                    if (rawTimeout instanceof Number number && isStrictInteger(number)
                            && number.longValue() >= 1 && number.longValue() <= 3600) {
                        timeoutSeconds = number.intValue();
                    } else {
                        malformedTimeout = true;
                    }
                }
                if (argv == null || argv.isEmpty() || malformedWorkingDirectory || malformedTimeout || unsupportedField) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    try {
                        AgentResponse reviewCommand = reviewSnapshotAccessService.runReviewCommand(
                                activeProjectRoot, provider, connectionInstanceId, argv, workingDirectory, timeoutSeconds);
                        if (reviewCommand != null) {
                            agentResponse = reviewCommand;
                        } else {
                            ProjectCommandService.CommandRequest cmdReq = new ProjectCommandService.CommandRequest(
                                    activeProjectRoot, provider, connectionInstanceId, argv, workingDirectory, timeoutSeconds);
                            agentResponse = runDurableCommand(cmdReq, id);
                        }
                    } catch (IllegalArgumentException ex) {
                        agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                    }
                }
            }
            case "synesis." + McpToolCatalog.GET_NEXT_ACTION -> {
                if (arguments != null && arguments.get("integrationCheck") instanceof Map<?, ?> check) {
                    try {
                        String head = String.valueOf(check.get("controlHead"));
                        String base = String.valueOf(check.get("base"));
                        List<String> paths = strings(check.get("paths"));
                        List<String> claims = strings(check.get("claims"));
                        var snapshot = new IntegrationCompatibilityService.SnapshotInput("mcp-candidate", base, paths,
                                claims.stream().map(ResourceSelector::pathExact).toList(), List.of(), List.of());
                        var result = new WorkspaceIntegrationReadinessService().check(new IntegrationCompatibilityService.CheckRequest(
                                head, List.of(snapshot), List.of(), testsPassed(check)));
                        List<String> failureCodes = result.failures().stream().map(Enum::name).toList();
                        agentResponse = new AgentResponse(result.accepted() ? AgentStatus.COMPLETED : AgentStatus.BLOCKED,
                                result.accepted() ? null : AgentReason.INTEGRATION_CONFLICT,
                                result.accepted() ? null : AgentNextAction.REQUEST_HUMAN_HELP,
                                Map.of("accepted", result.accepted(), "failures", failureCodes, "actions", result.actions()));
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                                AgentNextAction.RETRY, Map.of("error", failure.getMessage()));
                    }
                } else {
                    AgentNextActionService.NextActionRequest nextReq = new AgentNextActionService.NextActionRequest(
                            activeProjectRoot, provider, connectionInstanceId);
                    agentResponse = nextActionService.getNextAction(nextReq);
                }
            }
            case "synesis." + McpToolCatalog.REQUEST_COORDINATION -> {
                if (arguments == null || !arguments.containsKey("kind")) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                            AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
                    break;
                }
                try {
                    arguments = normalizeStrictCoordination(arguments);
                } catch (IllegalArgumentException failure) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                            AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
                    break;
                }
                String collaborationOperation = arguments != null ? (String) arguments.get("collaborationOperation") : null;
                if (collaborationOperation != null) {
                    try {
                        if ("capability_request".equals(collaborationOperation)) {
                            CapabilityContract contract = parseContract(arguments.get("contract"));
                            agentResponse = capabilityRequestService.describeRequiredCapability(
                                    new CapabilityRequestService.DescribeCapabilityRequest(
                                            activeProjectRoot, provider, connectionInstanceId,
                                            String.valueOf(arguments.get("capability")), contract,
                                            arguments.get("capabilityRequestHandle") instanceof String value ? value : null,
                                            arguments.get("revisionResponse") instanceof String value ? value : null,
                                            arguments.get("ownerAuthorityLineageId") instanceof String value
                                                    ? UUID.fromString(value) : null));
                            break;
                        }
                        var result = switch (collaborationOperation) {
                            case "status" -> {
                                var snapshot = collaborationService.contractStatus(activeProjectRoot);
                                Map<String, Object> status = new LinkedHashMap<>(collaborationStatusMap(
                                        collaborationService.status(activeProjectRoot)));
                                status.put("contracts", snapshot.contracts().stream().map(McpProtocolHandler::contractMap).toList());
                                status.put("dependencies", snapshot.dependencies().stream().map(McpProtocolHandler::dependencyMap).toList());
                                yield status;
                            }
                            case "publish" -> {
                                UUID contractId = UUID.fromString(String.valueOf(arguments.get("collaborationContractId")));
                                String body = String.valueOf(arguments.getOrDefault("collaborationBody", ""));
                                List<String> selectors = arguments.get("collaborationSelectors") instanceof List<?> values
                                        ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
                                var contract = collaborationService.publishContract(activeProjectRoot, provider, connectionInstanceId,
                                        contractId, body, selectors);
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
                                CoordinationRequest.Kind kind = CoordinationRequest.Kind.valueOf(
                                        String.valueOf(arguments.getOrDefault("collaborationRequestKind", "CONTRACT")).toUpperCase(java.util.Locale.ROOT));
                                String proposal = String.valueOf(arguments.getOrDefault("collaborationProposal", ""));
                                var request = collaborationService.request(activeProjectRoot, provider, connectionInstanceId,
                                        intentId, kind, proposal);
                                yield Map.of("request", requestMap(request));
                            }
                            case "handoff" -> {
                                UUID intentId = UUID.fromString(String.valueOf(arguments.get("collaborationIntentId")));
                                String target = String.valueOf(arguments.get("collaborationTarget"));
                                String proposal = String.valueOf(arguments.getOrDefault("collaborationProposal", ""));
                                var request = collaborationService.handoff(activeProjectRoot, provider, connectionInstanceId,
                                        intentId, target, proposal);
                                yield Map.of("request", requestMap(request));
                            }
                            case "work_group_create" -> {
                                UUID groupId = UUID.fromString(String.valueOf(arguments.get("workGroupId")));
                                collaborationService.createWorkGroup(activeProjectRoot, groupId,
                                        String.valueOf(arguments.getOrDefault("collaborationGoal", "")),
                                        String.valueOf(arguments.getOrDefault("collaborationAcceptance", "")));
                                yield Map.of("workGroupId", groupId.toString(), "status", "ACTIVE");
                            }
                            case "lane_grant_issue" -> {
                                UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                                UUID groupId = UUID.fromString(String.valueOf(arguments.get("workGroupId")));
                                UUID intentId = UUID.fromString(String.valueOf(arguments.get("intentId")));
                                LaneGrant grant = new LaneGrant(grantId, groupId, intentId,
                                        String.valueOf(arguments.get("targetParticipant")),
                                        ((Number) arguments.getOrDefault("claimEpoch", 1)).longValue(),
                                        !Boolean.FALSE.equals(arguments.get("singleUse")));
                                collaborationService.issueLaneGrant(activeProjectRoot, grant);
                                yield Map.of("grantId", grantId.toString(), "status", "ISSUED");
                            }
                            case "lane_grant_consume" -> {
                                UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                                var callerBinding = authorityResolver.resolveReview(
                                        new ProjectApplicationService().locate(activeProjectRoot), provider, connectionInstanceId);
                                String callerParticipant = WorkspaceCollaborationService.participantHandle(callerBinding.sessionId());
                                collaborationService.consumeLaneGrant(activeProjectRoot, grantId,
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
                                UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                                UUID sourceIntentId = UUID.fromString(String.valueOf(arguments.get("collaborationSourceIntentId")));
                                long epoch = ((Number) arguments.getOrDefault("claimEpoch", 1)).longValue();
                                collaborationService.continueLane(activeProjectRoot, provider, connectionInstanceId,
                                        grantId, sourceIntentId, epoch);
                                yield Map.of("grantId", grantId.toString(), "status", "CONTINUED");
                            }
                            case "work_group_close" -> {
                                UUID groupId = UUID.fromString(String.valueOf(arguments.get("workGroupId")));
                                WorkGroup.Status status = WorkGroup.Status.valueOf(String.valueOf(
                                        arguments.getOrDefault("groupStatus", "COMPLETED")).toUpperCase(java.util.Locale.ROOT));
                                long version = ((Number) arguments.getOrDefault("groupVersion", 1)).longValue();
                                collaborationService.closeWorkGroup(activeProjectRoot, groupId, status, version);
                                yield Map.of("workGroupId", groupId.toString(), "status", status.name());
                            }
                            default -> throw new IllegalArgumentException("unknown collaboration operation");
                        };
                        agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null, result);
                        break;
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                                AgentNextAction.RETRY, Map.of("error", failure.getMessage()));
                        break;
                    }
                }
                throw new IllegalStateException("strict coordination request did not produce an operation");
            }
            case "synesis." + McpToolCatalog.RESPOND_COORDINATION -> {
                if (arguments != null && arguments.get("kind") instanceof String) {
                    try {
                        Map<String, Object> strict = normalizeStrictResponse(arguments);
                        String kind = String.valueOf(strict.get("kind"));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = (Map<String, Object>) strict.get("payload");
                        switch (kind) {
                            case "implementation_validation" -> {
                                List<String> failedTests = payload.get("failedAcceptanceTests") instanceof List<?> list
                                        ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                                        : List.of();
                                agentResponse = validationService.validateImplementation(
                                        new ImplementationValidationService.ValidateRequest(
                                                activeProjectRoot, provider, connectionInstanceId,
                                                String.valueOf(payload.get("capabilityRequestHandle")),
                                                String.valueOf(payload.get("result")),
                                                payload.get("reason") == null ? null : String.valueOf(payload.get("reason")),
                                                ((Number) payload.get("implementationRevision")).intValue(),
                                        failedTests));
                            }
                            case "review_validation" -> {
                                agentResponse = reviewValidationService.validate(
                                        new ReviewValidationService.ValidateRequest(
                                                activeProjectRoot, provider, connectionInstanceId,
                                                UUID.fromString(String.valueOf(payload.get("grantId"))),
                                                String.valueOf(payload.get("snapshotId")),
                                                UUID.fromString(String.valueOf(payload.get("intentId"))),
                                                ((Number) payload.get("claimEpoch")).longValue(),
                                                String.valueOf(payload.get("result")),
                                                payload.get("reason") == null ? null : String.valueOf(payload.get("reason"))));
                            }
                            case "capability_response" -> {
                                agentResponse = capabilityResponseService.respondToOwnerRequest(
                                        new CapabilityResponseService.OwnerResponseRequest(
                                                activeProjectRoot, provider, connectionInstanceId,
                                                String.valueOf(payload.get("capabilityRequestHandle")),
                                                String.valueOf(payload.get("response")),
                                                parseContract(payload.get("revision")),
                                                payload.get("reason") == null ? null : String.valueOf(payload.get("reason"))));
                            }
                            case "coordination_response" -> {
                                CoordinationRequest.Status status = CoordinationRequest.Status.valueOf(
                                        String.valueOf(payload.get("coordinationStatus")));
                                collaborationService.respond(activeProjectRoot, provider, connectionInstanceId,
                                        UUID.fromString(String.valueOf(payload.get("coordinationRequest"))), status,
                                        String.valueOf(payload.getOrDefault("proposal", "")));
                                agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null,
                                        Map.of("coordinationRequest", payload.get("coordinationRequest"), "status", status.name()));
                            }
                            case "inbox_acknowledge", "inbox_resolve" -> {
                                UUID itemId = UUID.fromString(String.valueOf(payload.get("inboxItemId")));
                                boolean resolve = "inbox_resolve".equals(kind);
                                if (resolve) {
                                    CoordinationRequest.Status status = CoordinationRequest.Status.valueOf(
                                            String.valueOf(payload.get("resolution")));
                                    collaborationService.resolveInbox(activeProjectRoot, provider, connectionInstanceId,
                                            itemId, status, String.valueOf(payload.getOrDefault("proposal", "")));
                                } else {
                                    collaborationService.acknowledgeInbox(activeProjectRoot, provider, connectionInstanceId, itemId);
                                }
                                agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null,
                                        Map.of("inboxItemId", itemId.toString(), "acknowledged", true, "resolved", resolve));
                            }
                            default -> throw new IllegalArgumentException("unknown coordination response kind");
                        }
                        break;
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                                AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
                        break;
                    }
                }
                agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                        AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "COORDINATION_SCHEMA_REQUIRES_KIND_AND_PAYLOAD"));
            }
            case "synesis." + McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION -> {
                String reqHandle = arguments != null ? (String) arguments.get("capabilityRequestHandle") : null;
                String summary = arguments != null ? (String) arguments.get("summary") : null;

                if (reqHandle == null || reqHandle.isBlank()) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    ImplementationPublicationService.PublishRequest pubReq = new ImplementationPublicationService.PublishRequest(
                            activeProjectRoot, provider, connectionInstanceId, reqHandle, summary);
                    agentResponse = publicationService.publishImplementation(pubReq);
                }
            }
            case "synesis." + McpToolCatalog.FINISH_LANE -> {
                try {
                    String summary = optionalStringArgument(arguments, "summary");
                    AgentTaskCompletionService.CompletionOutcome outcome = arguments != null
                            && arguments.containsKey("outcome")
                            ? AgentTaskCompletionService.CompletionOutcome.fromWire(
                                    requiredStringArgument(arguments, "outcome"))
                            : AgentTaskCompletionService.CompletionOutcome.SNAPSHOT;
                    UUID expectedIntentId = optionalUuidArgument(arguments, "intentId");
                    UUID expectedWorkGroupId = optionalUuidArgument(arguments, "workGroupId");
                    Long expectedClaimEpoch = optionalLongArgument(arguments, "claimEpoch", 1);
                    Long expectedWorkGroupVersion = optionalLongArgument(arguments, "workGroupVersion", 1);
                    Long expectedRevision = optionalLongArgument(arguments, "expectedRevision", 0);
                    String expectedParticipant = optionalStringArgument(arguments, "participant");
                    AgentTaskCompletionService.CompleteTaskRequest completeReq =
                            new AgentTaskCompletionService.CompleteTaskRequest(
                                    activeProjectRoot, provider, connectionInstanceId, summary, outcome,
                                    expectedIntentId, expectedWorkGroupId, expectedClaimEpoch,
                                    expectedWorkGroupVersion, expectedRevision, expectedParticipant);
                    agentResponse = taskCompletionService.completeTask(completeReq);
                } catch (IllegalArgumentException invalidFinish) {
                    agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                            AgentNextAction.REQUEST_HUMAN_HELP,
                            Map.of("reason", "INVALID_FINISH_LANE_ARGUMENTS"));
                }
            }
            case "synesis." + McpToolCatalog.CANCEL_LANE -> {
                String reason = arguments != null ? (String) arguments.get("reason") : null;
                org.synesis.workspace.application.agent.AgentTaskCancellationService.CancelTaskRequest cancelReq = new org.synesis.workspace.application.agent.AgentTaskCancellationService.CancelTaskRequest(
                        activeProjectRoot, provider, connectionInstanceId, reason);
                agentResponse = taskCancellationService.cancelTask(cancelReq);
            }
            case null, default -> {
                Map<String, Object> textContent = Map.of("type", "text", "text", "Unknown tool: " + name);
                Map<String, Object> result = Map.of("content", List.of(textContent), "isError", true);
                return createResultResponse(id, result);
            }
        }

        Map<String, Object> textContent = Map.of("type", "text", "text", agentResponse.toJson());
        Map<String, Object> result = Map.of("content", List.of(textContent));
        return createResultResponse(id, result);
    }

    private static Map<String, Object> contractMap(ContractRecord contract) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractId", contract.contractId().toString());
        map.put("projectId", contract.projectId().toString());
        map.put("revision", contract.revision());
        map.put("owner", contract.owner());
        map.put("contentHash", contract.contentHash());
        map.put("body", contract.body());
        map.put("status", contract.status().name());
        map.put("supersedes", contract.supersedes() == null ? null : contract.supersedes().toString());
        map.put("selectorRefs", contract.selectorRefs());
        return map;
    }

    private static Map<String, Object> dependencyMap(ContractDependency dependency) {
        return Map.of("intentId", dependency.intentId().toString(),
                "participant", dependency.participant(),
                "contractId", dependency.contractId().toString(),
                "revision", dependency.revision(),
                "state", dependency.state().name());
    }

    /** Converts the collaboration projection to a JSON-safe discovery payload. */
    private static Map<String, Object> collaborationStatusMap(WorkspaceCollaborationService.CollaborationSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intents", snapshot.intents().stream().map(McpProtocolHandler::intentMap).toList());
        result.put("requests", snapshot.requests().stream().map(McpProtocolHandler::requestMap).toList());
        result.put("participants", snapshot.participants().stream().map(McpProtocolHandler::participantMap).toList());
        result.put("groups", snapshot.groups().stream().map(McpProtocolHandler::workGroupMap).toList());
        result.put("grants", snapshot.grants().stream().map(McpProtocolHandler::laneGrantMap).toList());
        result.put("snapshots", snapshot.snapshots().stream().map(McpProtocolHandler::snapshotMap).toList());
        return result;
    }

    /** Converts one logical work group to a JSON-safe map. */
    private static Map<String, Object> workGroupMap(WorkGroup group) {
        return Map.of("workGroupId", group.workGroupId().toString(),
                "projectId", group.projectId().toString(), "goal", group.goal(),
                "acceptance", group.acceptance(), "version", group.version(),
                "status", group.status().name());
    }

    /** Converts one targeted grant to a JSON-safe map. */
    private static Map<String, Object> laneGrantMap(LaneGrant grant) {
        return Map.of("grantId", grant.grantId().toString(),
                "workGroupId", grant.workGroupId().toString(),
                "targetIntentId", grant.targetIntentId().toString(),
                "reviewedIntentId", grant.targetIntentId().toString(),
                "targetParticipant", grant.targetParticipant(),
                "reviewerParticipant", grant.targetParticipant(),
                "claimEpoch", grant.claimEpoch(), "singleUse", grant.singleUse());
    }

    /** Converts one immutable task snapshot to a JSON-safe review projection. */
    private static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", snapshot.taskId().toString());
        result.put("snapshotId", snapshot.snapshotId());
        result.put("baseCommit", snapshot.baseCommit());
        result.put("commitSha", snapshot.commitSha());
        result.put("changedPaths", snapshot.changedPaths());
        result.put("summary", snapshot.summary());
        result.put("createdAtMillis", snapshot.createdAtMillis());
        result.put("laneId", snapshot.provenance().laneId().toString());
        result.put("claimEpoch", snapshot.provenance().claimEpoch());
        return result;
    }

    /**
     * Reads the bounded validation evidence accepted by the integration-check
     * compatibility adapter. Explicit structured results remain authoritative;
     * the legacy provider text forms are accepted only for an unambiguous
     * passing count and are rejected when failure wording is present.
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
            return "completed".equalsIgnoreCase(String.valueOf(outcome))
                    && exitCode instanceof Number number && number.intValue() == 0;
        }
        if (testResult instanceof String text && passingTestText(text)) {
            return true;
        }
        Object tests = check.get("tests");
        if (tests instanceof List<?> entries) {
            return entries.stream().anyMatch(entry -> entry instanceof String text && passingTestText(text))
                    && entries.stream().noneMatch(entry -> entry instanceof String text
                            && text.toLowerCase(java.util.Locale.ROOT)
                                    .matches("(?s).*\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b.*"));
        }
        return false;
    }

    private static boolean passingTestText(String text) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("(?s).*\\b\\d+\\s+passed\\b.*")
                && !normalized.matches("(?s).*\\b(?:failed|failure|error|errors|exit\\s*code\\s*[1-9]\\d*)\\b.*");
    }

    /** Converts one work intent to a JSON-safe map. */
    private static Map<String, Object> intentMap(WorkIntent intent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intentId", intent.intentId().toString());
        result.put("projectId", intent.projectId().toString());
        result.put("participant", intent.participant());
        result.put("provider", intent.provider());
        result.put("taskId", intent.taskId().toString());
        result.put("goal", intent.goal());
        result.put("acceptance", intent.acceptance());
        result.put("baseCommit", intent.baseCommit());
        result.put("selectors", intent.selectors().stream().map(McpProtocolHandler::selectorMap).toList());
        result.put("version", intent.version());
        result.put("workGroupId", intent.workGroupId().toString());
        result.put("authorityLineageId", intent.authorityLineageId().toString());
        result.put("status", intent.status().name());
        result.put("completionMode", intent.completionMode().wireValue());
        result.put("role", intent.role().wireValue());
        result.put("reviewTargets", intent.reviewTargetSelectors().stream()
                .map(McpProtocolHandler::selectorMap).toList());
        return result;
    }

    /** Converts one participant projection to a JSON-safe map. */
    private static Map<String, Object> participantMap(Participant participant) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", participant.id());
        result.put("provider", participant.provider());
        result.put("goal", participant.goal());
        result.put("state", participant.state().name());
        result.put("lastVerifiedActivity", participant.lastVerifiedActivity());
        result.put("claims", participant.claims().stream().map(McpProtocolHandler::selectorMap).toList());
        result.put("recoveryHeld", participant.state() == Participant.State.RECOVERY_HELD);
        return result;
    }

    /** Converts one coordination request to a JSON-safe map. */
    private static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", request.requestId().toString());
        result.put("projectId", request.projectId().toString());
        result.put("requester", request.requester());
        result.put("target", request.target());
        result.put("conflictingIntentId", request.conflictingIntentId().toString());
        result.put("reviewedIntentId", request.conflictingIntentId().toString());
        result.put("reviewedParticipantId", request.target());
        result.put("reviewerParticipant", request.requester());
        result.put("kind", request.kind().name());
        result.put("proposal", request.proposal());
        result.put("status", request.status().name());
        return result;
    }

    /** Converts one selector to a JSON-safe map. */
    private static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of("kind", selector.kind().name(), "path", selector.value());
    }

    @SuppressWarnings("unchecked")
    private CapabilityContract parseContract(Object contractObj) {
        if (!(contractObj instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) map;
        String inputs = (String) m.get("inputs");
        String output = (String) m.get("output");

        List<String> requiredBehavior = new java.util.ArrayList<>();
        if (m.get("requiredBehavior") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    requiredBehavior.add(s);
                }
            }
        }

        List<String> acceptanceTests = new java.util.ArrayList<>();
        if (m.get("acceptanceTests") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    acceptanceTests.add(s);
                }
            }
        }

        if (inputs == null || output == null) {
            return null;
        }
        try {
            return new CapabilityContract(inputs, output, requiredBehavior, acceptanceTests);
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private AgentSessionService.AgentTaskIntent parseTaskIntent(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object taskObj = arguments.get("task");
        Map<String, Object> map;
        if (taskObj instanceof Map<?, ?> taskMap) {
            map = (Map<String, Object>) taskMap;
        } else if (arguments.containsKey("goal") || arguments.containsKey("acceptance")
                || arguments.containsKey("claims") || arguments.containsKey("role")
                || arguments.containsKey("reviewTargets")) {
            // A few provider renderers flatten the task object.  Normalize
            // that bounded shape without changing the advertised schema.
            map = arguments;
        } else {
            return null;
        }
        String goal = (String) map.get("goal");
        String acceptance = (String) map.get("acceptance");
        List<String> likelyScopes = (List<String>) map.get("likelyScopes");
        List<String> knownDependencies = (List<String>) map.get("knownDependencies");
        UUID workGroupId = null;
        if (map.get("workGroupId") instanceof String value && !value.isBlank()) {
            try { workGroupId = UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        }
        WorkIntent.CompletionMode completionMode = WorkIntent.CompletionMode.SNAPSHOT_REQUIRED;
        Object rawCompletionMode = map.get("completionMode");
        if (rawCompletionMode != null) {
            if (!(rawCompletionMode instanceof String value)) {
                throw new IllegalArgumentException("completion mode must be a string");
            }
            completionMode = WorkIntent.CompletionMode.fromWire(value);
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
        return new AgentSessionService.AgentTaskIntent(goal, acceptance, likelyScopes, knownDependencies,
                workGroupId, completionMode, role, reviewTargetSelectors);
    }

    /** Parses the bounded non-ownership selectors used to identify review targets. */
    private List<ResourceSelector> parseSelectorList(Object rawSelectors) {
        if (rawSelectors == null) {
            return List.of();
        }
        if (!(rawSelectors instanceof List<?> entries)) {
            throw new IllegalArgumentException("reviewTargets must be an array");
        }
        List<ResourceSelector> selectors = new java.util.ArrayList<>();
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
            String kind = rawKind == null
                    ? "path_exact"
                    : rawKind instanceof String kindValue
                            ? kindValue.toLowerCase(java.util.Locale.ROOT) : null;
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

    @SuppressWarnings("unchecked")
    private List<ResourceSelector> parseClaimSelectors(Map<String, Object> arguments) {
        if (arguments == null) {
            return List.of();
        }
        Object taskObj = arguments.get("task");
        Object claimsObj = taskObj instanceof Map<?, ?> taskMap ? taskMap.get("claims") : arguments.get("claims");
        if (!(claimsObj instanceof List<?> claims)) {
            return List.of();
        }
        List<ResourceSelector> selectors = new java.util.ArrayList<>();
        for (Object item : claims) {
            if (item instanceof Map<?, ?> claim) {
                Object path = claim.get("path");
                if (!(path instanceof String)) {
                    path = claim.get("relativePath");
                }
                String kind = claim.get("kind") instanceof String value
                        ? value.toLowerCase(java.util.Locale.ROOT) : "path_exact";
                if (path instanceof String value) {
                    selectors.add("path_subtree".equals(kind)
                            ? ResourceSelector.pathSubtree(value) : ResourceSelector.pathExact(value));
                }
            }
        }
        return List.copyOf(selectors);
    }

    private boolean claimsFieldSpecified(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        return arguments.get("task") instanceof Map<?, ?> taskMap
                ? taskMap.containsKey("claims") : arguments.containsKey("claims");
    }

    @SuppressWarnings("unchecked")
    private static String taskField(Map<String, Object> arguments, String field) {
        if (arguments == null || !(arguments.get("task") instanceof Map<?, ?> taskMap)) return null;
        Object value = ((Map<String, Object>) taskMap).get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String requiredStringArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !(arguments.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    private static String optionalStringArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return null;
        }
        if (!(arguments.get(key) instanceof String value)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

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
     * Returns the first non-blank string under the canonical key or a bounded
     * provider-shaped alias.  Aliases are input normalization only: the raw
     * ten-tool catalog remains canonical and authorization is unchanged.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param canonical canonical wire key
     * @param alias provider-shaped spelling accepted at dispatch
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

    private static boolean requiresManualAttestation(String name, Map<String, Object> arguments) {
        return switch (name) {
            case "synesis." + McpToolCatalog.APPLY_PATCH, "synesis." + McpToolCatalog.RUN_COMMAND,
                 "synesis." + McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION, "synesis." + McpToolCatalog.FINISH_LANE -> true;
            case "synesis." + McpToolCatalog.ENSURE_SESSION -> arguments != null
                    && (Boolean.TRUE.equals(arguments.get("unwindCompletion"))
                    || taskField(arguments, "repairIntentId") != null
                    || taskField(arguments, "repairSnapshotId") != null);
            case "synesis." + McpToolCatalog.REQUEST_COORDINATION -> {
                // Status/discovery is a safe read.  Contract, scope, handoff,
                // continuation, and join operations increase authority.
                String operation = arguments == null ? null : String.valueOf(arguments.get("collaborationOperation"));
                String kind = arguments == null ? null : String.valueOf(arguments.get("kind"));
                yield !("status".equals(operation) || "status".equals(arguments == null ? null : arguments.get("coordinationRequest")))
                        && (kind == null || !kind.isBlank() || operation == null || !operation.isBlank());
            }
            case "synesis." + McpToolCatalog.RESPOND_COORDINATION -> arguments == null || arguments.get("kind") != null;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
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
            case "work_group_join" -> List.of("workGroupId", "grantId", "intentId", "claimEpoch",
                    "targetParticipant", "proposal", "reviewedIntentId", "reviewedParticipantId",
                    "reviewerParticipant");
            case "continuation" -> List.of("grantId", "intentId", "claimEpoch");
            default -> throw new IllegalArgumentException("UNKNOWN_COORDINATION_KIND");
        };
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("COORDINATION_FIELD_NOT_ALLOWED:" + key);
        }
        List<String> required = switch (kind) {
            case "capability_request" -> List.of("capability", "contract");
            case "collaboration_status" -> List.of();
            case "contract_proposal" -> List.of("contractId", "body");
            case "contract_request" -> List.of("conflictingIntentId", "proposal");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal");
            case "work_group_join" -> payload.containsKey("grantId")
                    ? List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant")
                    : List.of("workGroupId", "intentId", "proposal");
            case "continuation" -> List.of("grantId", "intentId", "claimEpoch");
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
        if ("contract_request".equals(kind) || "scope_revision".equals(kind)) {
            normalized.putIfAbsent("collaborationIntentId", payload.get("conflictingIntentId"));
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

    @SuppressWarnings("unchecked")
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
            case "implementation_validation" -> List.of("inboxItemId", "capabilityRequestHandle", "implementationRevision", "result", "reason",
                    "failedAcceptanceTests");
            case "review_validation" -> List.of("grantId", "snapshotId", "intentId", "claimEpoch", "result", "reason",
                    "reviewedIntentId", "reviewedParticipantId", "reviewerParticipant");
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
            if (!(response instanceof String value)
                    || !("accept".equals(value) || "revise".equals(value) || "reject".equals(value))) {
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
            if (!(result instanceof String reviewResult)
                    || !("accept".equalsIgnoreCase(reviewResult) || "accepted".equalsIgnoreCase(reviewResult)
                    || "reject".equalsIgnoreCase(reviewResult) || "rejected".equalsIgnoreCase(reviewResult))) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_INVALID_RESULT");
            }
            if (("reject".equalsIgnoreCase(String.valueOf(result))
                    || "rejected".equalsIgnoreCase(String.valueOf(result)))
                    && (!(payload.get("reason") instanceof String reason) || reason.isBlank())) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_REASON_REQUIRED");
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("kind", kind);
        normalized.put("payload", payload);
        return normalized;
    }

    /** Requires a provider-facing alias to repeat the same canonical value. */
    private static void requireMatchingAlias(Map<String, Object> payload, String canonical, String alias) {
        if (!payload.containsKey(alias) || !payload.containsKey(canonical)) {
            return;
        }
        Object canonicalValue = payload.get(canonical);
        Object aliasValue = payload.get(alias);
        if (!(canonicalValue instanceof String canonicalText) || canonicalText.isBlank()
                || !(aliasValue instanceof String aliasText) || aliasText.isBlank()
                || !canonicalText.equals(aliasText)) {
            throw new IllegalArgumentException("COORDINATION_ALIAS_MISMATCH:" + alias);
        }
    }

    /** Requires an optional provider-facing identity alias to be non-blank text. */
    private static void requireOptionalText(Map<String, Object> payload, String key) {
        if (payload.containsKey(key)
                && (!(payload.get(key) instanceof String value) || value.isBlank())) {
            throw new IllegalArgumentException("COORDINATION_FIELD_INVALID:" + key);
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static boolean isStrictInteger(Number number) {
        if (number instanceof Double || number instanceof Float) {
            return false;
        }
        long value = number.longValue();
        return number.doubleValue() == value;
    }

    private String createResultResponse(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return ProviderJson.write(response);
    }

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
}
