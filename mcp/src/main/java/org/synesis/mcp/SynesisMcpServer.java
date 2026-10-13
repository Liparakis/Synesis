package org.synesis.mcp;

import java.nio.file.Path;
import java.util.UUID;
import org.synesis.mcp.application.McpProtocolHandler;
import org.synesis.mcp.transport.stdio.McpStdioServer;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.RuntimeAuthenticator;

/**
 * Main process entrypoint for the Synesis Model Context Protocol (MCP) server.
 *
 * <p>Parses launch arguments ({@code --provider}, {@code --connection-instance-id}), initializes
 * ambient session resolution, and starts the stdio event loop. Environment variables provide the
 * project context for managed launches; normal Codex sessions resolve it from their MCP context.
 *
 * @since 1.0
 */
public final class SynesisMcpServer {

    /**
     * Provider assumed when neither the environment nor the arguments name one.
     */
    private static final String DEFAULT_PROVIDER = "codex";

    /**
     * The only provider for which managed continuity is supported.
     */
    private static final String MANAGED_CONTINUITY_PROVIDER = "codex";

    /**
     * Exit code for a launch the server refuses to start.
     */
    private static final int EXIT_REJECTED = 2;

    /**
     * Longest environment value accepted; longer values are treated as absent.
     */
    private static final int MAX_ENVIRONMENT_LENGTH = 8_192;

    /**
     * Version reported in the startup banner.
     */
    private static final String VERSION = "0.1.0-SNAPSHOT";

    /**
     * Build commit reported in the startup banner.
     */
    private static final String COMMIT = "bc334ac";

    /**
     * Utility class; the process is started through {@link #execute(String[])}.
     */
    private SynesisMcpServer() {
    }

    /**
     * Executes the MCP server process.
     *
     * <p>Refusals are reported on stderr as {@code SYNESIS_MCP_STARTUP_REJECTED=<code>} so stdout
     * stays reserved for protocol frames.
     *
     * @param arguments command-line launch arguments
     * @return process exit code: the stdio loop's result, or {@value #EXIT_REJECTED} when startup is refused
     */
    public static int execute(String[] arguments) {
        String provider = boundedEnvironment("SYNESIS_MCP_PROVIDER", DEFAULT_PROVIDER);
        String configuredProject = boundedEnvironmentOrNull("SYNESIS_MCP_PROJECT");
        boolean projectRootPinned = configuredProject != null;
        Path projectRoot = Path.of(configuredProject == null ? "." : configuredProject).toAbsolutePath().normalize();
        String connectionInstanceId = boundedEnvironment("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-instance-" + UUID.randomUUID());

        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            if ("--provider".equals(arg) && i + 1 < arguments.length) {
                provider = arguments[++i].trim();
            } else if ("--project".equals(arg)) {
                return reject("PROJECT_ARGUMENT_REMOVED");
            } else if ("--connection-instance-id".equals(arg) && i + 1 < arguments.length) {
                connectionInstanceId = arguments[++i].trim();
            }
        }

        long pid = ProcessHandle.current().pid();
        String attachmentProof = boundedEnvironmentOrNull("SYNESIS_ATTACH_PROOF");
        String continuityMode = "SESSION_BOUND";
        ManagedAttachmentRecord managedAttachment = null;
        ManagedAttachmentService.TransportLease transportLease = null;
        if (attachmentProof != null) {
            try {
                managedAttachment = authorizeManagedAttachment(projectRoot, provider, connectionInstanceId, attachmentProof);
                ProjectLocation location = new ProjectApplicationService().locate(projectRoot);
                transportLease = new ManagedAttachmentService().acquireTransport(location, managedAttachment.bindingSessionId(), managedAttachment.generation());
                continuityMode = managedAttachment.status() == ManagedAttachmentRecord.Status.PENDING_ACTIVATION ? "MANAGED_CONTINUITY_QUARANTINED" : "MANAGED_CONTINUITY";
            } catch (Exception rejected) {
                return reject("MANAGED_ATTACHMENT_REJECTED");
            }
        }
        System.err.println(
                "SYNESIS_MCP_STARTUP pid=" + pid + " version=" + VERSION + " commit=" + COMMIT + " conn=" + connectionInstanceId + " provider=" + provider + " continuityMode=" + continuityMode + " cwd=" + Path.of(".")
                        .toAbsolutePath().normalize());

