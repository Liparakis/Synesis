package org.synesis.mcp;

import java.nio.file.Path;
import java.util.UUID;
import org.synesis.mcp.application.McpProtocolHandler;
import org.synesis.mcp.transport.stdio.McpStdioServer;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.RuntimeAuthenticator;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

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
        String provider = boundedEnvironment("SYNESIS_MCP_PROVIDER", "codex");
        String configuredProject = boundedEnvironmentOrNull("SYNESIS_MCP_PROJECT");
        boolean projectRootPinned = configuredProject != null;
        Path projectRoot = Path.of(configuredProject == null ? "." : configuredProject)
                .toAbsolutePath()
                .normalize();
        String connectionInstanceId = boundedEnvironment("SYNESIS_MCP_CONNECTION_INSTANCE_ID",
                "conn-instance-" + UUID.randomUUID());

        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            if ("--provider".equals(arg) && i + 1 < arguments.length) {
                provider = arguments[++i].trim();
            } else if ("--project".equals(arg)) {
                if (i + 1 >= arguments.length || arguments[i + 1].isBlank()
                        || arguments[i + 1].startsWith("--")) {
                    System.err.println("SYNESIS_MCP_STARTUP_REJECTED=PROJECT_ARGUMENT_REQUIRED");
                    return 2;
                }
                try {
                    projectRoot = Path.of(arguments[++i])
                            .toAbsolutePath()
                            .normalize();
                    projectRootPinned = true;
                } catch (RuntimeException invalidProject) {
                    System.err.println("SYNESIS_MCP_STARTUP_REJECTED=PROJECT_ARGUMENT_INVALID");
                    return 2;
                }
            } else if ("--connection-instance-id".equals(arg) && i + 1 < arguments.length) {
                connectionInstanceId = arguments[++i].trim();
            }
        }

        long pid = ProcessHandle.current()
                .pid();
        String attachmentProof = boundedEnvironmentOrNull("SYNESIS_ATTACH_PROOF");
        String continuityMode = "SESSION_BOUND";
        if (attachmentProof != null) {
            try {
                authorizeManagedAttachment(projectRoot, provider, connectionInstanceId, attachmentProof);
                continuityMode = "MANAGED_CONTINUITY";
            } catch (Exception rejected) {
                System.err.println("SYNESIS_MCP_STARTUP_REJECTED=MANAGED_ATTACHMENT_REJECTED");
                return 2;
            }
        }
        System.err.println(
                "SYNESIS_MCP_STARTUP pid=" + pid + " version=0.1.0-SNAPSHOT commit=bc334ac conn=" + connectionInstanceId
                        + " provider=" + provider + " continuityMode=" + continuityMode + " cwd=" + Path.of(".")
                        .toAbsolutePath()
                        .normalize());

        AgentSessionService sessionService = new AgentSessionService();
        SessionProcessIdentity processIdentity = captureProcessIdentity(connectionInstanceId);
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, projectRoot, provider,
                connectionInstanceId, processIdentity, projectRootPinned);
        McpStdioServer server = new McpStdioServer(handler);

        return server.run();
    }

    private static RuntimeAuthenticator.AuthenticatedRuntime authorizeManagedAttachment(Path projectRoot,
            String provider, String connectionInstanceId, String attachmentProof) throws Exception {
        if (!"codex".equals(provider)) {
            throw new IllegalArgumentException("managed continuity provider unsupported");
        }
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(projectRoot);
        ProviderSessionBindingService.Binding binding = new ProviderSessionBindingService().find(location, provider,
                connectionInstanceId).orElseThrow(() -> new IllegalStateException("managed binding missing"));
        ManagedAttachmentService service = new ManagedAttachmentService();
        var store = ManagedAttachmentService.storeFor(location, binding.sessionId());
        ManagedAttachmentRecord record = store.read()
                .orElseThrow(() -> new IllegalStateException("managed attachment missing"));
        RuntimeAuthenticator.AttachmentRequest request = new RuntimeAuthenticator.AttachmentRequest(provider,
                binding.sessionId(), record.threadId(), record.generation(), attachmentProof);
        return service.authenticate(location, request, location.projectId().toString(), store);
    }

    private static String boundedEnvironment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() || value.length() > 8_192 ? fallback : value.trim();
    }

    private static String boundedEnvironmentOrNull(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() || value.length() > 8_192 ? null : value.trim();
    }

    private static SessionProcessIdentity captureProcessIdentity(String connectionInstanceId) {
        ProcessHandle.Info info = ProcessHandle.current()
                .info();
        String executable = info.command()
                .orElse("unknown");
        String commandLine = info.commandLine()
                .orElse(executable);
        long start = info.startInstant()
                .map(java.time.Instant::toEpochMilli)
                .orElse(System.currentTimeMillis());
        return new SessionProcessIdentity(ProcessHandle.current()
                .pid(), executable, commandLine, start,
                connectionInstanceId + ":" + UUID.randomUUID());
    }

    /**
     * Entrypoint for standalone process execution.
     *
     * @param arguments launch arguments
     */
    //noinspection RedundantModifier
    static void main(String[] arguments) {
        System.exit(execute(arguments));
    }
}
