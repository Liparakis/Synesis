package org.synesis.cli.command.provider;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Installs or updates one project-local provider integration.
 */
@Command(name = "install", description = "Install a provider integration.", mixinStandardHelpOptions = true)
public final class ProviderInstallCommand implements Callable<Integer> {

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
    public ProviderInstallCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Installs the provider. @return exit code
     */
    @Override
    public Integer call() {
        return operate(provider);
    }

    private int operate(String id) {
        try {
            var location = ProjectOptions.locate(runtime, project);
            var result = runtime.providerService().install(location, id);
            return ProviderCommand.report(runtime, result);
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
    }
}
