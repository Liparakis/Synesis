package org.synesis.workspace.agent;

import java.util.Objects;

/**
 * Bounded result payload for file mutation operations.
 *
 * <p>Exposes only repository-relative paths without absolute worktree locations.
 *
 * @param path repository-relative file path
 * @param revision updated opaque file revision
 * @param changedFiles number of modified files
 * @since 1.0
 */
public record AgentMutationResult(String path, String revision, int changedFiles) {

    /**
     * Validates and normalizes relative path.
     */
    public AgentMutationResult {
        Objects.requireNonNull(path, "path");
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(":\\")) {
            throw new IllegalArgumentException("Agent mutation result path must be relative: " + path);
        }
        if (changedFiles < 1) {
            throw new IllegalArgumentException("changedFiles must be positive");
        }
    }
}
