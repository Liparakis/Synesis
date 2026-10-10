package org.synesis.mcp.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Encodes JSON-RPC 2.0 response frames and MCP text tool results.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because the frames are pure functions of their
 * arguments. Map insertion order here is the wire key order, so the envelope shapes live in one place.
 */
final class McpJsonRpcResponses {

    /** Start of the raw body that reports a missing provider manual attestation; each form adds its own tail. */
    private static final String MANUAL_ATTESTATION_REQUIRED = "{\"status\":\"blocked\",\"reason\":\"manual_attestation_required\"";

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
        return createResultResponse(id, object("content", List.of(object("type", "text", "text", text))));
    }

    /**
     * Encodes a tool result that carries one text content item and is flagged as an error.
     *
     * @param id   JSON-RPC request identifier
     * @param text text content
     * @return serialized response frame
     */
    static String errorTextResultResponse(Object id, String text) {
        return createResultResponse(id, object("content", List.of(object("type", "text", "text", text)), "isError", true));
    }

    /**
     * Encodes the text result that stops an authority-increasing call for want of a valid manual
     * attestation.
     *
     * @param id      JSON-RPC request identifier
     * @param details attestation diagnostic, written after the status and reason
     * @return serialized response frame
     */
    static String manualAttestationRequiredResponse(Object id, Map<String, Object> details) {
        return textResultResponse(id, MANUAL_ATTESTATION_REQUIRED + ",\"details\":" + ProviderJson.write(details) + "}");
    }

    /**
     * Encodes the text result that rejects the ownership claims of an unattested {@code ensure_session}.
     *
     * @param id JSON-RPC request identifier
     * @return serialized response frame
     */
    static String claimsRejectedResponse(Object id) {
        return textResultResponse(id, MANUAL_ATTESTATION_REQUIRED + ",\"claims\":\"REJECTED\"}");
    }

    /**
     * Builds a policy-denied tool reply carrying one detail, the shape every argument rejection uses.
     *
     * @param nextAction what the caller should do next
     * @param key        detail name, {@code reason} or {@code error}
     * @param value      detail text
     * @return blocked response with {@link AgentReason#POLICY_DENIED}
     */
    static AgentResponse policyDenied(AgentNextAction nextAction, String key, String value) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, nextAction, Map.of(key, value));
    }

    /**
     * Builds a JSON object whose keys keep the order given, so the bytes of a reply do not depend on
     * hash iteration order.
     *
     * @param keysAndValues alternating {@code String} keys and values
     * @return insertion-ordered map
     */
    static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
