package org.synesis.workspace.integration.codex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses only the bounded file-directive grammar used by Codex {@code apply_patch}. */
public final class CodexApplyPatchParser {
    private static final int MAX_PATCH_LENGTH = 64 * 1024;
    private static final int MAX_LINES = 4_096;
    private static final int MAX_FILES = 64;
    private static final int MAX_PATH_LENGTH = 512;

    /** Creates the bounded parser. */
    public CodexApplyPatchParser() {
    }

    /** Supported file-level patch operations. */
    public enum Operation {
        /** Adds a file. */
        ADD,
        /** Updates a file. */
        UPDATE,
        /** Deletes a file. */
        DELETE,
        /** Moves a file from source to destination. */
        MOVE
    }

    /**
     * One normalized file-level patch operation.
     * @param operation operation kind
     * @param sourcePath normalized source path
     * @param destinationPath normalized destination path for moves
     */
    public record FileChange(Operation operation, String sourcePath, String destinationPath) {
        /** Validates the operation shape. */
        public FileChange {
            if (operation == null || sourcePath == null || sourcePath.isBlank()) {
                throw new IllegalArgumentException("Patch change requires an operation and source path");
            }
            if (operation == Operation.MOVE && (destinationPath == null || destinationPath.isBlank())) {
                throw new IllegalArgumentException("Move change requires a destination path");
            }
            if (operation != Operation.MOVE && destinationPath != null) {
                throw new IllegalArgumentException("Only move changes have a destination path");
            }
        }
    }

    /**
     * Bounded parser result.
     * @param changes normalized file changes
     * @param errorMessage bounded parse error, or {@code null} on success
     */
    public record ParseResult(List<FileChange> changes, String errorMessage) {
        /** Copies changes and validates the result state. */
        public ParseResult {
            changes = List.copyOf(changes == null ? List.of() : changes);
        }

        /**
         * Returns whether the patch was parsed successfully.
         * @return success state
         */
        public boolean valid() {
            return errorMessage == null && !changes.isEmpty();
        }
    }

    /**
     * Parses a complete, bounded Codex patch without applying it.
     *
     * @param patch complete patch text
     * @return normalized file changes or a bounded error
     */
    public ParseResult parse(String patch) {
        if (patch == null || patch.isBlank()) {
            return invalid("Patch is empty");
        }
        if (patch.length() > MAX_PATCH_LENGTH) {
            return invalid("Patch exceeds the maximum accepted size");
        }

        String[] lines = patch.strip().split("\\R", -1);
        if (lines.length > MAX_LINES || lines.length < 3
                || !"*** Begin Patch".equals(lines[0])
                || !"*** End Patch".equals(lines[lines.length - 1])) {
            return invalid("Patch markers are missing or malformed");
        }

        Map<String, FileChange> unique = new LinkedHashMap<>();
        for (int index = 1; index < lines.length - 1; index++) {
            String line = lines[index];
            if (line.startsWith("*** Add File:")) {
                FileChange change = change(Operation.ADD, pathAfter(line, "*** Add File:"), null);
                if (change == null) return invalid("Malformed Add File directive");
                unique.putIfAbsent(key(change), change);
            } else if (line.startsWith("*** Update File:")) {
                FileChange change = change(Operation.UPDATE, pathAfter(line, "*** Update File:"), null);
                if (change == null) return invalid("Malformed Update File directive");
                unique.putIfAbsent(key(change), change);
            } else if (line.startsWith("*** Delete File:")) {
                FileChange change = change(Operation.DELETE, pathAfter(line, "*** Delete File:"), null);
                if (change == null) return invalid("Malformed Delete File directive");
                unique.putIfAbsent(key(change), change);
            } else if (line.startsWith("*** Move to:")) {
                String destination = safePath(pathAfter(line, "*** Move to:"));
                if (destination == null || unique.isEmpty()) {
                    return invalid("Move directive has no preceding source file");
                }
                FileChange previous = last(unique);
                if (previous.operation() != Operation.UPDATE) {
                    return invalid("Move directive must follow an Update File directive");
                }
                unique.remove(key(previous));
                FileChange move = change(Operation.MOVE, previous.sourcePath(), destination);
                if (move == null) return invalid("Malformed Move directive");
                unique.putIfAbsent(key(move), move);
            } else if (line.startsWith("*** ")) {
                return invalid("Unsupported patch directive");
            }
            if (unique.size() > MAX_FILES) {
                return invalid("Patch contains too many file changes");
            }
        }
        if (unique.isEmpty()) return invalid("Patch contains no file directives");
        return new ParseResult(new ArrayList<>(unique.values()), null);
    }

    private static ParseResult invalid(String message) {
        return new ParseResult(List.of(), message);
    }

    private static FileChange change(Operation operation, String source, String destination) {
        String safeSource = safePath(source);
        String safeDestination = destination == null ? null : safePath(destination);
        return safeSource == null || (destination != null && safeDestination == null)
                ? null : new FileChange(operation, safeSource, safeDestination);
    }

    private static String pathAfter(String line, String directive) {
        return line.substring(directive.length()).trim();
    }

    private static String safePath(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_PATH_LENGTH
                || raw.indexOf('\0') >= 0 || raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) {
            return null;
        }
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) return null;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        try {
            Path normalized = Path.of(path).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")) return null;
        } catch (RuntimeException failure) {
            return null;
        }
        return path;
    }

    private static String key(FileChange change) {
        return change.operation() + "\u0000" + change.sourcePath() + "\u0000" + change.destinationPath();
    }

    private static FileChange last(Map<String, FileChange> changes) {
        return changes.values().stream().reduce((_, second) -> second).orElseThrow();
    }
}
