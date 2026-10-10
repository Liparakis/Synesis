package org.synesis.cli.command;

import java.io.IOException;
import java.nio.file.Path;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Resolves the {@code --project} and {@code --profile} string options shared by commands that do not record the
 * project in the known-project registry. Coordination commands with {@code Path} options use
 * {@code CoordinationCliSupport} instead.
 */
public final class ProjectOptions {

    private ProjectOptions() {
    }

    /**
     * Resolves the project named by {@code --project}, or the current directory.
     *
     * @param runtime CLI runtime
     * @param project requested project directory, or {@code null}
     * @return resolved project location
     * @throws ProjectApplicationException if the directory is not an initialized project
     * @throws IOException                  if repository-private exclusions cannot be maintained
     */
    public static ProjectLocation locate(CliRuntime runtime, String project) throws ProjectApplicationException, IOException {
        return runtime.projectService().require(Path.of(project == null ? "." : project));
    }

    /**
     * Resolves the project and replaces its profile directory when {@code --profile} is given.
     *
     * @param runtime CLI runtime
     * @param project requested project directory, or {@code null}
     * @param profile profile directory override, or {@code null}
     * @return resolved project location carrying the selected profile
     * @throws ProjectApplicationException if the directory is not an initialized project
     * @throws IOException                  if repository-private exclusions cannot be maintained
     */
    public static ProjectLocation locate(CliRuntime runtime, String project, String profile) throws ProjectApplicationException, IOException {
        var location = locate(runtime, project);
        if (profile == null) {
            return location;
        }
        return new ProjectLocation(location.root(), location.synesisDirectory(), location.metadataFile(), Path.of(profile), location.projectId(), location.createdAt());
    }

    /**
     * Selects the profile directory: the {@code --profile} override, else the project's own.
     *
     * @param location resolved project location
     * @param profile  profile directory override, or {@code null}
     * @return profile directory
     */
    public static Path profile(ProjectLocation location, String profile) {
        return profile == null ? location.profile() : Path.of(profile);
    }
}
