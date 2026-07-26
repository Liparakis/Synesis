package org.synesis.mcp;

import java.nio.file.Path;
import java.util.UUID;
import org.synesis.mcp.application.McpProtocolHandler;
import org.synesis.mcp.transport.stdio.McpStdioServer;
import org.synesis.workspace.application.AgentSessionService;

/**
 * Main process entrypoint for the Synesis Model Context Protocol (MCP) server.
 *
 * <p>Parses launch arguments ({@code --provider}, {@code --project}, {@code --connection-instance-id}),
 * initializes ambient session resolution, and starts the stdio event loop.
 *
 * @since 1.0
 */
public final class SynesisMcpServer {

    private SynesisMcpServer() {
    }

    /**
     * Executes the MCP server process.
     *
     * @param arguments command-line launch arguments
     * @return process exit code
     */
    public static int execute(String[] arguments) {
        String provider = "codex";
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        String connectionInstanceId = "conn-instance-" + UUID.randomUUID();

        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            if ("--provider".equals(arg) && i + 1 < arguments.length) {
                provider = arguments[++i].trim();
            } else if ("--project".equals(arg) && i + 1 < arguments.length) {
                projectRoot = Path.of(arguments[++i]).toAbsolutePath().normalize();
            } else if ("--connection-instance-id".equals(arg) && i + 1 < arguments.length) {
                connectionInstanceId = arguments[++i].trim();
            }
        }

        long pid = ProcessHandle.current().pid();
        System.err.println("SYNESIS_MCP_STARTUP pid=" + pid + " version=0.1.0-SNAPSHOT commit=bc334ac conn=" + connectionInstanceId + " provider=" + provider + " cwd=" + Path.of(".").toAbsolutePath().normalize());

        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, projectRoot, provider, connectionInstanceId);
        McpStdioServer server = new McpStdioServer(handler);

        return server.run();
    }

    /**
     * Entrypoint for standalone process execution.
     *
     * @param arguments launch arguments
     */
    public static void main(String[] arguments) {
        System.exit(execute(arguments));
    }
}
