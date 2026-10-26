package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.workspace.application.ProjectApplicationService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Removes the command a project declares that must pass before its work is integrated, as a decision signed with the
 * operator key (ADR-0104). From then on nothing gates integration but review, where the project requires it.
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
     * Signs the decision with the operator key. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            Map<PolicyName, String> decisions = new LinkedHashMap<>();
            decisions.put(PolicyName.VALIDATION, "");
            decisions.put(PolicyName.VALIDATION_ALSO_IN_LANE, "false");
            var decided = OperatorDecisions.sign(runtime, ProjectOptions.locate(runtime, project), decisions, Optional.empty());
            ProjectValidationCommand.print(runtime, ProjectOptions.locate(runtime, project));
            OperatorDecisions.printDecided(runtime, decided);
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (org.synesis.workspace.application.project.PolicyUnavailableException | org.synesis.workspace.application.project.PolicyNotReceivedException refused) {
            return OperatorDecisions.refuse(runtime, refused);
        } catch (RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (IOException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
