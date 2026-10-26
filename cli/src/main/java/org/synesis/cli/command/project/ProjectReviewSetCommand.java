package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Signs whether review is mandatory for every work group announced in the project from now on.
 */
@Command(name = "set", description = "Make independent review mandatory for every work group, or not: mandatory or none.", mixinStandardHelpOptions = true)
public final class ProjectReviewSetCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    @Parameters(index = "0", arity = "1", paramLabel = "POLICY", description = "mandatory or none.")
    private String policy;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectReviewSetCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Signs the decision with the operator key. @return stable exit code
     */
    @Override
    public Integer call() {
        String value = policy.toLowerCase(java.util.Locale.ROOT).equals("mandatory") ? "MANDATORY" : policy.toLowerCase(java.util.Locale.ROOT).equals("none") ? "NONE" : null;
        if (value == null) {
            runtime.terminal().stderr("ERROR=REVIEW_POLICY_UNKNOWN");
            runtime.terminal().stderr("REASON=the review policy is mandatory or none");
            return ExitCodes.USAGE;
        }
        try {
            var decided = OperatorDecisions.sign(runtime, ProjectOptions.locate(runtime, project), Map.of(PolicyName.REVIEW, value), Optional.empty());
            runtime.terminal().stdout("REVIEW=" + value);
            OperatorDecisions.printDecided(runtime, decided);
            return ExitCodes.OK;
        } catch (ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (IOException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
