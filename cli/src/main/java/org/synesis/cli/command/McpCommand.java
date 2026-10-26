package org.synesis.cli.command;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.daemon.ProjectRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.Terminal;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;
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
@Command(name = "mcp", description = "Launches the stdio Model Context Protocol (MCP) server", mixinStandardHelpOptions = true)
public final class McpCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final ProjectRuntime projectRuntime;
    @Option(names = {"--provider"}, description = "Provider name (default: codex)", defaultValue = "codex")
    private String provider;
    @Option(names = {"--connection-instance-id"}, description = "Process connection instance ID")
    private String connectionInstanceId;

    /**
     * Creates an MCP command instance.
     *
     * @param runtime CLI runtime environment
     */
    public McpCommand(CliRuntime runtime) {
        this(runtime, ProjectRuntime.DAEMON);
    }

    McpCommand(CliRuntime runtime, ProjectRuntime projectRuntime) {
        this.runtime = runtime;
        this.projectRuntime = projectRuntime;
    }

    /**
     * Starts the runtime of a project shared with another computer when it is down. Nothing else starts it after a
     * reboot, and an interactive agent's MCP server is the one process every such session starts, so without this its
     * log is not replicated and no code comes over Link until someone runs a {@code link} command. A single
     * computer's project needs no runtime and nothing is started for it. Never writes to standard output, which is
     * the protocol's.
     *
     * @param projects       project discovery
     * @param start          directory the MCP server serves; the project is the one containing it
     * @param projectRuntime the daemon's runtime of the project
     * @param terminal       where a failure is reported
     * @return whether the runtime was asked for
     */
    static boolean ensureSharedRuntime(ProjectApplicationService projects, Path start, ProjectRuntime projectRuntime, Terminal terminal) {
        try {
            ProjectLocation location = projects.locate(start);
            if (!Files.exists(location.coordinationDirectory().resolve(ReplicationSettings.FILE))) {
                return false;
            }
            projectRuntime.ensure(location.root());
            return true;
        } catch (ProjectApplicationException notAProject) {
            return false;
        } catch (IOException unavailable) {
            terminal.stderr("SYNESIS_MCP_RUNTIME=UNAVAILABLE reason=" + unavailable.getMessage());
            return false;
        }
    }

    /**
     * Asks for the runtime beside the server's start, so that the server answers its first request without waiting for
     * the daemon.
     */
    private void startRuntimeInBackground() {
        String configured = System.getenv("SYNESIS_MCP_PROJECT");
        Path start = Path.of(configured == null || configured.isBlank() ? "." : configured);
        Thread starter = new Thread(() -> ensureSharedRuntime(runtime.projectService(), start, projectRuntime, runtime.terminal()), "synesis-mcp-runtime");
        starter.setDaemon(true);
        starter.start();
    }

    @Override
    @SuppressWarnings("ExtractMethodRecommender")
    public Integer call() {
        startRuntimeInBackground();
        try {
            List<String> argsList = new ArrayList<>();
            if (provider != null && !provider.isBlank()) {
                argsList.add("--provider");
                argsList.add(provider);
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
            runtime.terminal().stderr("MCP subproject is not available on classpath: " + failure.getMessage());
            return ExitCodes.USAGE;
        } catch (Exception failure) {
            runtime.terminal().stderr("MCP server failed: " + failure.getMessage());
            return ExitCodes.INTERNAL;
        }
    }
}
