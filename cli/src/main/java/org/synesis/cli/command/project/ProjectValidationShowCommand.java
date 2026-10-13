package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shows the command a project declares that must pass before its work is integrated.
 */
@Command(name = "show", description = "Show the command that must pass before work is integrated.", mixinStandardHelpOptions = true)
public final class ProjectValidationShowCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectValidationShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the declared command. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            ProjectValidationCommand.print(runtime, ProjectOptions.locate(runtime, project));
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
