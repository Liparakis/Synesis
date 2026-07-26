package org.synesis.cli.command.speculation;


import org.synesis.cli.command.coordination.CoordinationCliSupport;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.domain.PredictionEventType;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Invalidates a prediction and removes any local speculation workspace.
 */
@Command(name = "invalidate", description = "Invalidate a prediction.", mixinStandardHelpOptions = true)
public final class SpeculationInvalidateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project")
    private Path project;
    @Option(names = "--endpoint", required = true)
    private URI endpoint;
    @Option(names = "--profile", required = true)
    private Path profile;
    @Option(names = "--prediction", required = true)
    private UUID prediction;

    /**
     * Creates an invalidation command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationInvalidateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Appends invalidation and best-effort closes local speculation. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var identity = CoordinationCliSupport.loadIdentity(profile);
            CoordinationCliSupport.submit(endpoint, CoordinationCommand.create(UUID.randomUUID(), location.projectId(),
                    prediction, PredictionEventType.PREDICTION_INVALIDATED, identity.nodeId(), new byte[0], identity));
            try {
                SpeculationRetireCommand.close(location, prediction);
            } catch (java.io.IOException ignored) {
            }
            runtime.terminal()
                    .stdout("PREDICTION_INVALIDATED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("SPECULATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
