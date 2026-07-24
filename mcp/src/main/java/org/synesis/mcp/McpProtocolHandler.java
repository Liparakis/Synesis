package org.synesis.mcp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.provider.ProviderJson;

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

    private final AgentSessionService sessionService;
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
    public McpProtocolHandler(AgentSessionService sessionService, Path projectRoot, String provider, String connectionInstanceId) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
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
        } catch (Exception parseFailure) {
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
                case "notifications/roots/list_changed", "roots/list_changed" -> handleRootsListChanged(request.get("params"));
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, (Map<String, Object>) request.get("params"));
                case "roots/list" -> handleRootsList(id);
                default -> createErrorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (Exception failure) {
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
        serverInfo.put("name", "synesis-mcp");
        serverInfo.put("version", "0.1.0-SNAPSHOT");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new LinkedHashMap<>());
        capabilities.put("roots", Map.of("listChanged", true));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        return createResultResponse(id, result);
    }

    private String handleRootsList(Object id) {
        Map<String, Object> rootItem = new LinkedHashMap<>();
        rootItem.put("uri", activeProjectRoot.toUri().toString());
        rootItem.put("name", activeProjectRoot.getFileName() != null ? activeProjectRoot.getFileName().toString() : "root");

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
            if (p != null && !candidates.contains(p)) {
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
            candidates.add(initialProjectRoot.toAbsolutePath().normalize());
        }

        // Scan Antigravity per-project configs (~/.gemini/config/projects/*.json)
        try {
            if (antigravityProjectsDir != null && java.nio.file.Files.isDirectory(antigravityProjectsDir)) {
                try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(antigravityProjectsDir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(configFile -> {
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
            Path cwd = Path.of(".").toAbsolutePath().normalize();
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
            trimmed = java.net.URLDecoder.decode(trimmed, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }

        if (trimmed.startsWith("file:")) {
            try {
                return Path.of(java.net.URI.create(trimmed)).toAbsolutePath().normalize();
            } catch (Exception ex) {
                String raw = trimmed.substring(5);
                while (raw.startsWith("/")) {
                    raw = raw.substring(1);
                }
                return Path.of(raw).toAbsolutePath().normalize();
            }
        }

        try {
            return Path.of(trimmed).toAbsolutePath().normalize();
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
        Path homePath = (userHome != null && !userHome.isBlank()) ? Path.of(userHome).toAbsolutePath().normalize() : null;

        for (Path candidate : candidates) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                String normStr = normalized.toString().replace('\\', '/');
                if (normStr.contains("/.synesis/local/worktrees/")) {
                    continue; // Reject assigned worktree path as control project root
                }
                if (homePath != null && normalized.equals(homePath)) {
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
        Map<String, Object> taskProperties = new LinkedHashMap<>();
        taskProperties.put("goal", Map.of("type", "string"));
        taskProperties.put("acceptance", Map.of("type", "string"));
        taskProperties.put("likelyScopes", Map.of("type", "array", "items", Map.of("type", "string")));
        taskProperties.put("knownDependencies", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> taskSchema = new LinkedHashMap<>();
        taskSchema.put("type", "object");
        taskSchema.put("properties", taskProperties);

        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("task", taskSchema);
        inputProperties.put("refresh", Map.of("type", "boolean"));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", inputProperties);

        Map<String, Object> ensureSessionTool = new LinkedHashMap<>();
        ensureSessionTool.put("name", "synesis.ensure_session");
        ensureSessionTool.put("description", "Ensures an active, verified Synesis workspace session.");
        ensureSessionTool.put("inputSchema", inputSchema);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", List.of(ensureSessionTool));

        return createResultResponse(id, result);
    }

    @SuppressWarnings("unchecked")
    private String handleToolsCall(Object id, Map<String, Object> params) {
        if (params == null) {
            return createErrorResponse(id, -32602, "Invalid params: missing params object");
        }

        String name = (String) params.get("name");
        if (!"synesis.ensure_session".equals(name)) {
            Map<String, Object> textContent = Map.of("type", "text", "text", "Unknown tool: " + name);
            Map<String, Object> result = Map.of("content", List.of(textContent), "isError", true);
            return createResultResponse(id, result);
        }

        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        AgentSessionService.AgentTaskIntent taskIntent = parseTaskIntent(arguments);
        boolean refresh = arguments != null && Boolean.TRUE.equals(arguments.get("refresh"));

        AgentSessionService.SessionResolutionRequest resolutionRequest = new AgentSessionService.SessionResolutionRequest(
                activeProjectRoot, provider, connectionInstanceId, taskIntent, refresh);

        AgentResponse agentResponse = sessionService.ensureSession(resolutionRequest);
        if (agentResponse.status() == AgentStatus.READY) {
            isSessionBound = true;
        }

        Map<String, Object> textContent = Map.of("type", "text", "text", agentResponse.toJson());
        Map<String, Object> result = Map.of("content", List.of(textContent));
        return createResultResponse(id, result);
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
        return new AgentSessionService.AgentTaskIntent(goal, acceptance, likelyScopes, knownDependencies);
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
