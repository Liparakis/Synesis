package org.synesis.workspace.application.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Path rules that decide which workspace files an agent may read or write.
 *
 * <p>The read side and the write side keep distinct predicates on purpose: they differ on blank
 * input and on malformed path syntax, and each caller keeps the answer it always gave.</p>
 *
 * @since 1.0
 */
public final class WorkspacePathPolicy {

    private WorkspacePathPolicy() {
    }

    /**
     * Identifies a path inside {@code .synesis}, {@code .codex}, or {@code .git}, case-insensitively
     * and with either separator. Blank input is not in a protected namespace.
     *
     * @param path project-relative path, or {@code null}
     * @return whether the path is one of those directories or lies under one
     */
    public static boolean isProtectedNamespace(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith(".synesis/") || normalized.startsWith(".codex/")
                || normalized.startsWith(".git/") || normalized.equals(".synesis")
                || normalized.equals(".codex") || normalized.equals(".git");
    }

    /**
     * Write-side rule for a resolved project-relative target: blank input is protected.
     *
     * @param resolvedRelative normalized project-relative target
     * @return whether mutations must not replace the target
     */
    public static boolean isProtectedTarget(String resolvedRelative) {
        return resolvedRelative == null || resolvedRelative.isBlank()
                || isProtectedNamespace(resolvedRelative);
    }

    /**
     * Read-side rule: blank, absolute, traversing, malformed, or protected paths are refused.
     *
     * @param path raw or resolved project-relative path
     * @return whether the path must not be read
     */
    public static boolean isInvalidOrProtectedReadPath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        try {
            if (isAbsolute(path) || containsTraversal(path)) {
                return true;
            }
        } catch (RuntimeException invalidPath) {
            return true;
        }
        return isProtectedNamespace(path);
    }

    /**
     * Tests whether a raw path is absolute. Write-side callers let malformed syntax propagate as
     * {@link java.nio.file.InvalidPathException}.
     *
     * @param rawPath raw project-relative path
     * @return whether the path is absolute on this platform
     */
    public static boolean isAbsolute(String rawPath) {
        return Path.of(rawPath).isAbsolute();
    }

    /**
     * Tests a raw path for a {@code ..} sequence anywhere in its text.
     *
     * @param rawPath raw project-relative path
     * @return whether the text contains {@code ..}
     */
    public static boolean containsTraversal(String rawPath) {
        return rawPath.contains("..");
    }

    /**
     * Checks lexical containment and the real path of the nearest existing target ancestor.
     *
     * @param assignedWorktree verified assigned worktree
     * @param targetFile normalized candidate file path
     * @return whether the candidate resolves within the assigned worktree
     * @throws IOException when the path cannot be resolved safely
     */
    public static boolean targetResolvesWithinWorktree(Path assignedWorktree, Path targetFile)
            throws IOException {
        Path root = Objects.requireNonNull(assignedWorktree, "assigned worktree")
                .toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(targetFile, "target file")
                .toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return false;
        }
        Path canonicalRoot = root.toRealPath();
        Path existingAncestor = target;
        while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)
                && existingAncestor.getParent() != null) {
            existingAncestor = existingAncestor.getParent();
        }
        return existingAncestor.toRealPath().startsWith(canonicalRoot);
    }
}
