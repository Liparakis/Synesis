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
import org.synesis.workspace.application.project.ProjectPolicyService.AdoptionChoice;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Signs the policy a project kept in {@code .synesis/project.json} as its baseline (ADR-0104, Migration), creating the
 * operator key on the way. The file as committed on the control branch's head is adopted; when the working file
 * differs, or the file is not committed, the person picks which one with {@code --from}.
 */
@Command(name = "adopt", description = "Sign the policy kept in project.json as the project's baseline; --from head or file picks one when they differ.", mixinStandardHelpOptions = true)
public final class ProjectPolicyAdoptCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    @Option(names = "--from", paramLabel = "head|file", description = "Which copy of project.json to sign: head is the committed file, file the working file.")
    private String from;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectPolicyAdoptCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Signs the baseline. @return stable exit code
     */
    @Override
    public Integer call() {
        Optional<AdoptionChoice> choice;
        if (from == null) {
            choice = Optional.empty();
        } else if (from.equalsIgnoreCase("head")) {
            choice = Optional.of(AdoptionChoice.HEAD);
        } else if (from.equalsIgnoreCase("file")) {
            choice = Optional.of(AdoptionChoice.FILE);
        } else {
            runtime.terminal().stderr("ERROR=POLICY_SOURCE_UNKNOWN");
            runtime.terminal().stderr("REASON=--from is head or file");
            return ExitCodes.USAGE;
        }
        try {
            var location = ProjectOptions.locate(runtime, project);
            if (org.synesis.workspace.application.project.ProjectPolicyService.read(location).snapshot().isPresent()) {
                runtime.terminal().stdout("POLICY_BASELINE=SIGNED");
                runtime.terminal().stdout("POLICY_ADOPTED=NONE");
                return ExitCodes.OK;
            }
            var decided = OperatorDecisions.sign(runtime, location, Map.of(), choice);
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
