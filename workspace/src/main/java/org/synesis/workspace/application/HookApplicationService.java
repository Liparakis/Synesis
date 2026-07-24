package org.synesis.workspace.application;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.synesis.workspace.integration.antigravity.AntigravityHookAdapter;
import org.synesis.workspace.integration.claude.ClaudeCodeHookAdapter;
import org.synesis.workspace.integration.codex.CodexHookAdapter;
import org.synesis.workspace.provider.ProviderJson;

/**
 * Adapts provider hook streams to structured provider results.
 */
public final class HookApplicationService {

    private static final int MAX_INPUT_BYTES = 128 * 1024;
    private final ProviderSessionBindingService bindings;

    /**
     * Creates the service.
     */
    public HookApplicationService() {
        bindings = new ProviderSessionBindingService();
    }

    /**
     * Processes one Claude Code hook event.
     *
     * @param profile local profile directory
     * @param input   provider event stream
     * @return structured hook result
     */
    public HookExecutionResult claudeCode(Path profile, InputStream input) {
        ClaudeCodeHookAdapter.Result result = new ClaudeCodeHookAdapter(profile).processStream(input);
        return new HookExecutionResult(result.outcome()
                .name(), result.responseJson(), result.humanReason());
    }

    /**
     * Processes one Antigravity hook event.
     *
     * @param profile local profile directory
     * @param input   provider event stream
     * @return structured hook result
     */
    public HookExecutionResult antigravity(Path profile, InputStream input) {
        AntigravityHookAdapter.Result result = new AntigravityHookAdapter(profile).processStream(input);
        return new HookExecutionResult(result.outcome()
                .name(), result.responseJson(), result.humanReason());
    }

    /**
     * Bootstraps a project-scoped Antigravity session before processing a hook.
     *
     * @param projectRoot initialized project root
     * @param profile local profile used by the adapter
     * @param input provider event stream
     * @return structured hook result
     */
    public HookExecutionResult antigravity(Path projectRoot, Path profile, InputStream input) {
        try {
            String json = read(input);
            var location = new ProjectApplicationService().require(projectRoot);
            ProviderSessionBindingService.BindingResult binding = bindings.ensure(location, "antigravity",
                    evidence(json));
            Path eventCwd = workspacePath(json, projectRoot);
            ProviderSessionBindingService.WorkspaceCheck workspace = bindings.verifyWorkspace(location,
                    binding.binding(), eventCwd);
            if (!workspace.verified()) return deniedAntigravity(workspace.code());
            HookExecutionResult result = antigravity(profile, new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            return withBinding(result, binding);
        } catch (Exception failure) {
            return deniedAntigravity("Synesis could not establish a trusted project session.");
        }
    }

    /**
     * Processes one Codex PreToolUse event using payload cwd project discovery.
     *
     * @param input provider event stream
     * @return structured hook result
     */
    public HookExecutionResult codex(InputStream input) {
        try {
            String json = read(input);
            Map<?, ?> event = object(ProviderJson.parse(json));
            String cwd = text(event, "cwd");
            var location = new ProjectApplicationService().locate(Path.of(cwd));
            ProviderSessionBindingService.BindingResult binding = bindings.ensure(location, "codex",
                    evidence(json));
            ProviderSessionBindingService.WorkspaceCheck workspace = bindings.verifyWorkspace(location,
                    binding.binding(), Path.of(cwd));
            if (!workspace.verified()) return denied(workspace.code());
            CodexHookAdapter.Result result = new CodexHookAdapter().processJson(json);
            return withBinding(new HookExecutionResult(result.outcome().name(), result.responseJson(), result.humanReason()), binding);
        } catch (Exception failure) {
            return denied("Synesis could not establish a trusted project session.");
        }
    }

    private static HookExecutionResult withBinding(HookExecutionResult result,
            ProviderSessionBindingService.BindingResult binding) {
        String hint = "SESSION_ID=" + binding.binding().sessionId() + " SUPERVISOR_ID="
                + binding.binding().supervisorId() + " WORKER_ID=" + binding.binding().workerId();
        return new HookExecutionResult(result.outcome(), result.responseJson(),
                result.humanReason() == null ? hint : result.humanReason() + " " + hint);
    }

    private static HookExecutionResult denied(String reason) {
        return new HookExecutionResult("INVALID_INPUT",
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\""
                + reason + "\"}}", reason);
    }

    private static HookExecutionResult deniedAntigravity(String reason) {
        return new HookExecutionResult("INVALID_INPUT", "{\"decision\":\"deny\",\"reason\":\""
                + reason + "\"}", reason);
    }

    private static String read(InputStream input) throws java.io.IOException {
        if (input == null) throw new java.io.IOException("missing input");
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) throw new java.io.IOException("input exceeds bound");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Map<?, ?> value, String key) {
        Object result = value == null ? null : value.get(key);
        if (!(result instanceof String text) || text.isBlank()) throw new IllegalArgumentException("missing " + key);
        return text;
    }

    private static String evidence(String json) {
        Map<String, Object> value = object(ProviderJson.parse(json));
        for (String key : new String[] {"session_id", "sessionId", "conversation_id", "conversationId"}) {
            Object candidate = value == null ? null : value.get(key);
            if (candidate instanceof String text && !text.isBlank()) return text;
        }
        return null;
    }

    private static Path workspacePath(String json, Path fallback) {
        Map<String, Object> value = object(ProviderJson.parse(json));
        Object paths = value == null ? null : value.get("workspacePaths");
        if (paths instanceof java.util.List<?> list && !list.isEmpty() && list.getFirst() instanceof String path
                && !path.isBlank()) return Path.of(path);
        return fallback;
    }

    /**
     * Structured provider-hook execution result.
     *
     * @param outcome      adapter outcome
     * @param responseJson provider response JSON
     * @param humanReason  optional diagnostic
     */
    public record HookExecutionResult(String outcome, String responseJson, String humanReason) {

        /**
         * Validates the result.
         */
        public HookExecutionResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(responseJson, "response JSON");
        }
    }
}
