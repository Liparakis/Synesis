package org.synesis.workspace.application.project.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where Synesis keeps its application state on this computer: lane and integration worktrees, the default Link identity
 * directory, the daemon endpoint. Both the sandbox and the provider's file-tool guard keep agents away from it, so the
 * two must agree on what it is.
 */
public final class ApplicationStatePaths {

    private ApplicationStatePaths() {
    }

    /**
     * Lists the existing application-state folders: {@code ~/.synesis/Synesis} or {@code %LOCALAPPDATA%\Synesis}, the
     * older {@code ~/.synesis}, and the per-user state folder of the host.
     *
     * @return real paths of the folders that exist
     */
    public static List<Path> roots() {
        String home = System.getProperty("user.home");
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank() ? Path.of(home, ".synesis") : Path.of(local);
        List<Path> roots = new ArrayList<>(List.of(base.resolve("Synesis"), Path.of(home, ".synesis")));
        String stateHome = System.getenv("XDG_STATE_HOME");
        roots.add((stateHome == null || stateHome.isBlank() ? Path.of(home, ".local", "state") : Path.of(stateHome)).resolve("Synesis"));
        return roots.stream().filter(Files::isDirectory).map(ApplicationStatePaths::real).toList();
    }

    /**
     * Resolves a path through symbolic links when it exists, else normalizes it.
     *
     * @param path any path
     * @return the real path, or the absolute normalized path when it cannot be resolved
     */
    public static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException unavailable) {
            return path.toAbsolutePath().normalize();
        }
    }
}
