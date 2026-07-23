package org.synesis.workspace.integration.claude;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.synesis.workspace.guardrail.ActionGuardrail;
import org.synesis.workspace.guardrail.ProjectPathResolver;
import org.synesis.coordination.OwnershipRegistry;

/**
 * Pre-action hook adapter translating official Claude Code PreToolUse hook events
 * into Synesis action-time constraint checks via {@link ActionGuardrail}.
 *
 * <p>Enforces supported structured file-edit operations without modifying
 * target files. Preserves Claude Code's normal permission flow and exits with code 0.
 *
 * @since 1.0
 */
public final class ClaudeCodeHookAdapter {

    private final Path profile;
    private final OwnershipRegistry ownership;
    private final String requesterNodeId;

    /**
     * Constructs a Claude Code hook adapter bound to a specific workspace profile.
     *
     * @param profile path to workspace profile directory
     */
    public ClaudeCodeHookAdapter(Path profile) {
        this(profile, null, null);
    }

    /**
     * Constructs an adapter with optional semantic ownership enforcement.
     * @param profile workspace profile directory
     * @param ownership ownership view, or null to use policy-only behavior
     * @param requesterNodeId authenticated requester node, required with ownership
     */
    public ClaudeCodeHookAdapter(Path profile, OwnershipRegistry ownership, String requesterNodeId) {
        this.profile = Objects.requireNonNull(profile, "profile")
                .toAbsolutePath()
                .normalize();
        this.ownership = ownership;
        this.requesterNodeId = requesterNodeId;
    }

    /**
     * Outcome classification of the adapter check.
     */
    public enum Outcome {
        /**
         * Operation is permitted.
         */
        ALLOWED,
        /**
         * Operation triggers a non-blocking warning.
         */
        WARNING,
        /**
         * Operation is blocked by an active constraint.
         */
        BLOCKED,
        /** Operation must stop and request the semantic owner. */
        REQUEST_OWNER,
        /**
         * Operation is an unsupported tool/action.
         */
        UNSUPPORTED,
        /**
         * Event JSON or path format is invalid.
         */
        INVALID_INPUT
    }

    /**
     * Result container for adapter processing.
     *
     * @param outcome      check outcome
     * @param responseJson official Claude Code hook JSON response
     * @param humanReason  human readable explanation or hint
     */
    public record Result(Outcome outcome, String responseJson, String humanReason) {

    }

