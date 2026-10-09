package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.synesis.workspace.lifecycle.codex.protocol.CodexAppServerProtocolClient;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope;
import org.synesis.workspace.lifecycle.codex.state.CodexLifecycleStateStore;

/**
 * Interprets the payloads of the Codex App Server JSON-RPC protocol for the lifecycle service.
 *
 * <p>Covers building request params, extracting thread and turn identities and status text from
 * response results and notification params, and bounding failure text for diagnostics. Every
 * method is a pure function of its arguments. They are kept apart from
 * {@link CodexAppServerLifecycleService} so the protocol's tolerated payload shapes can be read and
 * checked without the lifecycle state machine.
 */
final class CodexAppServerPayloadReader {

    private CodexAppServerPayloadReader() {
    }

    static CodexLifecycleStateStore.State reconcileReadState(CodexLifecycleStateStore.Checkpoint prior, CodexAppServerProtocolClient.Response read) {
        String status = null;
        if (read.result() instanceof Map<?, ?> raw) {
            Object value = raw.get("status");
            status = statusText(value);
            if (status == null && raw.get("thread") instanceof Map<?, ?> thread) {
                status = statusText(thread.get("status"));
            }
        }
        if ("interrupted".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.INTERRUPTED;
        }
        if ("completed".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.COMPLETED;
        }
        if ("idle".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.IDLE;
        }
        if ("running".equalsIgnoreCase(status)) {
            return CodexLifecycleStateStore.State.RUNNING;
        }
        return prior.state() == CodexLifecycleStateStore.State.RUNNING || prior.state() == CodexLifecycleStateStore.State.INTERRUPTING || prior.state() == CodexLifecycleStateStore.State.AMBIGUOUS
                ? CodexLifecycleStateStore.State.AMBIGUOUS : prior.state();
    }

    static Map<String, Object> protocolParams(LifecycleControlRequestEnvelope request, Map<String, ?> base) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>(base);
        String approvalPolicy = request.options().get("codexApprovalPolicy");
        if (approvalPolicy == null || approvalPolicy.isBlank()) {
            return params;
        }
        if (!Set.of("never", "on-request", "untrusted").contains(approvalPolicy)) {
            throw new IOException("codex_approval_policy_invalid");
        }
        params.put("approvalPolicy", approvalPolicy);
        return params;
    }

    static String responseId(CodexAppServerProtocolClient.Response response, String key) throws IOException {
        if (!(response.result() instanceof Map<?, ?> raw)) {
            throw new IOException("missing " + key + " response");
        }
        Object value = raw.get(key);
        if (value == null && raw.get("thread") instanceof Map<?, ?> thread) {
            value = thread.get(key);
            if (value == null && key.endsWith("Id")) {
                value = thread.get("id");
            }
        }
        if (value == null && raw.get("turn") instanceof Map<?, ?> turn) {
            value = turn.get(key);
            if (value == null && key.endsWith("Id")) {
                value = turn.get("id");
            }
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IOException("missing " + key + " response");
        }
        return text;
    }

    static String optionalResponseId(CodexAppServerProtocolClient.Response response) {
        if (!(response.result() instanceof Map<?, ?> raw)) {
            return null;
        }
        Object value = raw.get("threadId");
        if (value == null && raw.get("thread") instanceof Map<?, ?> thread) {
            value = thread.get("threadId");
            if (value == null) {
                value = thread.get("id");
            }
        }
        if (value == null && raw.get("turn") instanceof Map<?, ?> turn) {
            value = turn.get("threadId");
            if (value == null) {
                value = turn.get("id");
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    static CodexAppServerProtocolClient.Response requireSuccessful(CodexAppServerProtocolClient.Response response, String method) throws IOException {
        if (response.failed()) {
            throw new IOException("codex_request_failed:" + method);
        }
        return response;
    }

    static String diagnostic(Throwable failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }

    private static String statusText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type != null) {
                return statusText(type);
            }
            Object status = map.get("status");
            if (status != null) {
                return statusText(status);
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    static String value(Map<String, Object> params, String first, String second) {
        Object value = params.get(first);
        if (value == null) {
            value = params.get(second);
        }
        if (value == null && params.get("thread") instanceof Map<?, ?> thread) {
            value = thread.get(first);
            if (value == null) {
                value = thread.get(second);
            }
            if (value == null && first.endsWith("Id")) {
                value = thread.get("id");
            }
        }
        if (value == null && params.get("turn") instanceof Map<?, ?> turn) {
            value = turn.get(first);
            if (value == null) {
                value = turn.get(second);
            }
            if (value == null && first.endsWith("Id")) {
                value = turn.get("id");
            }
        }
        return statusText(value);
    }
}
