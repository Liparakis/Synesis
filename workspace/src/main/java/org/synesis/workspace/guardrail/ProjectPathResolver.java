package org.synesis.workspace.guardrail;

import java.nio.file.Path;
import java.util.Objects;

import org.synesis.projectrecord.ScopeMatcher;

/**
 * Resolves trusted hook paths to normalized project-relative scopes.
 */
public final class ProjectPathResolver {

    private ProjectPathResolver() {
    }

    /**
     * Resolves an absolute or project-relative path.
     *
     * @param projectRoot project root directory
     * @param rawPath     raw path from the provider payload
     * @return normalized project-relative path, or {@code null} for blank input
     * @throws IllegalArgumentException if the path escapes the project root
     */
    public static String resolve(Path projectRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize();
        Path target = Path.of(rawPath);
        if (!target.isAbsolute()) {
            target = root.resolve(target);
        }
        target = target.normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Target path outside project root: " + rawPath);
        }
        return ScopeMatcher.normalizePath(root.relativize(target)
                .toString());
    }
}
