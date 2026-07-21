package org.synesis.workspace.integration.antigravity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.synesis.projectrecord.ScopeMatcher;
import org.synesis.workspace.guardrail.ActionGuardrail;

/**
 * Pre-action hook adapter translating Google Antigravity PreToolUse hook events
 * into Synesis action-time constraint checks via {@link ActionGuardrail}.
 *
 * <p>Enforces supported structured file-mutation tools without modifying
 * target files. Exits with code 0 and returns valid decision JSON for Antigravity.
 *
 * @since 1.0
 */
public final class AntigravityHookAdapter {

    private final Path profile;

    /**
     * Outcome classification of the adapter check.
     */
    public enum Outcome {
        /** Operation is permitted. */
        ALLOWED,
        /** Operation triggers a non-blocking warning (force_ask). */
        WARNING,
        /** Operation is blocked by an active constraint (deny). */
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
     * @param responseJson Antigravity hook decision JSON response
     * @param humanReason  human readable explanation or hint
     */
    public record Result(Outcome outcome, String responseJson, String humanReason) {
    }

    /**
     * Constructs an Antigravity hook adapter bound to a specific workspace profile.
     *
     * @param profile path to workspace profile directory
     */
    public AntigravityHookAdapter(Path profile) {
        this.profile = Objects.requireNonNull(profile, "profile").toAbsolutePath().normalize();
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
            return new Result(Outcome.INVALID_INPUT, denyJson("Synesis could not safely validate the target path."), e.getMessage());
        }
    }

    /**
     * Processes Antigravity pre-tool event JSON string.
     *
     * @param json payload string
     * @return adapter result
     */
    public Result processJson(String json) {
        if (json == null || json.isEmpty()) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Synesis could not safely validate the target path."), "Empty input event");
        }

        String toolName = extractJsonField(json, "name");
        if (toolName == null || toolName.isEmpty()) {
            toolName = extractJsonField(json, "toolName");
        }

        if (toolName == null || !isSupportedTool(toolName)) {
            String diagnostic = "SYNESIS_HOOK_RESULT=UNSUPPORTED\nTOOL_NAME=" + (toolName == null ? "UNKNOWN" : toolName)
                    + "\nREASON=The current adapter does not safely determine affected paths for this tool.";
            return new Result(Outcome.UNSUPPORTED, askJson("Synesis found no blocking project constraint."), diagnostic);
        }

