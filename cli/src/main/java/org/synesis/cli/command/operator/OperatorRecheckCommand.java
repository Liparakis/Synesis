package org.synesis.cli.command.operator;

import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.integration.IntegrationOrchestrationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Asks Synesis to look again at what its operator requests are waiting for.
 *
 * <p>There is no command that declares a request resolved. A request closes when its condition is
 * observed not to hold; this runs that observation now, and integrates the work that was waiting
 * on it, without an agent having to be running.</p>
 */
@Command(name = "recheck", description = "Check again whether the open operator requests still hold, and integrate what was waiting.", mixinStandardHelpOptions = true)
public final class OperatorRecheckCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public OperatorRecheckCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Runs one integration pass and prints the requests still open. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = ProjectOptions.locate(runtime, project);
            var pass = new IntegrationOrchestrationService().orchestrateIntegration(location.root(), location.openCoordinationStore(), location.loadOrCreateIdentity());
            runtime.terminal().stdout("RECHECK=" + pass.status().value() + (pass.reason() == null ? "" : " REASON=" + pass.reason().value()));
            var open = location.openCoordinationStore().operatorRequestProjection().open();
            OperatorListCommand.print(runtime, open);
            return open.isEmpty() && pass.status() != org.synesis.workspace.agent.AgentStatus.FAILED ? ExitCodes.OK : ExitCodes.COMMAND_FAILED;
        } catch (Exception failure) {
            runtime.terminal().stderr("OPERATOR_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
