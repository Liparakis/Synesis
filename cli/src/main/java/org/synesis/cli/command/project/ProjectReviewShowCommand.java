package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shows whether review is mandatory, as the project's signed policy says.
 */
@Command(name = "show", description = "Show whether every work group needs an independent review.", mixinStandardHelpOptions = true)
public final class ProjectReviewShowCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectReviewShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the policy. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            runtime.terminal().stdout("REVIEW=" + (ProjectOptions.locate(runtime, project).review().mandatory() ? "MANDATORY" : "NONE"));
            return ExitCodes.OK;
        } catch (ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (IOException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
