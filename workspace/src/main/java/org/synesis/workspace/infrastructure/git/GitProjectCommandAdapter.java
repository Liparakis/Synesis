package org.synesis.workspace.infrastructure.git;

import org.synesis.workspace.application.project.ProjectCommandAdapter;
import org.synesis.workspace.application.project.ProjectCommandIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for executing read-only Git operations inside the assigned worktree.
 *
 * @since 1.0
 */
public final class GitProjectCommandAdapter implements ProjectCommandAdapter {

    /**
     * Creates a Git project command adapter.
     */
    public GitProjectCommandAdapter() {
    }

    @Override
    public String id() {
        return "git";
    }

    @Override
    public boolean supports(Path worktreePath) {
        if (worktreePath == null) {
            return false;
        }
        return Files.exists(worktreePath.resolve(".git"));
    }

    @Override
    public List<String> buildCommandTokens(Path worktreePath, ProjectCommandIntent intent) {
        if (intent == null || intent.type() == null) {
            throw new IllegalArgumentException("Invalid command intent");
        }

        List<String> tokens = new ArrayList<>();
        tokens.add("git");

        String type = intent.type().toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case "git_status" -> {
                tokens.add("status");
                tokens.add("--porcelain");
            }
            case "git_diff" -> {
                tokens.add("diff");
                if (intent.target() != null && !intent.target().isBlank()) {
                    tokens.add(intent.target().trim());
                }
            }
            case "git_log" -> {
                tokens.add("log");
                tokens.add("-n");
                tokens.add("10");
                tokens.add("--oneline");
            }
            default -> throw new IllegalArgumentException("Unsupported git command type: " + type);
        }

        return tokens;
    }
}
