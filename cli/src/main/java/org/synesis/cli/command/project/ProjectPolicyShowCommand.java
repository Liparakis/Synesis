package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.ProjectPolicyService;
import org.synesis.workspace.application.project.ProjectPolicyService.Snapshot;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shows which policy is in force and where it comes from: the signed log once the project has a baseline, the project
 * file before.
 */
@Command(name = "show", description = "Show the policy in force and whether it is signed.", mixinStandardHelpOptions = true)
public final class ProjectPolicyShowCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectPolicyShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the policy. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            ProjectLocation location = ProjectOptions.locate(runtime, project);
            if (!location.policyReceived()) {
                runtime.terminal().stdout("POLICY_BASELINE=NOT_RECEIVED");
                return ExitCodes.OK;
            }
            Optional<Snapshot> signed = ProjectPolicyService.read(location).snapshot();
            runtime.terminal().stdout("POLICY_BASELINE=" + (signed.isPresent() ? "SIGNED" : "NOT_ADOPTED"));
            runtime.terminal().stdout("POLICY_SOURCE=" + (signed.isPresent() ? "LOG" : "FILE"));
            ProjectValidationCommand.print(runtime, location);
            runtime.terminal().stdout("REVIEW=" + (location.review().mandatory() ? "MANDATORY" : "NONE"));
            if (signed.isPresent()) {
                runtime.terminal().stdout("POLICY_REVISION=" + signed.get().revision());
                runtime.terminal().stdout("UNSANDBOXED_COMMANDS=" + (signed.get().unsandboxedAllowed() ? "ALLOW" : "DENY"));
                if (ProjectPolicyService.fileDisagreement(location).isPresent()) {
                    runtime.terminal().stdout("POLICY_FILE=IGNORED");
                }
            } else {
                runtime.terminal().stdout("POLICY_ADOPTION=" + (ProjectPolicyService.adoption(location).needsConfirmation() ? "CONFIRMATION_NEEDED" : "READY"));
            }
            return ExitCodes.OK;
        } catch (ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (IOException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
