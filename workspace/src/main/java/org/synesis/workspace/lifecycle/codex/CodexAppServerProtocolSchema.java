package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generated-local Codex App Server protocol schema projection.
 *
 * <p>This Codex-only projection is the protocol authority used by the
 * lifecycle client. It intentionally contains no provider-neutral interface;
 * when the installed App Server schema is regenerated, this projection is
 * regenerated with it. Unknown event extensions are retained as bounded
 * events, while outbound lifecycle methods and JSON-RPC frame shape remain
 * strict.
 *
 * @since 1.0
 */
public final class CodexAppServerProtocolSchema {

    /**
     * Maximum method-name UTF-8 bytes accepted by the local schema.
     */
    public static final int MAX_METHOD_BYTES = 256;

    private static final Set<String> REQUEST_METHODS = Set.of(
            "initialize", "thread/start", "thread/resume", "thread/read", "turn/start", "turn/steer",
            "turn/interrupt");
    private static final Set<String> CLIENT_NOTIFICATIONS = Set.of("initialized");
    private static final Set<String> SERVER_REQUEST_METHODS = Set.of(
            "account/chatgptAuthTokens/refresh", "applyPatchApproval", "attestation/generate",
            "currentTime/read", "execCommandApproval", "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval", "item/permissions/requestApproval", "item/tool/call",
            "item/tool/requestUserInput", "mcpServer/elicitation/request");
    private static final Set<String> LIFECYCLE_EVENTS = Set.of(
            "error", "thread/started", "thread/resumed", "thread/closed", "thread/status/changed", "turn/started",
            "turn/completed", "turn/interrupt_acknowledged", "process/exited", "auth/required",
            "interaction_required");

    private CodexAppServerProtocolSchema() {
        // Schema projection.
    }

    /**
     * Returns whether an outbound method exists in the installed local schema.
     *
     * @param method Codex App Server method
     * @return whether the method is a supported request
     */
    public static boolean isRequestMethod(String method) {
        return method != null && REQUEST_METHODS.contains(method);
    }

    /**
     * Returns whether an event is one of the lifecycle events with explicit
     * state-machine semantics.
     *
     * @param method event method
     * @return whether the event is lifecycle-authoritative
     */
    public static boolean isLifecycleEvent(String method) {
        return method != null && LIFECYCLE_EVENTS.contains(method);
    }

    /**
     * Returns whether a client notification exists in the installed schema.
     *
     * @param method notification method
     * @return whether the notification is supported
     */
    public static boolean isClientNotification(String method) {
        return method != null && CLIENT_NOTIFICATIONS.contains(method);
    }

    /**
     * Returns whether a server-to-client request is present in the installed
     * local schema. Such a request carries both a method and an ID and is
     * surfaced as interaction-required rather than misclassified as a
     * malformed lifecycle event.
     *
     * @param method server request method
     * @return whether the method is a generated-schema server request
     */
    public static boolean isServerRequest(String method) {
        return method != null && SERVER_REQUEST_METHODS.contains(method);
    }

    /**
     * Validates one outbound method and bounded parameter object.
     *
     * @param method outbound Codex method
     * @param params bounded parameter object
     * @throws IOException when the method or shape is outside the local schema
     */
    public static void validateRequest(String method, Map<String, ?> params) throws IOException {
        if (!isRequestMethod(method) || method.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_METHOD_BYTES) {
            throw new IOException("codex_protocol_method_unknown");
        }
        if (params == null || params.size() > 128) {
            throw new IOException("codex_protocol_params_exceeds_bound");
        }
    }

    /**
     * Validates one installed client notification and bounded parameters.
     *
     * @param method notification method
     * @param params bounded parameter object
     * @throws IOException when the notification is outside the local schema
     */
    public static void validateNotification(String method, Map<String, ?> params) throws IOException {
        if (!isClientNotification(method) || params == null || params.size() > 128) {
            throw new IOException("codex_protocol_notification_unknown");
        }
    }

    /**
     * Validates the bounded JSON-RPC-like frame shape before lifecycle dispatch.
     *
     * @param frame parsed frame object
     * @throws IOException when the frame is not a response or event
     */
    public static void validateFrame(Map<String, Object> frame) throws IOException {
        Object rawMethod = frame.get("method");
        Object rawId = frame.get("id");
        if (rawMethod != null) {
            if (rawId != null && (!(rawMethod instanceof String method) || !isServerRequest(method))) {
                throw new IOException("codex_protocol_malformed_frame");
            }
            if (rawId != null && invalidRequestId(rawId)) {
                throw new IOException("codex_protocol_malformed_frame");
            }
            if (!(rawMethod instanceof String method) || method.isBlank()
                    || method.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_METHOD_BYTES) {
                throw new IOException("codex_protocol_malformed_event");
            }
            Object params = frame.get("params");
            if (params != null && !(params instanceof Map<?, ?>)) {
                throw new IOException("codex_protocol_malformed_event");
            }
            return;
        }
        if (invalidRequestId(rawId)) {
            throw new IOException("codex_protocol_malformed_response");
        }
        if (!frame.containsKey("result") && !frame.containsKey("error")) {
            throw new IOException("codex_protocol_malformed_response");
        }
        if (frame.containsKey("result") && frame.containsKey("error")
                && frame.get("result") != null && frame.get("error") != null) {
            throw new IOException("codex_protocol_malformed_response");
        }
    }

    private static boolean invalidRequestId(Object value) {
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return !Double.isFinite(numeric) || numeric != Math.rint(numeric)
                    || numeric < Long.MIN_VALUE || numeric > Long.MAX_VALUE;
        }
        return true;
    }

    /**
     * Builds the installed-schema initialize payload.
     *
     * @return bounded initialize parameters
     */
    public static Map<String, Object> initializeParams() {
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "synesis");
        clientInfo.put("title", "Synesis Codex lifecycle");
        clientInfo.put("version", "0.1.0");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("clientInfo", clientInfo);
        value.put("capabilities", Map.of());
        return value;
    }

    /**
     * Builds a text user-input value accepted by the installed turn schema.
     *
     * @param text bounded user text
     * @return one-element text input array
     */
    public static List<Map<String, String>> textInput(String text) {
        return List.of(Map.of("type", "text", "text", text == null ? "" : text));
    }
}
