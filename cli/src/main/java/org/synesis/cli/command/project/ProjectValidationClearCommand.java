package org.synesis.cli.command.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectValidationChange;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Removes the command a project declares that must pass before its work is integrated. From then
 * on nothing gates integration but review, where the project requires it.
 */
@Command(name = "clear", description = "Remove the command that must pass before work is integrated.", mixinStandardHelpOptions = true)
public final class ProjectValidationClearCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectValidationClearCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Rewrites the project file without a command. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            ProjectValidationChange change = runtime.projectService().clearValidation(Path.of(project));
            ProjectValidationCommand.print(runtime, change.location());
            ProjectValidationCommand.printChanged(runtime, change);
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            ProjectValidationCommand.printFailure(runtime, failure);
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (IOException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
