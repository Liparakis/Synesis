package org.synesis.workspace.integration.codex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.guardrail.ActionGuardrail;
import org.synesis.workspace.guardrail.ProjectPathResolver;
import org.synesis.workspace.provider.ProviderJson;

/** Translates Codex {@code PreToolUse} apply-patch events into guardrail decisions. */
public final class CodexHookAdapter {
    private static final int MAX_INPUT_BYTES = 128 * 1024;
    private static final int MAX_REASON_LENGTH = 1_500;

    /** Creates the project-discovering Codex adapter. */
    public CodexHookAdapter() {
    }

    /** Outcome classification of one Codex hook event. */
    public enum Outcome {
        /** The patch is allowed and no decision JSON is emitted. */
        ALLOWED,
        /** The patch is allowed with additional warning context. */
        WARNING,
        /** The patch is denied because a path is protected. */
        BLOCKED,
        /** The tool is outside this adapter's supported contract. */
        UNSUPPORTED,
        /** The event or patch is invalid and is denied fail-closed. */
        INVALID_INPUT
    }

    /**
     * Result of processing one Codex hook event.
     * @param outcome adapter outcome
     * @param responseJson provider response JSON, possibly empty
     * @param humanReason bounded diagnostic reason, or {@code null}
     */
    public record Result(Outcome outcome, String responseJson, String humanReason) {
        /** Validates the result shape. */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(responseJson, "response JSON");
        }
    }

    /**
     * Reads and processes one bounded Codex hook event.
     *
     * @param inputStream event input
     * @return fail-closed or silent hook result
     */
    public Result processStream(InputStream inputStream) {
        if (inputStream == null) return invalid("Missing hook input");
        try {
            byte[] bytes = inputStream.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) return invalid("Hook input exceeds the maximum accepted size");
            return processJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            return invalid("Could not read hook input");
        }
    }

    /**
     * Processes one Codex hook event without modifying any file.
     *
     * @param json event JSON
     * @return hook result
     */
    public Result processJson(String json) {
        if (json == null || json.isBlank()) return invalid("Empty hook input");
        try {
            Map<String, Object> event = object(ProviderJson.parse(json));
            String toolName = string(event.get("tool_name"));
            if (toolName == null || toolName.isBlank()) return invalid("Missing tool_name");
            if (!"apply_patch".equals(toolName)) {
                return new Result(Outcome.UNSUPPORTED, "", "Unsupported Codex tool: " + bounded(toolName));
            }

            String cwd = string(event.get("cwd"));
            Map<String, Object> toolInput = object(event.get("tool_input"));
            String command = toolInput == null ? null : string(toolInput.get("command"));
            if (cwd == null || cwd.isBlank() || command == null || command.isBlank()) {
                return invalid("Codex apply_patch event is missing cwd or tool_input.command");
            }

            ProjectApplicationService.ProjectLocation location;
            try {
                location = new ProjectApplicationService().locate(Path.of(cwd));
            } catch (Exception failure) {
                return invalid("Codex cwd is not an initialized Synesis project");
            }
            CodexApplyPatchParser.ParseResult parsed = new CodexApplyPatchParser().parse(command);
            if (!parsed.valid()) return invalid(parsed.errorMessage());

            Set<String> rawPaths = new LinkedHashSet<>();
            for (CodexApplyPatchParser.FileChange change : parsed.changes()) {
                rawPaths.add(change.sourcePath());
                if (change.destinationPath() != null) rawPaths.add(change.destinationPath());
            }
            List<String> relativePaths = new ArrayList<>();
            for (String rawPath : rawPaths) {
                try {
                    relativePaths.add(ProjectPathResolver.resolve(location.root(), rawPath));
                } catch (IllegalArgumentException failure) {
                    return invalid("Patch path is outside the project root");
                }
            }

            ActionGuardrail.Response warning = null;
            for (String relativePath : relativePaths) {
                ActionGuardrail.Response response = ActionGuardrail.evaluate(location.profile(),
                        new ActionGuardrail.Request(location.root(), relativePath, "apply_patch", null));
                if (response.outcome() == ActionGuardrail.Outcome.BLOCKED) {
                    return new Result(Outcome.BLOCKED, deny(response.message()), bounded(response.message()));
                }
                if (response.outcome() == ActionGuardrail.Outcome.INVALID_INPUT) return invalid(response.message());
                if (response.outcome() == ActionGuardrail.Outcome.WARNING && warning == null) warning = response;
            }
            if (warning != null) {
                String message = bounded(warning.message());
                return new Result(Outcome.WARNING, warning(message), message);
            }
            return new Result(Outcome.ALLOWED, "", null);
        } catch (RuntimeException failure) {
            return invalid("Invalid Codex hook input");
        }
    }

    private static Result invalid(String reason) {
        String message = bounded(reason == null ? "Invalid Codex hook input" : reason);
        return new Result(Outcome.INVALID_INPUT, deny(message), message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String string(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String bounded(String value) {
        if (value == null) return "Invalid Codex hook input";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= MAX_REASON_LENGTH ? clean : clean.substring(0, MAX_REASON_LENGTH - 3) + "...";
    }

    private static String deny(String reason) {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\""
                + escape(reason) + "\"}}";
    }

    private static String warning(String reason) {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"additionalContext\":\""
                + escape(reason) + "\"}}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\f", "\\f")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
