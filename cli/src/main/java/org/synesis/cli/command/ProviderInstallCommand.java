package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.synesis.cli.bootstrap.CliRuntime;

/** Installs or updates one project-local provider integration. */
@Command(name = "install", description = "Install a provider integration.", mixinStandardHelpOptions = true)
public final class ProviderInstallCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Provider identifier.") private String provider;
    @Option(names = "--project", description = "Project directory.") private String project;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public ProviderInstallCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Installs the provider. @return exit code */
    @Override public Integer call() { return operate("install", provider, false); }
    private int operate(String operation, String id, boolean unused) {
        try { var location = runtime.projectService().require(Path.of(project == null ? "." : project)); var result = runtime.providerService().install(location, id); result.values().forEach((key, value) -> runtime.terminal().stdout(key + "=" + value)); return result.exitCode(); }
        catch (Exception failure) { runtime.terminal().stderr("ERROR=PROJECT_NOT_CONFIGURED"); return 10; }
    }
}