    /**
     * Reads JSON event payload from an input stream and executes the constraint check.
     *
     * @param inputStream stream providing hook event JSON
     * @return adapter execution result
     */
    public Result processStream(InputStream inputStream) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line)
                        .append("\n");
            }
            return processJson(sb.toString()
                    .trim());
        } catch (Exception e) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Invalid input: " + e.getMessage()), e.getMessage());
        }
    }

    /**
     * Processes pre-tool event JSON string.
     *
     * @param json payload string
     * @return adapter result
     */
    public Result processJson(String json) {
        if (json == null || json.isEmpty()) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Empty input event"), "Empty input event");
        }

        String toolName = extractJsonField(json, "tool_name");
        if (toolName == null || toolName.isEmpty()) {
            toolName = extractJsonField(json, "name");
        }

        if (toolName == null || !isFileEditTool(toolName)) {
            String diagnostic =
                    "SYNESIS_HOOK_RESULT=UNSUPPORTED\nTOOL_NAME=" + (toolName == null ? "UNKNOWN" : toolName)
                            + "\nREASON=The current adapter does not safely determine affected paths for this tool.";
            return new Result(Outcome.UNSUPPORTED, emptyJson(), diagnostic);
        }

        String cwdStr = extractJsonField(json, "cwd");
        Path projectRoot = profile;
        if (cwdStr != null && !cwdStr.isBlank()) {
            try {
                projectRoot = Path.of(cwdStr)
                        .toAbsolutePath()
                        .normalize();
            } catch (Exception ignored) {
            }
        }

        List<String> rawPaths = extractTargetPaths(json);
        if (rawPaths.isEmpty()) {
            return new Result(Outcome.INVALID_INPUT,
                    denyJson("No target file path found in tool input"),
                    "No target file path");
        }

        List<String> normalizedRelativePaths = new ArrayList<>();
        for (String raw : rawPaths) {
            try {
                String rel = resolveRelativePath(projectRoot, raw);
                if (rel != null) {
                    normalizedRelativePaths.add(rel);
                }
            } catch (IllegalArgumentException e) {
                return new Result(Outcome.INVALID_INPUT,
                        denyJson("Path outside project root or invalid: " + raw),
                        e.getMessage());
            }
        }

        if (normalizedRelativePaths.isEmpty()) {
            return new Result(Outcome.INVALID_INPUT,
                    denyJson("No valid repository-relative target path could be resolved"),
                    "No relative target path");
        }

        ActionGuardrail.Response finalResponse = new ActionGuardrail.Response(ActionGuardrail.Outcome.ALLOWED,
                null,
                null,
                "Allowed");
        for (String relPath : normalizedRelativePaths) {
            ActionGuardrail.Request req = new ActionGuardrail.Request(projectRoot, relPath, toolName, null);
            ActionGuardrail.Response resp = ownership == null ? ActionGuardrail.evaluate(profile, req)
                    : ActionGuardrail.evaluate(profile, req, ownership, requesterNodeId);
            if (resp.outcome() == ActionGuardrail.Outcome.BLOCKED) {
                finalResponse = resp;
            } else if (resp.outcome() == ActionGuardrail.Outcome.REQUEST_OWNER) {
                finalResponse = resp;
                break;
            } else if (resp.outcome() == ActionGuardrail.Outcome.WARNING
                    && finalResponse.outcome() != ActionGuardrail.Outcome.BLOCKED) {
                finalResponse = resp;
            } else if (resp.outcome() == ActionGuardrail.Outcome.INVALID_INPUT
                    && finalResponse.outcome() == ActionGuardrail.Outcome.ALLOWED) {
                finalResponse = resp;
            }
        }

        return switch (finalResponse.outcome()) {
            case BLOCKED -> new Result(Outcome.BLOCKED, denyJson(finalResponse.message()), finalResponse.message());
            case REQUEST_OWNER -> new Result(Outcome.REQUEST_OWNER, denyJson(finalResponse.message()), finalResponse.message());
            case WARNING -> {
                String warningDiag = "SYNESIS_HOOK_RESULT=WARNING\nCONSTRAINT_TITLE="
                        + (finalResponse.warningConstraint() != null ? finalResponse.warningConstraint()
                                                                       .title() : "Warning")
                        + "\nREASON=" + finalResponse.message();
                yield new Result(Outcome.WARNING, warnJson(
                        finalResponse.warningConstraint() != null ? finalResponse.warningConstraint()
                                                                    .title() : "Warning",
                        finalResponse.warningConstraint() != null ? finalResponse.warningConstraint()
                                                                    .rationale() : finalResponse.message()
                ), warningDiag);
            }
            case INVALID_INPUT ->
                    new Result(Outcome.INVALID_INPUT, denyJson(finalResponse.message()), finalResponse.message());
            case UNSUPPORTED -> new Result(Outcome.UNSUPPORTED, emptyJson(), finalResponse.message());
            case ALLOWED -> new Result(Outcome.ALLOWED, emptyJson(), "Allowed");
        };
    }

    /**
     * Resolves a raw target path (absolute or relative) against the project root.
     *
     * @param projectRoot project root directory
     * @param rawPath     raw file path string from tool input
     * @return normalized repository-relative path string
     * @throws IllegalArgumentException if path lies outside project root
     */
    public static String resolveRelativePath(Path projectRoot, String rawPath) {
        return ProjectPathResolver.resolve(projectRoot, rawPath);
    }

    private static boolean isFileEditTool(String toolName) {
        String lower = toolName.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("edit") || lower.equals("write") || lower.equals("str_replace_editor")
                || lower.equals("write_file") || lower.equals("file_edit") || lower.equals("file_write")
                || lower.equals("notebookedit");
    }

    private static List<String> extractTargetPaths(String json) {
        List<String> paths = new ArrayList<>();
        String[] candidateFields = {"file_path", "path", "target_file", "filePath", "targetFile"};
        for (String field : candidateFields) {
            String val = extractJsonField(json, field);
            if (val != null && !val.isEmpty()) {
                paths.add(val);
            }
        }
        return paths;
    }

    private static String extractJsonField(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            search = "\"" + key + "\" :";
            idx = json.indexOf(search);
        }
        if (idx < 0) {
            return null;
        }

        int start = idx + search.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }

        start++;
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }

        return json.substring(start, end);
    }

    private static String denyJson(String reason) {
        return """
                {
                  "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": "%s"
                  }
                }""".formatted(escapeJson(reason));
    }

    private static String warnJson(String title, String rationale) {
        return """
                {
                  "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "additionalContext": "Synesis Warning: Active constraint '%s' applies to this scope. Rationale: %s"
                  }
                }""".formatted(escapeJson(title), escapeJson(rationale));
    }

    private static String emptyJson() {
        return "{}";
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
