package org.synesis.workspace.application.project.execution.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What one confined command may touch. The paths are absolute and canonical, and the caller derives them from the
 * authoritative lane binding, never from the command's own input. Everything not named is read-only system state
 * (toolchains) or invisible, as the platform sandbox decides.
 *
 * @param writable           read-write: the lane's worktree, its private temp, the lane's own Git administrative directory
 * @param readOnly           readable but not writable: the control repository's Git directory and toolchain folders
 * @param readOnlyWithin     paths inside {@code writable} that stay read-only (the worktree's {@code .git} file, Git
 *                           directory pointers, provider configuration); skipped when absent
 * @param emptyReadOnlyFiles files that read as empty and cannot be created or changed, even when absent
 * @param hidden             neither readable nor writable: the control checkout, application state, other lanes
 * @param environment        variables a confining sandbox adds so the command uses its private folders (home, temp, Git object directory)
 */
public record SandboxPolicy(List<Path> writable, List<Path> readOnly, List<Path> readOnlyWithin, List<Path> emptyReadOnlyFiles, List<Path> hidden, Map<String, String> environment) {

    /**
     * Copies the lists.
     */
    public SandboxPolicy {
        writable = List.copyOf(writable);
        readOnly = List.copyOf(readOnly);
        readOnlyWithin = List.copyOf(readOnlyWithin);
        emptyReadOnlyFiles = List.copyOf(emptyReadOnlyFiles);
        hidden = List.copyOf(hidden);
        environment = Map.copyOf(environment);
    }
}
