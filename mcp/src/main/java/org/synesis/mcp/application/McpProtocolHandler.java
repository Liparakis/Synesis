package org.synesis.mcp.application;

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

    /** Marks a clean stdio shutdown and releases this connection's claims. */
    public void close() {
        try {
            leaseService.markClosedCleanly(activeProjectRoot, connectionInstanceId);
            collaborationService.release(activeProjectRoot, provider, connectionInstanceId);
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
     * otherwise the oldest supported baseline is returned for compatibility with legacy clients.
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
        // Tool 1: synesis.ensure_session
        Map<String, Object> taskProperties = new LinkedHashMap<>();
        taskProperties.put("goal", Map.of("type", "string"));
        taskProperties.put("acceptance", Map.of("type", "string"));
        taskProperties.put("likelyScopes", Map.of("type", "array", "items", Map.of("type", "string")));
        taskProperties.put("knownDependencies", Map.of("type", "array", "items", Map.of("type", "string")));
        taskProperties.put("workGroupId", Map.of("type", "string", "format", "uuid"));
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
        ensureSessionTool.put("name", "ensure_session");
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
        readFileTool.put("name", "read_file");
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
        applyPatchTool.put("name", "apply_patch");
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
        runCommandTool.put("name", "run_command");
        runCommandTool.put("description",
                "Executes an approved project build or git command intent inside the assigned worktree.");
        runCommandTool.put("inputSchema", runCmdSchema);

        Map<String, Object> nextActionSchema = Map.of("type", "object", "properties", Map.of(
                "integrationCheck", Map.of("type", "object", "description", "Explicit pre-merge candidate facts")));
        Map<String, Object> getNextActionTool = new LinkedHashMap<>();
        getNextActionTool.put("name", "get_next_action");
        getNextActionTool.put("description",
                "Retrieves the single highest-priority actionable coordination item for the active MCP session.");
        getNextActionTool.put("inputSchema", nextActionSchema);

        // Tool 6: synesis.describe_required_capability
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

        Map<String, Object> describeProperties = new LinkedHashMap<>();
        describeProperties.put("capability",
                Map.of("type", "string", "description", "Target capability name (e.g. catalog.product-query)"));
        describeProperties.put("contract", contractSchema);
        describeProperties.put("request",
                Map.of("type",
                        "string",
                        "description",
                        "Public capability request handle locator (for requester revision response)"));
        describeProperties.put("revisionResponse",
                Map.of("type",
                        "string",
                        "description",
                        "Requester response type to owner revision: accept, counter, cancel"));
        describeProperties.put("collaborationOperation",
                Map.of("type", "string", "enum", List.of("status", "publish", "bind", "request", "request_coordination", "handoff"),
                        "description", "Shared contract or coordination operation through the collaboration service"));
        describeProperties.put("collaborationContractId", Map.of("type", "string", "description", "UUID of shared contract"));
        describeProperties.put("collaborationIntentId", Map.of("type", "string", "description", "UUID of consuming intent"));
        describeProperties.put("collaborationRevision", Map.of("type", "integer", "description", "Exact contract revision"));
        describeProperties.put("collaborationBody", Map.of("type", "string", "description", "Bounded shared contract body"));
        describeProperties.put("collaborationSelectors", Map.of("type", "array", "items", Map.of("type", "string")));
        describeProperties.put("collaborationRequestKind", Map.of("type", "string", "enum", List.of("CONTRACT", "HANDOFF", "SCOPE_REVISION")));
        describeProperties.put("collaborationProposal", Map.of("type", "string", "description", "Bounded coordination or handoff proposal"));
        describeProperties.put("collaborationTarget", Map.of("type", "string", "description", "Opaque participant target for handoff"));

        Map<String, Object> describeSchema = new LinkedHashMap<>();
        describeSchema.put("type", "object");
        describeSchema.put("properties", describeProperties);

        Map<String, Object> describeTool = new LinkedHashMap<>();
        describeTool.put("name", "describe_required_capability");
        describeTool.put("description",
                "Describes required capability contract or responds to owner revision feedback.");
        describeTool.put("inputSchema", describeSchema);

        // Tool 7: synesis.respond_to_owner_request
        Map<String, Object> respondProperties = new LinkedHashMap<>();
        respondProperties.put("request",
                Map.of("type", "string", "description", "Public capability request handle locator"));
        respondProperties.put("response",
                Map.of("type", "string", "description", "Owner response type: accept, revise, reject"));
        respondProperties.put("revision", contractSchema);
        respondProperties.put("reason",
                Map.of("type", "string", "description", "Explanation for revision or rejection"));
        respondProperties.put("coordinationRequest", Map.of("type", "string", "description", "UUID of coordination or handoff request"));
        respondProperties.put("coordinationStatus", Map.of("type", "string", "enum", List.of("ACCEPTED", "REVISED", "REJECTED", "CANCELLED", "COMPLETED")));
        respondProperties.put("proposal", Map.of("type", "string", "description", "Coordination response proposal"));

        Map<String, Object> respondSchema = new LinkedHashMap<>();
        respondSchema.put("type", "object");
        respondSchema.put("properties", respondProperties);
        respondSchema.put("required", List.of("request", "response"));

        Map<String, Object> respondTool = new LinkedHashMap<>();
        respondTool.put("name", "respond_to_owner_request");
        respondTool.put("description", "Responds to a pending capability request as the authorized capability owner.");
        respondTool.put("inputSchema", respondSchema);

        // Tool 8: synesis.publish_implementation
        Map<String, Object> publishProperties = new LinkedHashMap<>();
        publishProperties.put("request", Map.of("type", "string", "description", "Public capability request handle"));
        publishProperties.put("summary",
                Map.of("type", "string", "description", "Human-readable summary of this implementation"));

        Map<String, Object> publishSchema = new LinkedHashMap<>();
        publishSchema.put("type", "object");
        publishSchema.put("properties", publishProperties);
        publishSchema.put("required", List.of("request"));

        Map<String, Object> publishTool = new LinkedHashMap<>();
        publishTool.put("name", "publish_implementation");
        publishTool.put("description",
                "Publishes an immutable implementation snapshot for a capability request as the authorized owner.");
        publishTool.put("inputSchema", publishSchema);

        // Tool 9: synesis.validate_available_implementation
        Map<String, Object> validateProperties = new LinkedHashMap<>();
        validateProperties.put("request", Map.of("type", "string", "description", "Public capability request handle"));
        validateProperties.put("result",
                Map.of("type", "string", "description", "Validation result: accepted or revision_required"));
        validateProperties.put("reason",
                Map.of("type", "string", "description", "Failure reason when result is revision_required"));
        validateProperties.put("failedAcceptanceTests",
                Map.of("type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Failed acceptance test names"));

        Map<String, Object> validateSchema = new LinkedHashMap<>();
        validateSchema.put("type", "object");
        validateSchema.put("properties", validateProperties);
        validateSchema.put("required", List.of("request", "result"));

        Map<String, Object> validateTool = new LinkedHashMap<>();
        validateTool.put("name", "validate_available_implementation");
        validateTool.put("description",
                "Validates the available implementation snapshot for a capability request as the authorized requester.");
        validateTool.put("inputSchema", validateSchema);

        // Tool 10: synesis.complete_task
        Map<String, Object> completeProperties = new LinkedHashMap<>();
        completeProperties.put("summary",
                Map.of("type", "string", "description", "Human-readable summary of completed task work"));

        Map<String, Object> completeSchema = new LinkedHashMap<>();
        completeSchema.put("type", "object");
        completeSchema.put("properties", completeProperties);

        Map<String, Object> completeTaskTool = new LinkedHashMap<>();
        completeTaskTool.put("name", "complete_task");
        completeTaskTool.put("description", "Requests task completion and triggers dependency integration.");
        completeTaskTool.put("inputSchema", completeSchema);

        // Tool 11: synesis.cancel_task
        Map<String, Object> cancelProperties = new LinkedHashMap<>();
        cancelProperties.put("reason",
                Map.of("type", "string", "description", "Cancellation reason string (1-1000 characters)"));

        Map<String, Object> cancelSchema = new LinkedHashMap<>();
        cancelSchema.put("type", "object");
        cancelSchema.put("properties", cancelProperties);
        cancelSchema.put("required", List.of("reason"));

        Map<String, Object> cancelTaskTool = new LinkedHashMap<>();
        cancelTaskTool.put("name", "cancel_task");
        cancelTaskTool.put("description", "Cancels the active task for the ambient MCP connection.");
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
                        validateTool,
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
        // The wire contract advertises raw names; accept legacy synesis.* calls
        // for one compatibility period and route both forms identically.
        if (name != null && !name.startsWith("synesis.")) {
            name = "synesis." + name;
        }
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        AgentResponse agentResponse;
        renewLease();

        switch (name) {
            case "synesis.ensure_session" -> {
                AgentSessionService.AgentTaskIntent taskIntent = parseTaskIntent(arguments);
                boolean refresh = arguments != null && Boolean.TRUE.equals(arguments.get("refresh"));

                AgentSessionService.SessionResolutionRequest resolutionRequest = new AgentSessionService.SessionResolutionRequest(
                        activeProjectRoot, provider, connectionInstanceId, taskIntent, refresh);

                agentResponse = sessionService.ensureSession(resolutionRequest);
                if (agentResponse.status() == AgentStatus.READY) {
                    isSessionBound = true;
                    // The first verified ensure_session is activity too. Establish the lease
                    // before any claim is announced so an abruptly deleted chat remains
                    // recoverable even when it sends no follow-up MCP request.
                    renewLease();
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
            case "synesis.read_file" -> {
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
            case "synesis.apply_patch" -> {
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
            case "synesis.run_command" -> {
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
            case "synesis.get_next_action" -> {
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
                        agentResponse = new AgentResponse(result.accepted() ? AgentStatus.COMPLETED : AgentStatus.BLOCKED,
                                result.accepted() ? null : AgentReason.INTEGRATION_CONFLICT,
                                result.accepted() ? null : AgentNextAction.REQUEST_HUMAN_HELP,
                                Map.of("accepted", result.accepted(), "failures", result.failures(), "actions", result.actions()));
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
            case "synesis.describe_required_capability" -> {
                String collaborationOperation = arguments != null ? (String) arguments.get("collaborationOperation") : null;
                if (collaborationOperation != null) {
                    try {
                        var result = switch (collaborationOperation) {
                            case "status" -> {
                                var snapshot = collaborationService.contractStatus(activeProjectRoot);
                                yield Map.of("contracts", snapshot.contracts().stream().map(McpProtocolHandler::contractMap).toList(),
                                        "dependencies", snapshot.dependencies().stream().map(McpProtocolHandler::dependencyMap).toList());
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
                                collaborationService.createWorkGroup(activeProjectRoot,
                                        new WorkGroup(groupId, UUID.fromString(String.valueOf(arguments.get("projectId"))),
                                                String.valueOf(arguments.getOrDefault("collaborationGoal", "")),
                                                String.valueOf(arguments.getOrDefault("collaborationAcceptance", "")), 1,
                                                WorkGroup.Status.ACTIVE));
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
                                collaborationService.consumeLaneGrant(activeProjectRoot, grantId,
                                        String.valueOf(arguments.get("targetParticipant")),
                                        UUID.fromString(String.valueOf(arguments.get("intentId"))),
                                        ((Number) arguments.getOrDefault("claimEpoch", 1)).longValue());
                                yield Map.of("grantId", grantId.toString(), "status", "CONSUMED");
                            }
                            case "lane_grant_revoke" -> {
                                UUID grantId = UUID.fromString(String.valueOf(arguments.get("grantId")));
                                collaborationService.revokeLaneGrant(activeProjectRoot, grantId);
                                yield Map.of("grantId", grantId.toString(), "status", "REVOKED");
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
                if (arguments != null && arguments.get("coordinationRequest") instanceof String requestValue) {
                    try {
                        var snapshot = collaborationService.status(activeProjectRoot);
                        agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null,
                                collaborationStatusMap(snapshot));
                        break;
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                                AgentNextAction.REQUEST_HUMAN_HELP, null);
                        break;
                    }
                }
                String capability = arguments != null ? (String) arguments.get("capability") : null;
                String reqHandle = arguments != null ? (String) arguments.get("request") : null;
                String revResp = arguments != null ? (String) arguments.get("revisionResponse") : null;
                CapabilityContract contract = parseContract(arguments != null ? arguments.get("contract") : null);

                CapabilityRequestService.DescribeCapabilityRequest descReq = new CapabilityRequestService.DescribeCapabilityRequest(
                        activeProjectRoot, provider, connectionInstanceId, capability, contract, reqHandle, revResp);
                agentResponse = capabilityRequestService.describeRequiredCapability(descReq);
            }
            case "synesis.respond_to_owner_request" -> {
                if (arguments != null && arguments.get("coordinationRequest") instanceof String requestValue) {
                    try {
                        CoordinationRequest.Status status = CoordinationRequest.Status.valueOf(
                                String.valueOf(arguments.getOrDefault("coordinationStatus", "REJECTED")));
                        collaborationService.respond(activeProjectRoot, provider, connectionInstanceId,
                                java.util.UUID.fromString(requestValue), status,
                                String.valueOf(arguments.getOrDefault("proposal", "")));
                        agentResponse = new AgentResponse(AgentStatus.COMPLETED, null, null,
                                Map.of("coordinationRequest", requestValue, "status", status.name()));
                        break;
                    } catch (Exception failure) {
                        agentResponse = new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                                AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", failure.getMessage()));
                        break;
                    }
                }
                String reqHandle = arguments != null ? (String) arguments.get("request") : null;
                String response = arguments != null ? (String) arguments.get("response") : null;
                String reason = arguments != null ? (String) arguments.get("reason") : null;
                CapabilityContract revision = parseContract(arguments != null ? arguments.get("revision") : null);

                if (reqHandle == null || response == null) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    CapabilityResponseService.OwnerResponseRequest respReq = new CapabilityResponseService.OwnerResponseRequest(
                            activeProjectRoot, provider, connectionInstanceId, reqHandle, response, revision, reason);
                    agentResponse = capabilityResponseService.respondToOwnerRequest(respReq);
                }
            }
            case "synesis.publish_implementation" -> {
                String reqHandle = arguments != null ? (String) arguments.get("request") : null;
                String summary = arguments != null ? (String) arguments.get("summary") : null;

                if (reqHandle == null || reqHandle.isBlank()) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    ImplementationPublicationService.PublishRequest pubReq = new ImplementationPublicationService.PublishRequest(
                            activeProjectRoot, provider, connectionInstanceId, reqHandle, summary);
                    agentResponse = publicationService.publishImplementation(pubReq);
                }
            }
            case "synesis.validate_available_implementation" -> {
                String reqHandle = arguments != null ? (String) arguments.get("request") : null;
                String valResult = arguments != null ? (String) arguments.get("result") : null;
                String valReason = arguments != null ? (String) arguments.get("reason") : null;

                List<String> failedTests = new java.util.ArrayList<>();
                if (arguments != null && arguments.get("failedAcceptanceTests") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String s) {
                            failedTests.add(s);
                        }
                    }
                }

                if (reqHandle == null || valResult == null) {
                    agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
                } else {
                    ImplementationValidationService.ValidateRequest valReq = new ImplementationValidationService.ValidateRequest(
                            activeProjectRoot,
                            provider,
                            connectionInstanceId,
                            reqHandle,
                            valResult,
                            valReason,
                            failedTests);
                    agentResponse = validationService.validateImplementation(valReq);
                }
            }
            case "synesis.complete_task" -> {
                String summary = arguments != null ? (String) arguments.get("summary") : null;
                AgentTaskCompletionService.CompleteTaskRequest completeReq = new AgentTaskCompletionService.CompleteTaskRequest(
                        activeProjectRoot, provider, connectionInstanceId, summary);
                agentResponse = taskCompletionService.completeTask(completeReq);
            }
            case "synesis.cancel_task" -> {
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
