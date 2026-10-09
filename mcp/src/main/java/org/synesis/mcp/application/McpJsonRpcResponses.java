package org.synesis.mcp.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Encodes JSON-RPC 2.0 response frames and MCP text tool results.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because the frames are pure functions of their
 * arguments. Map insertion order here is the wire key order, so the envelope shapes live in one place.
 */
final class McpJsonRpcResponses {

    /** Utility class; these rules are exposed through static operations. */
    private McpJsonRpcResponses() {
    }

    /**
     * Encodes one successful JSON-RPC response envelope.
     *
     * @param id     JSON-RPC request identifier
     * @param result result object
     * @return serialized response frame
     */
    static String createResultResponse(Object id, Map<String, Object> result) {
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
    static String createErrorResponse(Object id, int code, String message) {
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
    static String textResultResponse(Object id, String text) {
        Map<String, Object> content = Map.of("type", "text", "text", text);
        return createResultResponse(id, Map.of("content", List.of(content)));
    }
}
