package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.synesis.workspace.lifecycle.codex.CodexAppServerLifecycleService.AppServerProcess;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope;

/**
 * Builds and starts the real direct {@code codex app-server} process for one binding.
 *
 * <p>This is the only place that knows the launch command, the environment variables and the
 * {@code -c} overrides that carry the exact binding into the App Server. It is stateless and kept
 * apart from {@link CodexAppServerLifecycleService} so the lifecycle state machine does not mix
 * process-launch configuration with attachment state.
 */
final class CodexAppServerDirectLaunch {

    private CodexAppServerDirectLaunch() {
    }

    /**
     * Starts one App Server in the exact assigned worktree and captures its process identity.
     *
     * @param authority verified authority context
     * @return launched process attachment
     * @throws IOException when launch fails
     */
    static AppServerProcess launch(LifecycleControlRequestEnvelope.AuthorityContext authority) throws IOException {
        List<String> command = configuredCommand(authority);
        ProcessBuilder builder = new ProcessBuilder(command).directory(Path.of(authority.realWorktree()).toFile()).redirectError(ProcessBuilder.Redirect.PIPE);
        builder.environment().put("SYNESIS_MCP_PROJECT", authority.controlProjectRoot());
        builder.environment().put("SYNESIS_MCP_CONNECTION_INSTANCE_ID", authority.connectionInstanceId());
        builder.environment().put("SYNESIS_MCP_PROVIDER", authority.provider());
        Process process = builder.start();
        var info = ProcessHandle.of(process.pid()).map(ProcessHandle::info).orElse(null);
        String executable = info == null ? command.getFirst() : info.command().orElse(command.getFirst());
        // Windows commonly exposes the executable and start instant but
        // omits ProcessHandle.Info.commandLine().  Persist the same
        // identity that the repeated-discovery inspector can observe in
        // that case; retaining a synthetic argv string would make a
        // verified attachment look unowned during shutdown.
        String commandIdentity = info == null ? executable : info.commandLine().orElse(executable);
        long started = info == null ? System.currentTimeMillis() : info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        return new AppServerProcess(process, executable, commandIdentity, started);
    }

    private static List<String> configuredCommand(LifecycleControlRequestEnvelope.AuthorityContext authority) {
        List<String> command = new ArrayList<>(configuredCommand());
        // Codex 0.145.0 does not reliably propagate arbitrary parent
        // environment variables into configured MCP subprocesses.  Keep the
        // inherited variables as a compatibility fallback, but also provide
        // the exact binding values through Codex's supported per-server env
        // overrides.  This remains one App Server process and one existing
        // Synesis MCP server; it only makes the already-required binding
        // explicit at the App Server boundary.
        command.add("-c");
        command.add("mcp_servers.synesis.env.SYNESIS_MCP_PROJECT=" + codexConfigString(authority.controlProjectRoot()));
        command.add("-c");
        command.add("mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID=" + codexConfigString(authority.connectionInstanceId()));
        command.add("-c");
        command.add("mcp_servers.synesis.env.SYNESIS_MCP_PROVIDER=" + codexConfigString(authority.provider()));
        return List.copyOf(command);
    }

    private static List<String> configuredCommand() {
        String configured = System.getenv("SYNESIS_CODEX_APP_SERVER_COMMAND");
        if (configured == null || configured.isBlank()) {
            return List.of("codex", "app-server");
        }
        String[] tokens = configured.trim().split("\\s+");
        return List.of(tokens);
    }

    private static String codexConfigString(String value) {
        String normalized = value.replace('\\', '/');
        return "\"" + normalized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
