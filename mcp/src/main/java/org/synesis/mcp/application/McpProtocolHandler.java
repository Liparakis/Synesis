package org.synesis.mcp.application;

import org.synesis.mcp.contract.McpToolCatalog;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.synesis.workspace.application.capability.CapabilityRequestService;
import org.synesis.workspace.application.capability.CapabilityResponseService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.integration.IntegrationCompatibilityService;
import org.synesis.workspace.application.integration.WorkspaceIntegrationReadinessService;
import org.synesis.workspace.application.project.ProjectCommandIntent;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.workspace.WorkspacePatchService;
import org.synesis.workspace.application.workspace.WorkspaceReadService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

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
    private final AgentNextActionService nextActionService;
    private final CapabilityRequestService capabilityRequestService;
    private final CapabilityResponseService capabilityResponseService;
    private final ImplementationPublicationService publicationService;
    private final ImplementationValidationService validationService;
    private final AgentTaskCompletionService taskCompletionService;
    private final org.synesis.workspace.application.agent.AgentTaskCancellationService taskCancellationService;
    private final WorkspaceCollaborationService collaborationService;
    private final SessionAuthorityResolver authorityResolver;
    private final SessionLeaseService leaseService;
    private final SessionLeasePolicy leasePolicy;
    private final ProviderManualService manualService;
    private final Path initialProjectRoot;
    private Path activeProjectRoot;
    private boolean isSessionBound;
    private final String provider;
    private final String connectionInstanceId;
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
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.readService = new WorkspaceReadService();
        this.patchService = new WorkspacePatchService();
        this.commandService = new ProjectCommandService();
        this.nextActionService = new AgentNextActionService();
        this.capabilityRequestService = new CapabilityRequestService();
        this.capabilityResponseService = new CapabilityResponseService();
        this.publicationService = new ImplementationPublicationService();
        this.validationService = new ImplementationValidationService();
        this.taskCompletionService = new AgentTaskCompletionService();
        this.taskCancellationService = new org.synesis.workspace.application.agent.AgentTaskCancellationService();
        this.collaborationService = new WorkspaceCollaborationService();
        this.authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
        this.leaseService = new SessionLeaseService();
        this.leasePolicy = new SessionLeasePolicy();
        this.manualService = new ProviderManualService();
        this.initialProjectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.activeProjectRoot = projectRoot;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.connectionInstanceId = Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
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

    private String legacyHandleToolsList(Object id) {
        // Tool 1: synesis.ensure_session
        Map<String, Object> taskProperties = new LinkedHashMap<>();
        taskProperties.put("goal", Map.of("type", "string"));
        taskProperties.put("acceptance", Map.of("type", "string"));
        taskProperties.put("likelyScopes", Map.of("type", "array", "items", Map.of("type", "string")));
        taskProperties.put("knownDependencies", Map.of("type", "array", "items", Map.of("type", "string")));
        taskProperties.put("workGroupId", Map.of("type", "string", "format", "uuid"));
        taskProperties.put("unwindCompletion", Map.of("type", "boolean",
                "description", "Authorized unwind of this caller's prepared but unpublished completion"));
        taskProperties.put("repairIntentId", Map.of("type", "string", "format", "uuid"));
        taskProperties.put("repairSnapshotId", Map.of("type", "string"));
        taskProperties.put("claims", Map.of("type", "array", "items", Map.of(
                "type", "object", "required", List.of("path"), "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "kind", Map.of("type", "string", "enum", List.of("path_exact", "path_subtree"))))));

        Map<String, Object> taskSchema = new LinkedHashMap<>();
        taskSchema.put("type", "object");
        taskSchema.put("properties", taskProperties);

        Map<String, Object> ensureSessionProperties = new LinkedHashMap<>();
        ensureSessionProperties.put("task", taskSchema);
        ensureSessionProperties.put("refresh", Map.of("type", "boolean"));

        Map<String, Object> ensureSessionSchema = new LinkedHashMap<>();
        ensureSessionSchema.put("type", "object");
        ensureSessionSchema.put("properties", ensureSessionProperties);

        Map<String, Object> ensureSessionTool = new LinkedHashMap<>();
        ensureSessionTool.put("name", McpToolCatalog.ENSURE_SESSION);
        ensureSessionTool.put("description", "Ensures an active, verified Synesis workspace session.");
        ensureSessionTool.put("inputSchema", ensureSessionSchema);

        // Tool 2: synesis.read_file
        Map<String, Object> readProperties = new LinkedHashMap<>();
        readProperties.put("path", Map.of("type", "string", "description", "Repository-relative file path"));
        readProperties.put("startLine",
                Map.of("type", "integer", "description", "1-based starting line number (default: 1)"));
        readProperties.put("endLine",
                Map.of("type", "integer", "description", "1-based ending line number (default: EOF)"));
        readProperties.put("maxBytes",
                Map.of("type", "integer", "description", "Maximum UTF-8 bytes to return (default: 65536)"));

        Map<String, Object> readSchema = new LinkedHashMap<>();
        readSchema.put("type", "object");
        readSchema.put("properties", readProperties);
        readSchema.put("required", List.of("path"));

        Map<String, Object> readFileTool = new LinkedHashMap<>();
        readFileTool.put("name", McpToolCatalog.READ_FILE);
        readFileTool.put("description", "Reads text file content from the assigned worktree.");
        readFileTool.put("inputSchema", readSchema);

        // Tool 3: synesis.apply_patch
        Map<String, Object> editProperties = new LinkedHashMap<>();
        editProperties.put("find", Map.of("type", "string"));
        editProperties.put("replace", Map.of("type", "string"));
        editProperties.put("expectedOccurrences", Map.of("type", "integer"));

        Map<String, Object> editSchema = new LinkedHashMap<>();
        editSchema.put("type", "object");
        editSchema.put("properties", editProperties);
        editSchema.put("required", List.of("find", "replace", "expectedOccurrences"));

        Map<String, Object> patchProperties = new LinkedHashMap<>();
        patchProperties.put("path", Map.of("type", "string", "description", "Repository-relative file path"));
        patchProperties.put("create", Map.of("type", "boolean", "description", "Set true for new file creation"));
        patchProperties.put("content", Map.of("type", "string", "description", "Full file content for creation mode"));
        patchProperties.put("expectedHash",
                Map.of("type",
                        "string",
                        "description",
                        "SHA-256 hex string of existing contentHash returned by synesis.read_file (required for modification)"));
        patchProperties.put("edits",
                Map.of("type",
                        "array",
                        "items",
                        editSchema,
                        "description",
                        "List of replacement edits (required for modification)"));

        Map<String, Object> patchSchema = new LinkedHashMap<>();
        patchSchema.put("type", "object");
        patchSchema.put("properties", patchProperties);
        patchSchema.put("required", List.of("path"));

        Map<String, Object> applyPatchTool = new LinkedHashMap<>();
        applyPatchTool.put("name", McpToolCatalog.APPLY_PATCH);
        applyPatchTool.put("description",
                "Applies a structured file creation or modification patch to the assigned worktree.");
        applyPatchTool.put("inputSchema", patchSchema);

        Map<String, Object> runCmdProperties = new LinkedHashMap<>();
        runCmdProperties.put("type",
                Map.of("type",
                        "string",
                        "description",
                        "Command intent classification: build, test, lint, format_check, git_status, git_diff, git_log"));
        runCmdProperties.put("target",
                Map.of("type", "string", "description", "Optional target specifier or test filter"));
        runCmdProperties.put("arguments",
                Map.of("type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Optional additional arguments"));

        Map<String, Object> runCmdSchema = new LinkedHashMap<>();
        runCmdSchema.put("type", "object");
        runCmdSchema.put("properties", runCmdProperties);
        runCmdSchema.put("required", List.of("type"));

        Map<String, Object> runCommandTool = new LinkedHashMap<>();
        runCommandTool.put("name", McpToolCatalog.RUN_COMMAND);
        runCommandTool.put("description",
                "Executes an approved project build or git command intent inside the assigned worktree.");
        runCommandTool.put("inputSchema", runCmdSchema);

        Map<String, Object> nextActionSchema = Map.of("type", "object", "properties", Map.of(
                "integrationCheck", Map.of("type", "object", "description", "Explicit pre-merge candidate facts")));
        Map<String, Object> getNextActionTool = new LinkedHashMap<>();
        getNextActionTool.put("name", McpToolCatalog.GET_NEXT_ACTION);
        getNextActionTool.put("description",
                "Retrieves the single highest-priority actionable coordination item for the active MCP session.");
        getNextActionTool.put("inputSchema", nextActionSchema);

        // Tool 6: synesis.request_coordination
        Map<String, Object> contractProperties = new LinkedHashMap<>();
        contractProperties.put("inputs", Map.of("type", "string", "description", "Input parameter specification"));
        contractProperties.put("output", Map.of("type", "string", "description", "Output return type and semantics"));
        contractProperties.put("requiredBehavior",
                Map.of("type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "List of operational behavior requirements"));
        contractProperties.put("acceptanceTests",
                Map.of("type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "List of acceptance test criteria"));

        Map<String, Object> contractSchema = new LinkedHashMap<>();
        contractSchema.put("type", "object");
        contractSchema.put("properties", contractProperties);

        Map<String, Object> describeSchema = new LinkedHashMap<>();
        describeSchema.put("type", "object");
        Map<String, Object> strictPayload = new LinkedHashMap<>();
        strictPayload.put("type", "object");
        strictPayload.put("additionalProperties", false);
        Map<String, Object> strictPayloadProperties = new LinkedHashMap<>();
        strictPayloadProperties.put("conflictingIntentId", Map.of("type", "string", "format", "uuid"));
        strictPayloadProperties.put("intentId", Map.of("type", "string", "format", "uuid"));
        strictPayloadProperties.put("contractId", Map.of("type", "string", "format", "uuid"));
        strictPayloadProperties.put("body", Map.of("type", "string"));
        strictPayloadProperties.put("selectors", Map.of("type", "array", "items", Map.of("type", "string")));
        strictPayloadProperties.put("revision", Map.of("type", "integer", "minimum", 1));
        strictPayloadProperties.put("targetParticipant", Map.of("type", "string"));
        strictPayloadProperties.put("proposal", Map.of("type", "string"));
        strictPayloadProperties.put("artifact", Map.of("type", "string"));
        strictPayloadProperties.put("capability", Map.of("type", "string"));
        strictPayloadProperties.put("contract", contractSchema);
        strictPayloadProperties.put("capabilityRequestHandle", Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$"));
        strictPayloadProperties.put("revisionResponse", Map.of("type", "string", "enum", List.of("accept", "counter", "cancel")));
        strictPayloadProperties.put("workGroupId", Map.of("type", "string", "format", "uuid"));
        strictPayloadProperties.put("grantId", Map.of("type", "string", "format", "uuid"));
        strictPayloadProperties.put("claimEpoch", Map.of("type", "integer", "minimum", 1));
        strictPayload.put("properties", strictPayloadProperties);
        describeSchema.put("properties", Map.of(
                "kind", Map.of("type", "string", "enum", List.of("capability_request", "collaboration_status", "contract_proposal", "contract_request",
                        "scope_revision", "handoff", "work_group_join", "continuation")),
                "payload", strictPayload));
        describeSchema.put("required", List.of("kind", "payload"));
        describeSchema.put("additionalProperties", false);

        Map<String, Object> describeTool = new LinkedHashMap<>();
        describeTool.put("name", McpToolCatalog.REQUEST_COORDINATION);
        describeTool.put("description",
                "Submits one strict capability or collaboration request; discovery is returned by get_next_action.");
        describeTool.put("inputSchema", describeSchema);

        // Tool 7: synesis.respond_coordination
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("capabilityRequestHandle", Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$"));
        responsePayload.put("response", Map.of("type", "string", "enum", List.of("accept", "revise", "reject")));
        responsePayload.put("revision", contractSchema);
        responsePayload.put("reason", Map.of("type", "string"));
        responsePayload.put("coordinationRequest", Map.of("type", "string", "format", "uuid"));
        responsePayload.put("coordinationStatus", Map.of("type", "string", "enum", List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        responsePayload.put("proposal", Map.of("type", "string"));
        responsePayload.put("inboxItemId", Map.of("type", "string", "format", "uuid"));
        responsePayload.put("resolution", Map.of("type", "string", "enum", List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        responsePayload.put("result", Map.of("type", "string", "enum", List.of("accepted", "revision_required")));
        responsePayload.put("implementationRevision", Map.of("type", "integer", "minimum", 1));
        responsePayload.put("failedAcceptanceTests", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> respondSchema = new LinkedHashMap<>();
        respondSchema.put("type", "object");
        respondSchema.put("properties", Map.of(
                "kind", Map.of("type", "string", "enum", List.of("capability_response", "coordination_response",
                        "inbox_acknowledge", "inbox_resolve", "implementation_validation")),
                "payload", Map.of("type", "object", "properties", responsePayload,
                        "additionalProperties", false)));
        respondSchema.put("required", List.of("kind", "payload"));
        respondSchema.put("additionalProperties", false);

        Map<String, Object> respondTool = new LinkedHashMap<>();
        respondTool.put("name", McpToolCatalog.RESPOND_COORDINATION);
        respondTool.put("description", "Responds to a pending capability request as the authorized capability owner.");
        respondTool.put("inputSchema", respondSchema);

        // Tool 8: synesis.publish_capability_implementation
        Map<String, Object> publishProperties = new LinkedHashMap<>();
        publishProperties.put("capabilityRequestHandle", Map.of("type", "string", "pattern", "^req_[A-Za-z0-9]{12,64}$",
                "description", "Server-issued capability request handle"));
        publishProperties.put("summary",
                Map.of("type", "string", "description", "Human-readable summary of this implementation"));

        Map<String, Object> publishSchema = new LinkedHashMap<>();
        publishSchema.put("type", "object");
        publishSchema.put("properties", publishProperties);
        publishSchema.put("required", List.of("capabilityRequestHandle"));

        Map<String, Object> publishTool = new LinkedHashMap<>();
        publishTool.put("name", McpToolCatalog.PUBLISH_CAPABILITY_IMPLEMENTATION);
        publishTool.put("description",
                "Publishes an immutable implementation revision only for an accepted capability request owned by this caller.");
        publishTool.put("inputSchema", publishSchema);

        // Tool 9: synesis.finish_lane
        Map<String, Object> completeProperties = new LinkedHashMap<>();
        completeProperties.put("summary",
                Map.of("type", "string", "description", "Human-readable summary of completed task work"));

        Map<String, Object> completeSchema = new LinkedHashMap<>();
        completeSchema.put("type", "object");
        completeSchema.put("properties", completeProperties);

        Map<String, Object> completeTaskTool = new LinkedHashMap<>();
        completeTaskTool.put("name", McpToolCatalog.FINISH_LANE);
        completeTaskTool.put("description", "Validates, publishes, integrates, and closes this isolated mutation lane.");
        completeTaskTool.put("inputSchema", completeSchema);

        // Tool 10: synesis.cancel_lane
        Map<String, Object> cancelProperties = new LinkedHashMap<>();
        cancelProperties.put("reason",
                Map.of("type", "string", "description", "Cancellation reason string (1-1000 characters)"));

        Map<String, Object> cancelSchema = new LinkedHashMap<>();
        cancelSchema.put("type", "object");
        cancelSchema.put("properties", cancelProperties);
        cancelSchema.put("required", List.of("reason"));

        Map<String, Object> cancelTaskTool = new LinkedHashMap<>();
        cancelTaskTool.put("name", McpToolCatalog.CANCEL_LANE);
        cancelTaskTool.put("description", "Permanently fences and cancels this isolated mutation lane.");
        cancelTaskTool.put("inputSchema", cancelSchema);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools",
                List.of(ensureSessionTool,
                        readFileTool,
                        applyPatchTool,
                        runCommandTool,
                        getNextActionTool,
                        describeTool,
                        respondTool,
                        publishTool,
                        completeTaskTool,
                        cancelTaskTool));

        return createResultResponse(id, result);
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
        renewLease();

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
                AgentSessionService.AgentTaskIntent taskIntent = parseTaskIntent(arguments);
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
                                    intent == null ? null : intent.workGroupId());
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
                String path = arguments != null ? (String) arguments.get("path") : null;
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
                String path = arguments != null ? (String) arguments.get("path") : null;
                boolean create = arguments != null && Boolean.TRUE.equals(arguments.get("create"));
                String content = arguments != null ? (String) arguments.get("content") : null;
                String expectedHash = arguments != null ? (String) arguments.get("expectedHash") : null;

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
                String type = arguments != null ? (String) arguments.get("type") : null;
                String target = arguments != null ? (String) arguments.get("target") : null;
                List<String> commandArgs = new java.util.ArrayList<>();
                if (arguments != null && arguments.get("arguments") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String s) {
                            commandArgs.add(s);
                        }
                    }
                }

                if (type == null || type.isBlank()) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    try {
                        ProjectCommandIntent intent = new ProjectCommandIntent(type, target, commandArgs);
                        ProjectCommandService.CommandRequest cmdReq = new ProjectCommandService.CommandRequest(
                                activeProjectRoot, provider, connectionInstanceId, intent);
                        agentResponse = commandService.runCommand(cmdReq);
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
                                head, List.of(snapshot), List.of(), Boolean.TRUE.equals(check.get("testsPassed"))));
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
                                            arguments.get("revisionResponse") instanceof String value ? value : null));
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
                                var callerBinding = authorityResolver.resolve(
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
                String summary = arguments != null ? (String) arguments.get("summary") : null;
                AgentTaskCompletionService.CompleteTaskRequest completeReq = new AgentTaskCompletionService.CompleteTaskRequest(
                        activeProjectRoot, provider, connectionInstanceId, summary);
                agentResponse = taskCompletionService.completeTask(completeReq);
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
        return result;
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
        result.put("status", intent.status().name());
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
        if (!(taskObj instanceof Map<?, ?> taskMap)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) taskMap;
        String goal = (String) map.get("goal");
        String acceptance = (String) map.get("acceptance");
        List<String> likelyScopes = (List<String>) map.get("likelyScopes");
        List<String> knownDependencies = (List<String>) map.get("knownDependencies");
        UUID workGroupId = null;
        if (map.get("workGroupId") instanceof String value && !value.isBlank()) {
            try { workGroupId = UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        }
        return new AgentSessionService.AgentTaskIntent(goal, acceptance, likelyScopes, knownDependencies, workGroupId);
    }

    @SuppressWarnings("unchecked")
    private List<ResourceSelector> parseClaimSelectors(Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("task") instanceof Map<?, ?> taskMap)
                || !(taskMap.get("claims") instanceof List<?> claims)) {
            return List.of();
        }
        List<ResourceSelector> selectors = new java.util.ArrayList<>();
        for (Object item : claims) {
            if (item instanceof Map<?, ?> claim) {
                Object path = claim.get("path");
                String kind = claim.get("kind") instanceof String value ? value : "path_exact";
                if (path instanceof String value) {
                    selectors.add("path_subtree".equals(kind)
                            ? ResourceSelector.pathSubtree(value) : ResourceSelector.pathExact(value));
                }
            }
        }
        return List.copyOf(selectors);
    }

    private boolean claimsFieldSpecified(Map<String, Object> arguments) {
        return arguments != null && arguments.get("task") instanceof Map<?, ?> taskMap
                && taskMap.containsKey("claims");
    }

    @SuppressWarnings("unchecked")
    private static String taskField(Map<String, Object> arguments, String field) {
        if (arguments == null || !(arguments.get("task") instanceof Map<?, ?> taskMap)) return null;
        Object value = ((Map<String, Object>) taskMap).get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
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
            case "capability_request" -> List.of("capability", "contract", "capabilityRequestHandle", "revisionResponse");
            case "collaboration_status" -> List.of();
            case "contract_proposal" -> List.of("contractId", "body", "selectors", "revision");
            case "contract_request" -> List.of("conflictingIntentId", "proposal", "contractId", "revision");
            case "scope_revision" -> List.of("intentId", "selectors", "proposal");
            case "handoff" -> List.of("intentId", "targetParticipant", "proposal", "artifact");
            case "work_group_join" -> List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant");
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
            case "work_group_join" -> List.of("workGroupId", "grantId", "intentId", "claimEpoch", "targetParticipant");
            case "continuation" -> List.of("grantId", "intentId", "claimEpoch");
            default -> List.of();
        };
        for (String key : required) {
            if (!payload.containsKey(key) || payload.get(key) == null) {
                throw new IllegalArgumentException("COORDINATION_FIELD_REQUIRED:" + key);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        normalized.remove("kind");
        normalized.put("collaborationOperation", switch (kind) {
            case "capability_request" -> "capability_request";
            case "collaboration_status" -> "status";
            case "contract_proposal" -> "publish";
            case "contract_request", "scope_revision" -> "request_coordination";
            case "handoff" -> "handoff";
            case "work_group_join" -> "lane_grant_consume";
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
            default -> List.of();
        };
        for (String key : required) {
            if (!payload.containsKey(key)) {
                throw new IllegalArgumentException("COORDINATION_RESPONSE_FIELD_REQUIRED:" + key);
            }
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
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("kind", kind);
        normalized.put("payload", payload);
        return normalized;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
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
