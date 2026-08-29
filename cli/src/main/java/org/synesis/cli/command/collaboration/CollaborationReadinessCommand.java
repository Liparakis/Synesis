package org.synesis.cli.command.collaboration;

import java.util.List;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.workspace.application.integration.IntegrationCompatibilityService;
import org.synesis.workspace.application.integration.WorkspaceIntegrationReadinessService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Evaluates one explicit pre-merge candidate through the shared service.
 */
@Command(name = "readiness", description = "Check integration readiness for one snapshot.", mixinStandardHelpOptions = true)
public final class CollaborationReadinessCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--control-head", required = true)
    private String controlHead;
    @Option(names = "--base", required = true)
    private String base;
    @Option(names = "--path", required = true)
    private List<String> paths;
    @Option(names = "--claim", required = true)
    private List<String> claims;
    @Option(names = "--tests-passed", defaultValue = "false")
    private boolean testsPassed;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationReadinessCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes the check. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var snapshot = new IntegrationCompatibilityService.SnapshotInput("cli-candidate", base, paths,
                    claims.stream()
                            .map(ResourceSelector::pathExact)
                            .toList(), List.of(), List.of());
            var result = new WorkspaceIntegrationReadinessService().check(new IntegrationCompatibilityService.CheckRequest(
                    controlHead, List.of(snapshot), List.of(), testsPassed));
            runtime.terminal()
                    .stdout("INTEGRATION_READY=" + result.accepted() + " FAILURES=" + result.failures() + " ACTIONS="
                            + result.actions());
            return result.accepted() ? ExitCodes.OK : ExitCodes.LOCAL_CONFIGURATION;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("INTEGRATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
