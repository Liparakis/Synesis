package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Locates and removes the disposable Git worktrees Synesis keeps outside the control checkout,
 * under {@code <local app data or ~/.synesis>/Synesis/workspaces/<project-id>/<kind>}.
 */
final class ExternalWorktrees {

    private ExternalWorktrees() {
    }

    /**
     * Resolves the external directory that holds one kind of disposable worktree for a project.
     *
     * @param projectRoot control project root path
     * @param kind        worktree kind, such as {@code integration} or {@code validation}
     * @return absolute external directory
     */
    static Path root(Path projectRoot, String kind) {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces", resolveProjectId(projectRoot), kind).toAbsolutePath().normalize();
    }

    /**
     * Removes a worktree with {@code git worktree remove --force}, run from the worktree's own top
     * level (or the path itself when Git cannot name one). Absent worktrees and Git failures are
     * ignored: the caller's outcome is already decided, and doctor reports abandoned worktrees.
     *
     * @param worktreePath worktree directory, or {@code null}
     */
    static void removeBestEffort(Path worktreePath) {
        if (worktreePath == null || !Files.exists(worktreePath)) {
            return;
        }
        try {
            GitProcessRunner.run(topLevelOrSelf(worktreePath), "worktree", "remove", "--force", worktreePath.toString());
        } catch (IOException ignored) {
        }
    }

    private static Path topLevelOrSelf(Path path) {
        try {
            return Path.of(GitProcessRunner.run(path, "rev-parse", "--show-toplevel")).toAbsolutePath().normalize();
        } catch (IOException failure) {
            return path;
        }
    }

    private static String resolveProjectId(Path projectRoot) {
        Path projFile = projectRoot.resolve(".synesis/project.json");
        if (Files.exists(projFile)) {
            try {
                String content = Files.readString(projFile);
                int idx = content.indexOf("\"projectId\"");
                if (idx != -1) {
                    int colon = content.indexOf(':', idx);
                    int q1 = content.indexOf('"', colon + 1);
                    int q2 = content.indexOf('"', q1 + 1);
                    if (colon != -1 && q1 != -1 && q2 != -1) {
                        return content.substring(q1 + 1, q2);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "default-project";
    }
}
