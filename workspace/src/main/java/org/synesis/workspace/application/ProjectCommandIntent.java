package org.synesis.workspace.application;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structured intent parameters for executing an approved project command.
 *
 * @param type      command intent classification (build, test, lint, format_check, git_status, git_diff, git_log)
 * @param target    optional validated target name or filter
 * @param arguments optional validated additional command arguments
 * @since 1.0
 */
public record ProjectCommandIntent(
        String type,
        String target,
        List<String> arguments
) {
    private static final Pattern SAFE_TARGET_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{1,256}$");

    /**
     * Validates intent type, target format, and argument bounds.
     */
    public ProjectCommandIntent {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("command type cannot be blank");
        }
        if (target != null && !target.isBlank()) {
            if (!SAFE_TARGET_PATTERN.matcher(target.trim()).matches()) {
                throw new IllegalArgumentException("target contains invalid characters or exceeds 256 characters");
            }
        }
        if (arguments != null) {
            if (arguments.size() > 10) {
                throw new IllegalArgumentException("arguments count exceeds maximum allowed limit of 10");
            }
            for (String arg : arguments) {
                if (arg != null) {
                    if (arg.length() > 128) {
                        throw new IllegalArgumentException("argument length exceeds maximum limit of 128 characters");
                    }
                    if (containsShellMetacharacters(arg)) {
                        throw new IllegalArgumentException("argument contains prohibited shell metacharacters");
                    }
                }
            }
        }
    }

    private static boolean containsShellMetacharacters(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' || c == '|' || c == ';' || c == '$' || c == '>' || c == '<'
                    || c == '`' || c == '\\' || c == '"' || c == '\'' || c == '\n'
                    || c == '\r' || c == '\0') {
                return true;
            }
        }
        return false;
    }
}