        String rawTargetFile = extractJsonField(json, "TargetFile");
        if (rawTargetFile == null || rawTargetFile.isBlank()) {
            rawTargetFile = extractJsonField(json, "targetFile");
        }
        if (rawTargetFile == null || rawTargetFile.isBlank()) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Synesis could not safely validate the target path."), "Missing TargetFile arg");
        }

        List<String> workspacePaths = extractWorkspacePaths(json);
        Path projectRoot = selectProjectRoot(rawTargetFile, workspacePaths, profile);
        if (projectRoot == null) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Synesis could not safely validate the target path."), "Target outside workspace roots");
        }

        String relativePath;
        try {
            relativePath = resolveRelativePath(projectRoot, rawTargetFile);
        } catch (IllegalArgumentException e) {
            return new Result(Outcome.INVALID_INPUT, denyJson("Synesis could not safely validate the target path."), e.getMessage());
        }

        String description = extractJsonField(json, "Description");
        if (description == null) description = extractJsonField(json, "Instruction");

        ActionGuardrail.Request req = new ActionGuardrail.Request(projectRoot, relativePath, toolName, description);
        ActionGuardrail.Response resp = ActionGuardrail.evaluate(profile, req);

        return switch (resp.outcome()) {
            case BLOCKED -> new Result(Outcome.BLOCKED, denyJson(resp.message()), resp.message());
            case WARNING -> {
                String warningReason = "Synesis warning: An active project constraint applies to this file: "
                        + (resp.warningConstraint() != null ? resp.warningConstraint().title() : "Warning")
                        + ". Rationale: " + (resp.warningConstraint() != null ? resp.warningConstraint().rationale() : resp.message());
                yield new Result(Outcome.WARNING, forceAskJson(warningReason), warningReason);
            }
            case INVALID_INPUT -> new Result(Outcome.INVALID_INPUT, denyJson("Synesis could not safely validate the target path."), resp.message());
            case UNSUPPORTED -> new Result(Outcome.UNSUPPORTED, askJson("Synesis found no blocking project constraint."), resp.message());
            case ALLOWED -> new Result(Outcome.ALLOWED, askJson("Synesis found no blocking project constraint."), "Allowed");
        };
    }

    /**
     * Selects the most specific workspace root containing the target file, falling back to profile root.
     *
     * @param rawTargetFile  target file path
     * @param workspacePaths list of workspace root directory strings
     * @param fallbackProfile profile fallback directory
     * @return selected project root path, or null if target lies outside all workspace roots
     */
    public static Path selectProjectRoot(String rawTargetFile, List<String> workspacePaths, Path fallbackProfile) {
        if (rawTargetFile == null || rawTargetFile.isBlank()) return fallbackProfile;
        Path targetPath = Path.of(rawTargetFile);

        if (workspacePaths != null && !workspacePaths.isEmpty()) {
            Path bestRoot = null;
            for (String ws : workspacePaths) {
                try {
                    Path root = Path.of(ws).toAbsolutePath().normalize();
                    Path absoluteTarget = targetPath.isAbsolute() ? targetPath.normalize() : root.resolve(targetPath).normalize();
                    if (absoluteTarget.startsWith(root)) {
                        if (bestRoot == null || root.getNameCount() > bestRoot.getNameCount()) {
                            bestRoot = root;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (bestRoot != null) return bestRoot;
            if (targetPath.isAbsolute()) return null; // Target file is outside all workspace paths
        }

        return fallbackProfile != null ? fallbackProfile.toAbsolutePath().normalize() : null;
    }

    /**
     * Resolves a raw target file path against the project root and normalizes to repository-relative `/` format.
     *
     * @param projectRoot project root path
     * @param rawPath     raw file path
     * @return normalized repository-relative path string
     * @throws IllegalArgumentException if path lies outside project root
     */
    public static String resolveRelativePath(Path projectRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        Path target = Path.of(rawPath);
        if (!target.isAbsolute()) {
            target = root.resolve(target);
        }
        target = target.normalize();

        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Target path outside project root: " + rawPath);
        }
        Path relative = root.relativize(target);
        return ScopeMatcher.normalizePath(relative.toString());
    }

    private static boolean isSupportedTool(String toolName) {
        String lower = toolName.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("write_to_file") || lower.equals("replace_file_content")
                || lower.equals("multi_replace_file_content");
    }

    private static List<String> extractWorkspacePaths(String json) {
        List<String> roots = new ArrayList<>();
        int idx = json.indexOf("\"workspacePaths\"");
        if (idx < 0) return roots;
        int arrStart = json.indexOf('[', idx);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return roots;

        String content = json.substring(arrStart + 1, arrEnd);
        String[] parts = content.split(",");
        for (String p : parts) {
            String clean = p.trim();
            if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
                clean = clean.substring(1, clean.length() - 1).replace("\\\\", "\\");
                roots.add(clean);
            }
        }
        return roots;
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

        start++;
        int end = json.indexOf('"', start);
        if (end < 0) return null;

        return json.substring(start, end);
    }

    private static String denyJson(String reason) {
        return """
                {
                  "decision": "deny",
                  "reason": "%s"
                }""".formatted(escapeJson(reason));
    }

    private static String forceAskJson(String reason) {
        return """
                {
                  "decision": "force_ask",
                  "reason": "%s"
                }""".formatted(escapeJson(reason));
    }

    private static String askJson(String reason) {
        return """
                {
                  "decision": "ask",
                  "reason": "%s"
                }""".formatted(escapeJson(reason));
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
