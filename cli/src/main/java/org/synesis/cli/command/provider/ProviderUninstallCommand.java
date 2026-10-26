package org.synesis.cli.command.provider;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Removes one provider's project configuration, or its shared user-level Codex MCP entry.
 */
@Command(name = "uninstall", description = "Uninstall a provider integration.", mixinStandardHelpOptions = true)
public final class ProviderUninstallCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Parameters(index = "0", description = "Provider identifier.")
    @SuppressWarnings("unused")
    private String provider;
    @Option(names = "--project", description = "Project directory.")
    private String project;
    @Option(names = "--global", description = "Remove only the shared user-level Codex MCP entry.")
    private boolean global;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProviderUninstallCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Uninstalls the selected project hook or shared MCP entry. @return exit code
     */
    @Override
    public Integer call() {
        if (global) {
            if (project != null) {
                ErrorShape.print(runtime.terminal()::stderr, "CONFLICTING_UNINSTALL_SCOPE");
                return ExitCodes.USAGE;
            }
            return ProviderCommand.report(runtime, runtime.providerService().uninstallGlobalMcpConfiguration(provider));
        }
        try {
            var location = ProjectOptions.locate(runtime, project);
            return ProviderCommand.report(runtime, runtime.providerService().uninstall(location, provider));
        } catch (Exception failure) {
            return ProjectOptions.refuse(runtime, failure);
        }
    }
}
