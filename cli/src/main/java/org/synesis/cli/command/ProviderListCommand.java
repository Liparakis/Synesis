package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

/** Lists implemented project-local providers. */
@Command(name = "list", description = "List provider integrations.", mixinStandardHelpOptions = true)
public final class ProviderListCommand implements Callable<Integer> {
    @Option(names = "--project", description = "Project directory.") private String project;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public ProviderListCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Lists providers. @return exit code */
    @Override public Integer call() {
        try {
            var location = runtime.projectService().require(Path.of(project == null ? "." : project));
            var rows = runtime.providerService().list(location);
            runtime.terminal().stdout("PROVIDER_COUNT=" + rows.size());
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                runtime.terminal().stdout("PROVIDER_" + (i + 1) + "_ID=" + row.id());
                runtime.terminal().stdout("PROVIDER_" + (i + 1) + "_SUPPORT_LEVEL=" + row.supportLevel());
                runtime.terminal().stdout("PROVIDER_" + (i + 1) + "_STATUS=" + row.status());
            }
            return ExitCodes.OK;
        } catch (Exception failure) { runtime.terminal().stderr("ERROR=PROJECT_NOT_CONFIGURED"); return ExitCodes.LOCAL_CONFIGURATION; }
    }
}
