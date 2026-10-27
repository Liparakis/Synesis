package org.synesis.workspace.application.hook;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.workspace.application.project.execution.ApplicationStatePaths;

/**
 * Keeps Claude Code's own file tools ({@code Read}, {@code Glob}, {@code Grep}) away from Synesis's state and keys
 * (ADR-0104, "Keys and the provider's own tools"). The sandbox confines {@code run_command}; these tools are the
 * provider's and run outside it, so the PreToolUse hook is the only place Synesis can refuse them.
 *
 * <p>Refused: any path with a {@code .synesis} component (the project's coordination log, the node key and the
 * operator key live under {@code .synesis/local/profile}), and anything under the application-state folders
 * ({@link ApplicationStatePaths}: other lanes' worktrees, the default Link identity, the daemon endpoint). The one
 * exception is the agent's own lane worktree, which sits inside the application state: the nearest ancestor of the
 * event's working directory that holds {@code .synesis/local/workspace-binding.json}, else the working directory. A
 * search whose root is above a protected folder and not inside the lane is refused too, because it would read the
 * folder. Deny rules in the provider's settings cannot carve that exception out, which is why this is a hook.</p>
 *
 * <p>Best effort by nature: a search rooted in the lane still reaches the lane's own tracked {@code .synesis}
 * files, which hold no key. A Codex agent has no such tool to guard: its reads go through a shell inside Codex's own
 * sandbox, which Synesis does not control (ADR-0104, residual risk).</p>
 */
final class SynesisStateReadGuard {

    private static final String PROTECTED_COMPONENT = ".synesis";
    private static final String REASON = "Synesis keeps its state and keys there. Read the project through the files of your own worktree and Synesis's tools, not through its state.";

    private SynesisStateReadGuard() {
    }

    /**
     * Decides one tool call.
     *
     * @param toolName  the provider's tool name
     * @param toolInput the call's input
     * @param cwd       the event's working directory, or {@code null}
     * @return the reason to refuse, or empty to leave the call to the provider's normal permission flow
     */
    static Optional<String> denial(String toolName, Map<String, Object> toolInput, String cwd) {
        if (toolName == null || toolInput == null || !List.of("Read", "Glob", "Grep").contains(toolName)) {
            return Optional.empty();
        }
        Path working = absolute(cwd == null ? "." : cwd, null);
        Path lane = lane(working);
        List<Path> states = ApplicationStatePaths.roots();
        List<Path> targets = new ArrayList<>();
        for (String field : List.of("file_path", "notebook_path", "path")) {
            if (toolInput.get(field) instanceof String text && !text.isBlank()) {
                targets.add(absolute(text, working));
            }
        }
        boolean search = !toolName.equals("Read");
        // A search with no path starts in the working directory.
        if (search && targets.isEmpty()) {
            targets.add(working);
        }
        Path base = targets.isEmpty() ? working : targets.getFirst();
        for (String field : List.of("pattern", "glob")) {
            if (field.equals("pattern") && toolName.equals("Grep")) {
                continue;
            }
            if (toolInput.get(field) instanceof String pattern && !pattern.isBlank()) {
                if (hasProtectedSegment(pattern)) {
                    return Optional.of(REASON);
                }
                String prefix = literalPrefix(pattern);
                if (!prefix.isEmpty()) {
                    targets.add(absolute(prefix, base));
                }
            }
        }
        for (Path target : targets) {
            if (refused(target, search, lane, states)) {
                return Optional.of(REASON);
            }
        }
        return Optional.empty();
    }

    private static boolean refused(Path target, boolean search, Path lane, List<Path> states) {
        // The exception holds for a lane worktree inside the state, not for a working directory above it (a home folder).
        boolean inLane = target.startsWith(lane) && states.stream().noneMatch(state -> state.startsWith(lane));
        // The lane's own path may run through a folder called .synesis (the state folder is ~/.synesis on Linux), so only
        // what is below the lane is judged.
        for (Path part : inLane ? lane.relativize(target) : target) {
            if (part.toString().equalsIgnoreCase(PROTECTED_COMPONENT)) {
                return true;
            }
        }
        if (inLane) {
            return false;
        }
        for (Path state : states) {
            if (target.startsWith(state) || search && state.startsWith(target)) {
                return true;
            }
        }
        return false;
    }

    /** The folder an agent may read inside the application state: its own lane worktree. */
    private static Path lane(Path working) {
        for (Path current = working; current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".synesis/local/workspace-binding.json"))) {
                return current;
            }
        }
        return working;
    }

    private static boolean hasProtectedSegment(String pattern) {
        for (String segment : pattern.split("[/\\\\]")) {
            if (segment.equalsIgnoreCase(PROTECTED_COMPONENT)) {
                return true;
            }
        }
        return false;
    }

    /** The directory part of a glob before its first wildcard, which names where the search starts. */
    private static String literalPrefix(String pattern) {
        int wildcard = -1;
        for (int i = 0; i < pattern.length() && wildcard < 0; i++) {
            if ("*?[{".indexOf(pattern.charAt(i)) >= 0) {
                wildcard = i;
            }
        }
        String literal = wildcard < 0 ? pattern : pattern.substring(0, wildcard);
        int separator = Math.max(literal.lastIndexOf('/'), literal.lastIndexOf('\\'));
        return wildcard < 0 ? literal : separator < 0 ? "" : literal.substring(0, separator + 1);
    }

    /** Resolves a path as the provider would, through symbolic links where it exists; an unreadable path stays as written. */
    private static Path absolute(String text, Path base) {
        try {
            Path path = Path.of(text);
            Path resolved = path.isAbsolute() || base == null ? path.toAbsolutePath() : base.resolve(path);
            return ApplicationStatePaths.real(resolved.normalize());
        } catch (InvalidPathException malformed) {
            return Path.of("/").resolve(PROTECTED_COMPONENT);
        }
    }
}
