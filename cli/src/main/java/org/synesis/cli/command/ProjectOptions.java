package org.synesis.cli.command;

import java.io.IOException;
import java.nio.file.Path;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
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

    /**
     * Reports why a project could not be located, with the cause the failure carries. A directory that is not an
     * initialized project keeps its own code, {@code PROJECT_NOT_CONFIGURED}; every other cause names itself, so a
     * damaged project, an unusable path or an unreadable file is never reported as a missing setup.
     *
     * @param runtime CLI runtime
     * @param failure what {@link #locate} (or the work after it) threw
     * @return {@link ExitCodes#LOCAL_CONFIGURATION}, as the blanket report always returned
     */
    public static int refuse(CliRuntime runtime, Exception failure) {
        if (failure instanceof ProjectApplicationException application && "NOT_FOUND".equals(application.code())) {
            ErrorShape.print(runtime.terminal()::stderr, "PROJECT_NOT_CONFIGURED");
        } else if (failure instanceof ProjectApplicationException application) {
            ErrorShape.print(runtime.terminal()::stderr, application.code());
            runtime.terminal().stderr("REASON=" + application.getMessage());
        } else {
            ErrorShape.print(runtime.terminal()::stderr, "PROJECT_ACCESS_FAILED");
            runtime.terminal().stderr("REASON=" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
        return ExitCodes.LOCAL_CONFIGURATION;
    }
}
