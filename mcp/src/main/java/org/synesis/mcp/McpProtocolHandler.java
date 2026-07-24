package org.synesis.mcp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentSessionService;
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
    private final Path projectRoot;
    private final String provider;
    private final String connectionInstanceId;

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
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.connectionInstanceId = Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
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
                case "initialize" -> handleInitialize(id);
                case "initialized", "notifications/initialized" -> null;
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, (Map<String, Object>) request.get("params"));
                default -> createErrorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (Exception failure) {
            return createErrorResponse(id, -32603, "Internal error: " + failure.getMessage());
        }
    }

    private String handleInitialize(Object id) {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "synesis-mcp");
        serverInfo.put("version", "0.1.0-SNAPSHOT");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new LinkedHashMap<>());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);

        return createResultResponse(id, result);
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
                projectRoot, provider, connectionInstanceId, taskIntent, refresh);

        AgentResponse agentResponse = sessionService.ensureSession(resolutionRequest);

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
