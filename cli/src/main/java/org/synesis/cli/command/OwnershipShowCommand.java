package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.OwnershipClaim;
import org.synesis.coordination.domain.PredictionEventType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Shows one capability ownership claim from replay.
 */
@Command(name = "show", description = "Show semantic ownership.", mixinStandardHelpOptions = true)
public final class OwnershipShowCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--capability", required = true)
    private String capability;

    /**
     * Creates an ownership show command.
     *
     * @param runtime composed CLI runtime
     */
    public OwnershipShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Replays and prints one ownership claim.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            CoordinationCommand found = null;
            OwnershipClaim current = null;
            for (var event : CoordinationCliSupport.replay(endpoint, 0)) {
                if (event.type() != PredictionEventType.OWNERSHIP_CLAIMED
                        && event.type() != PredictionEventType.OWNERSHIP_RELEASED) {
                    continue;
                }
                OwnershipClaim claim = OwnershipClaim.decode(CoordinationCommand.decode(event.payload())
                        .payload());
                if (!claim.capability()
                        .equals(capability)) {
                    continue;
                }
                found = CoordinationCommand.decode(event.payload());
                current = event.type() == PredictionEventType.OWNERSHIP_CLAIMED
                        ? claim : null;
            }
            if (found == null || current == null) {
                throw new IllegalArgumentException("OWNERSHIP_NOT_FOUND");
            }
            runtime.terminal()
                    .stdout("CAPABILITY=" + current.capability());
            runtime.terminal()
                    .stdout("OWNER_NODE=" + current.ownerNodeId());
            runtime.terminal()
                    .stdout("OWNER_SUPERVISOR=" + current.ownerSupervisorId());
            runtime.terminal()
                    .stdout("TASK_ID=" + current.taskId());
            runtime.terminal()
                    .stdout("INTENT_VERSION=" + current.intentVersion());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("OWNERSHIP_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
