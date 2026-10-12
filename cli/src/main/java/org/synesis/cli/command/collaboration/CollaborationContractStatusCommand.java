package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Lists the public contracts, the consumers bound to them, and the change requests still open.
 */
@Command(name = "status", description = "Inspect shared contracts.", mixinStandardHelpOptions = true)
public final class CollaborationContractStatusCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
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
            var contracts = runtime.projectService().require(project.toAbsolutePath().normalize()).openCoordinationStore().contractProjection();
            contracts.contracts().forEach(c -> runtime.terminal().stdout("CONTRACT=" + c.contractId() + " REVISION=" + c.revision() + " STATE=" + c.state() + " OWNER_LINEAGE=" + c.ownerLineageId()
                    + " PATHS=" + String.join(",", c.paths().stream().map(path -> path.value()).toList())));
            contracts.contracts().forEach(c -> contracts.bindings(c.contractId())
                    .forEach(b -> runtime.terminal().stdout("BINDING=" + b.handle() + " CONTRACT=" + b.contractId() + " REVISION=" + b.revision() + (b.revision() == c.revision() ? "" : " STALE=true"))));
            contracts.contracts().stream().map(c -> c.ownerLineageId()).distinct().forEach(owner -> contracts.openChangeRequestsFor(owner)
                    .forEach(r -> runtime.terminal().stdout("CHANGE_REQUEST=" + r.id() + " STATE=" + r.state() + " PATH=" + r.path().value())));
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("CONTRACT_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
