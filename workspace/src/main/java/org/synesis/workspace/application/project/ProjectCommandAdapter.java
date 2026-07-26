package org.synesis.workspace.application.project;

import java.nio.file.Path;
import java.util.List;

/**
 * Strategy interface for build-system specific project command generation.
 *
 * @since 1.0
 */
public interface ProjectCommandAdapter {

    /**
     * Unique identifier for the build system adapter (e.g. "gradle", "maven", "dotnet", "npm", "git").
     *
     * @return adapter ID
     */
    String id();

    /**
     * Tests whether this adapter supports the project in the given worktree directory.
     *
     * @param worktreePath assigned worktree path
     * @return {@code true} if project indicator files exist
     */
    boolean supports(Path worktreePath);

    /**
     * Generates a direct token array for executing the requested command intent.
     *
     * @param worktreePath assigned worktree path
     * @param intent       command intent
     * @return direct command token list
     * @throws IllegalArgumentException if intent is not supported by this adapter
     */
    List<String> buildCommandTokens(Path worktreePath, ProjectCommandIntent intent);
}
