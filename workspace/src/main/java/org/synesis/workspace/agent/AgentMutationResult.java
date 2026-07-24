package org.synesis.workspace.agent;

import java.util.Objects;

/**
 * Bounded result payload for file mutation operations.
 *
 * <p>Exposes only repository-relative paths without absolute worktree locations.
 *
 * @param path repository-relative file path
 * @since 1.0
 */
public record AgentMutationResult(String path) {

    /**
     * Validates and normalizes relative path.
     */
    public AgentMutationResult {
        Objects.requireNonNull(path, "path");
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(":\\")) {
            throw new IllegalArgumentException("Agent mutation result path must be relative: " + path);
        }
    }
}
