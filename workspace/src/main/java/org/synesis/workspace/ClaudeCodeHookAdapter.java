package org.synesis.workspace;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;

/**
 * Pre-action hook adapter translating Claude Code pre-tool-execution events
 * into Synesis action-time constraint checks.
 *
 * <p>Enforces supported structured file-edit operations without modifying
 * target files. Shell commands and non-file tools pass through with a documented
 * limitation warning.
 *
 * @since 1.0
 */
public final class ClaudeCodeHookAdapter {

    private final Path profile;

    /**
     * Constructs a Claude Code hook adapter bound to a specific workspace profile.
     *
     * @param profile path to workspace profile directory
     */
    public ClaudeCodeHookAdapter(Path profile) {
        this.profile = Objects.requireNonNull(profile, "profile").toAbsolutePath().normalize();
    }

    /**
     * Outcome classification of the adapter check.
     */
    public enum Outcome {
        /** Operation is permitted. */
        ALLOWED,
        /** Operation triggers a non-blocking warning. */
        WARNING,
        /** Operation is blocked by an active constraint. */
        BLOCKED,
        /** Operation is an unsupported tool/action. */
        UNSUPPORTED,
        /** Event JSON or path format is invalid. */
        INVALID_INPUT
    }

    /**
     * Result container for adapter processing.
     *
     * @param outcome      check outcome
     * @param responseJson Claude Code hook JSON response
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
                sb.append(line).append("\n");
            }
            return processJson(sb.toString().trim());
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
            return new Result(Outcome.UNSUPPORTED, allowJson(), "Unsupported non-file-edit tool: " + toolName);
        }

        List<String> paths = extractTargetPaths(json);
        if (paths.isEmpty()) {
            return new Result(Outcome.INVALID_INPUT, denyJson("No target file path found in tool input"), "No target file path");
        }

        // Load project config and decision store
        ProjectConfig config;
        DecisionStore store;
        List<DecisionRecord> heads;
        try {
            config = ProjectConfig.load(profile.resolve("project.conf"));
            store = new DecisionStore(profile.resolve("records"), config.projectId());
            heads = store.verifiedHeads(1_000);
        } catch (Exception e) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Project is not configured for profile"), "Project not configured");
        }

        List<ProjectConstraint> activeConstraints = new ArrayList<>();
        for (DecisionRecord r : heads) {
            ProjectConstraint c = ProjectConstraint.fromRecord(r);
            if (c != null && c.status() == ProjectConstraint.ConstraintStatus.ACTIVE) {
                activeConstraints.add(c);
            }
        }

        ProjectConstraint blockingConstraint = null;
        ProjectConstraint warningConstraint = null;
        String matchedPath = null;

        for (String path : paths) {
            for (ProjectConstraint c : activeConstraints) {
                try {
                    if (c.appliesTo(path)) {
                        if (c.effect() == ProjectConstraint.Effect.BLOCK) {
                            blockingConstraint = c;
                            matchedPath = path;
                            break;
                        } else if (c.effect() == ProjectConstraint.Effect.WARN && warningConstraint == null) {
                            warningConstraint = c;
                            matchedPath = path;
                        }
                    }
                } catch (IllegalArgumentException e) {
                    return new Result(Outcome.INVALID_INPUT, denyJson("Invalid path format: " + path), e.getMessage());
                }
            }
            if (blockingConstraint != null) break;
        }

        if (blockingConstraint != null) {
            String denialReason = "Synesis blocked this edit.\n\n"
                    + "Constraint: " + blockingConstraint.title() + "\n"
                    + "Effect: BLOCK\n"
                    + "Scope: " + String.join(", ", blockingConstraint.scopes()) + "\n"
                    + "Reason: " + blockingConstraint.rationale() + "\n\n"
                    + "Re-plan without modifying the protected scope.";
            return new Result(Outcome.BLOCKED, denyJson(denialReason), denialReason);
        }

        if (warningConstraint != null) {
            return new Result(Outcome.WARNING, allowJson(), "Warning: " + warningConstraint.title());
        }

        return new Result(Outcome.ALLOWED, allowJson(), "Allowed");
    }

    private static boolean isFileEditTool(String toolName) {
        String lower = toolName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("edit") || lower.contains("write") || lower.contains("replace") || lower.contains("create");
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
        if (idx < 0) return null;

        int start = idx + search.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') return null;

        start++; // skip opening quote
        int end = json.indexOf('"', start);
        if (end < 0) return null;

        return json.substring(start, end);
    }

    private static String denyJson(String reason) {
        return "{\n  \"decision\": \"deny\",\n  \"reason\": \"" + escapeJson(reason) + "\"\n}";
    }

    private static String allowJson() {
        return "{\n  \"decision\": \"allow\"\n}";
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
