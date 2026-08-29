package org.synesis.cli.command;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Command wrapper for executing the Synesis Model Context Protocol (MCP) server.
 *
 * <p>Delegates dynamically to the {@code org.synesis.mcp.SynesisMcpServer} entrypoint via
 * reflection to maintain strict module decoupling between {@code :cli} and {@code :mcp}.
 *
 * @since 1.0
 */
@Command(name = "mcp", description = "Launches the stdio Model Context Protocol (MCP) server")
public final class McpCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = {"--provider"}, description = "Provider name (default: codex)", defaultValue = "codex")
    private String provider;
    @Option(names = {"--project"}, description = "Project root directory")
    private String project;
    @Option(names = {"--connection-instance-id"}, description = "Process connection instance ID")
    private String connectionInstanceId;

    /**
     * Creates an MCP command instance.
     *
     * @param runtime CLI runtime environment
     */
    public McpCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    @SuppressWarnings("ExtractMethodRecommender")
    public Integer call() {
        try {
            List<String> argsList = new ArrayList<>();
            if (provider != null && !provider.isBlank()) {
                argsList.add("--provider");
                argsList.add(provider);
            }
            if (project != null && !project.isBlank()) {
                argsList.add("--project");
                argsList.add(project);
            }
            if (connectionInstanceId != null && !connectionInstanceId.isBlank()) {
                argsList.add("--connection-instance-id");
                argsList.add(connectionInstanceId);
            }

            String[] args = argsList.toArray(new String[0]);

            Class<?> mcpClass = Class.forName("org.synesis.mcp.SynesisMcpServer");
            Method executeMethod = mcpClass.getMethod("execute", String[].class);
            return (Integer) executeMethod.invoke(null, (Object) args);
        } catch (ClassNotFoundException failure) {
            runtime.terminal()
                    .stderr("MCP subproject is not available on classpath: " + failure.getMessage());
            return ExitCodes.USAGE;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("MCP server failed: " + failure.getMessage());
            return ExitCodes.INTERNAL;
        }
    }
}
