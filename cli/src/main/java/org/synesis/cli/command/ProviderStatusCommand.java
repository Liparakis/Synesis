package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.synesis.cli.bootstrap.CliRuntime;

/** Inspects one provider integration without repairing it. */
@Command(name = "status", description = "Inspect provider status.", mixinStandardHelpOptions = true)
public final class ProviderStatusCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Provider identifier.") private String provider;
    @Option(names = "--project", description = "Project directory.") private String project;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public ProviderStatusCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Inspects the provider. @return exit code */
    @Override public Integer call() { try { var location = runtime.projectService().require(Path.of(project == null ? "." : project)); var result = runtime.providerService().status(location, provider); result.values().forEach((key, value) -> runtime.terminal().stdout(key + "=" + value)); return result.exitCode(); } catch (Exception failure) { runtime.terminal().stderr("ERROR=PROJECT_NOT_CONFIGURED"); return 10; } }
}
