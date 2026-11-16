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
 * Puts the project's operator key back on this computer from the 24 words shown when it was made (ADR-0104,
 * "Operator-key recovery"): for a lost key file, a forgotten passphrase, or a new owner's computer. The words must give
 * the key the project registered, so this restores that key and can never register another; the log is not written.
 */
@Command(name = "restore-key", description = "Restore the operator key from its 24 words, under a new passphrase.", mixinStandardHelpOptions = true)
public final class ProjectPolicyRestoreKeyCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public ProjectPolicyRestoreKeyCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Restores the key. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            String keyId = OperatorDecisions.restore(runtime, ProjectOptions.locate(runtime, project));
            runtime.terminal().stdout("OPERATOR_KEY=" + keyId);
            runtime.terminal().stdout("OPERATOR_KEY_RESTORED=true");
            return ExitCodes.OK;
        } catch (ProjectApplicationException failure) {
            return OperatorDecisions.refuse(runtime, failure);
        } catch (IOException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
