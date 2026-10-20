package org.synesis.cli.command.provider;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Inspects one provider integration without repairing it.
 */
@Command(name = "status", description = "Inspect provider status.", mixinStandardHelpOptions = true)
public final class ProviderStatusCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Parameters(index = "0", description = "Provider identifier.")
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private String provider;
    @Option(names = "--project", description = "Project directory.")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProviderStatusCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Inspects the provider. @return exit code
     */
    @Override
    public Integer call() {
        try {
            var location = ProjectOptions.locate(runtime, project);
            var result = runtime.providerService().status(location, provider);
            return ProviderCommand.report(runtime, result);
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
    }
}
