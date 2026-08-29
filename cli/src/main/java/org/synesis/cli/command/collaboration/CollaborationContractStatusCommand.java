package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Inspects replayed shared contracts and dependencies.
 */
@Command(name = "status", description = "Inspect shared contracts.", mixinStandardHelpOptions = true)
public final class CollaborationContractStatusCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".")
    private Path project;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationContractStatusCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes inspection. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var snapshot = new WorkspaceCollaborationService().contractStatus(project.toAbsolutePath()
                    .normalize());
            snapshot.contracts()
                    .forEach(c -> runtime.terminal()
                            .stdout("CONTRACT=" + c.contractId() + " REVISION=" + c.revision() + " STATUS=" + c.status()
                                    + " OWNER=" + c.owner()));
            snapshot.dependencies()
                    .forEach(d -> runtime.terminal()
                            .stdout("DEPENDENCY=" + d.intentId() + " CONTRACT=" + d.contractId() + " REVISION="
                                    + d.revision() + " STATE=" + d.state()));
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("CONTRACT_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
