package org.synesis.cli.command.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectValidationChange;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Declares the command that must pass on the combined work before it is integrated.
 */
@Command(name = "set", description = "Declare the command that must pass before work is integrated. Give it after `--`; no shell is used.", mixinStandardHelpOptions = true)
public final class ProjectValidationSetCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    @Option(names = "--timeout", paramLabel = "SECONDS", description = "Seconds the command may run (default: 120).")
    private Integer timeout;

    @Option(names = "--directory", paramLabel = "DIR", defaultValue = ".", description = "Working directory, relative to the project root (default: .).")
    private String directory;

    @Option(names = "--also-in-lane", description = "Also run the command in each lane alone before its finish is published. A lane whose tests need another lane's code cannot pass it alone.")
    private boolean alsoInLane;

    @Parameters(arity = "1..*", paramLabel = "ARGV", description = "The executable and its arguments.")
    private List<String> argv;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectValidationSetCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Rewrites the project file with the command. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            ProjectCommandSpec validation = new ProjectCommandSpec(argv, directory, timeout == null ? ProjectCommandSpec.DEFAULT_TIMEOUT_SECONDS : timeout);
            ProjectValidationChange change = runtime.projectService().setValidation(Path.of(project), validation, alsoInLane);
            ProjectValidationCommand.print(runtime, change.location());
            ProjectValidationCommand.printChanged(runtime, change);
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            ProjectValidationCommand.printFailure(runtime, failure);
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
            runtime.terminal().stderr("REASON=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (IOException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