        AgentSessionService sessionService = new AgentSessionService();
        McpProtocolHandler handler = new McpProtocolHandler(sessionService, projectRoot, provider, connectionInstanceId, projectRootPinned, managedAttachment);
        handler.holdWaitsFor(waitHold());
        McpStdioServer server = new McpStdioServer(handler);

        try {
            return server.run();
        } finally {
            closeTransportLease(transportLease);
        }
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

    /**
     * Authenticates the proof-bearing transport against the exact managed binding and attachment.
     *
     * @param projectRoot          control project root
     * @param provider             provider identifier
     * @param connectionInstanceId exact connection selector
     * @param attachmentProof      process-private attachment proof
     * @return the authenticated attachment record
     * @throws Exception when the provider is unsupported, the binding or attachment is missing or inactive, or the proof is rejected
     */
    private static ManagedAttachmentRecord authorizeManagedAttachment(Path projectRoot, String provider, String connectionInstanceId, String attachmentProof) throws Exception {
        if (!MANAGED_CONTINUITY_PROVIDER.equals(provider)) {
            throw new IllegalArgumentException("managed continuity provider unsupported");
        }
        ProjectLocation location = new ProjectApplicationService().locate(projectRoot);
        ProviderSessionBindingService.Binding binding = new ProviderSessionBindingService().find(location, provider, connectionInstanceId).orElseThrow(() -> new IllegalStateException("managed binding missing"));
        ManagedAttachmentService service = new ManagedAttachmentService();
        var store = ManagedAttachmentService.storeFor(location, binding.sessionId());
        if (!"BOUND".equalsIgnoreCase(binding.status())) {
            throw new IllegalStateException("managed binding is not active");
        }
        ManagedAttachmentRecord record = store.read().orElseThrow(() -> new IllegalStateException("managed attachment missing"));
        RuntimeAuthenticator.AttachmentRequest request = new RuntimeAuthenticator.AttachmentRequest(provider, binding.sessionId(), record.threadId(), record.generation(), attachmentProof);
        return service.authenticateTransport(location, request, location.projectId().toString(), store);
    }

    /**
     * Reports a refused launch on stderr.
     *
     * @param code stable rejection code
     * @return {@value #EXIT_REJECTED}, for the caller to return as the exit code
     */
    private static int reject(String code) {
        System.err.println("SYNESIS_MCP_STARTUP_REJECTED=" + code);
        return EXIT_REJECTED;
    }

    /**
     * Releases the managed transport lease, if any, discarding close failures.
     *
     * @param transportLease lease to release, or {@code null}
     */
    private static void closeTransportLease(ManagedAttachmentService.TransportLease transportLease) {
        if (transportLease == null) {
            return;
        }
        try {
            transportLease.close();
        } catch (Exception ignored) {
            // The MCP result is already determined by the stdio loop.
        }
    }

    /**
     * Reads an environment variable, falling back when it is unset, blank, or over-long.
     *
     * @param name     variable name
     * @param fallback value returned when the variable is unusable
     * @return the trimmed value, or {@code fallback} as given
     */
    private static String boundedEnvironment(String name, String fallback) {
        String value = boundedEnvironmentOrNull(name);
        return value == null ? fallback : value;
    }

    /**
     * Reads an environment variable, treating unset, blank, or over-long values as absent.
     *
     * @param name variable name
     * @return the trimmed value, or {@code null} when unusable
     */
    private static String boundedEnvironmentOrNull(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() || value.length() > MAX_ENVIRONMENT_LENGTH ? null : value.trim();
    }

    /**
     * Returns how long one waiting call is held: twenty seconds unless
     * {@code SYNESIS_WAIT_HOLD_SECONDS} says otherwise. Zero turns the hold off. Anything outside
     * zero to thirty is ignored: a provider gives a tool call about a minute, and the answer
     * after the hold needs part of it.
     *
     * @return the hold
     */
    private static java.time.Duration waitHold() {
        return waitHold(System.getenv("SYNESIS_WAIT_HOLD_SECONDS"));
    }

    static java.time.Duration waitHold(String configured) {
        try {
            long seconds = configured == null || configured.isBlank() ? 20L : Long.parseLong(configured.trim());
            return java.time.Duration.ofSeconds(seconds < 0 || seconds > 30 ? 20L : seconds);
        } catch (NumberFormatException notANumber) {
            return java.time.Duration.ofSeconds(20L);
        }
    }
}
