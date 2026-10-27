package org.synesis.cli.command.project;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.PolicyNotReceivedException;
import org.synesis.workspace.application.project.PolicyUnavailableException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Signs, clears and shows whether agents' commands may run unsandboxed on a computer that has no sandbox (ADR-0104,
 * Part C). It is an owner decision signed with the operator key and kept in the project's log; no environment
 * variable, flag or file grants it.
 */
@Command(name = "sandbox", description = "Allow, deny or show commands that run without a sandbox where none is available.", mixinStandardHelpOptions = true)
public final class ProjectSandboxCommand {

    private final CliRuntime runtime;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectSandboxCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Signs that commands may run unsandboxed when this project's computers have no sandbox.
     *
     * @param project project directory
     * @return stable exit code
     */
    @Command(name = "allow-unsandboxed", description = "Sign that agents' commands may run without a sandbox on a computer that has none.", mixinStandardHelpOptions = true)
    public int allow(@Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).") String project) {
        return decide(project, "ALLOW");
    }

    /**
     * Signs that commands never run unsandboxed, which is the default.
     *
     * @param project project directory
     * @return stable exit code
     */
    @Command(name = "deny-unsandboxed", description = "Sign that agents' commands are refused when no sandbox is available (the default).", mixinStandardHelpOptions = true)
    public int deny(@Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).") String project) {
        return decide(project, "DENY");
    }

    /**
     * Shows the decision in force.
     *
     * @param project project directory
     * @return stable exit code
     */
    @Command(name = "show", description = "Show whether commands may run without a sandbox where none is available.", mixinStandardHelpOptions = true)
    public int show(@Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).") String project) {
        try {
            runtime.terminal().stdout("UNSANDBOXED_COMMANDS=" + (ProjectOptions.locate(runtime, project).unsandboxedAllowed() ? "ALLOW" : "DENY"));
            return ExitCodes.OK;
        } catch (ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (PolicyUnavailableException | PolicyNotReceivedException refused) {
            return OperatorDecisions.refuse(runtime, refused);
        } catch (IOException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private int decide(String project, String value) {
        try {
            var decided = OperatorDecisions.sign(runtime, ProjectOptions.locate(runtime, project), Map.of(PolicyName.SANDBOX_UNSANDBOXED, value), Optional.empty());
            runtime.terminal().stdout("UNSANDBOXED_COMMANDS=" + value);
            if (value.equals("ALLOW")) {
                runtime.terminal().stdout("WARNING=on a computer with no sandbox, an agent's command can do what your user can do, including reading keys and changing this project");
            }
            OperatorDecisions.printDecided(runtime, decided);
            return ExitCodes.OK;
        } catch (ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (PolicyUnavailableException | PolicyNotReceivedException refused) {
            return OperatorDecisions.refuse(runtime, refused);
        } catch (IOException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
