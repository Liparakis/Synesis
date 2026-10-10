package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.API_VERSION;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.ordered;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.readObject;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requireJson;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendEmpty;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendError;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;

/**
 * Authenticated, versioned local-control HTTP and SSE adapter.
 *
 * <p>The handler owns only browser-session state and bounded control-plane operation handles.
 * Durable project and coordination state stays in the existing application services and event
 * projections. Requests are accepted only over loopback with an exact local {@code Host} and,
 * when present, an exact loopback {@code Origin}. Wildcard CORS and arbitrary filesystem
 * operations are never enabled.
 *
 * <p>This class is the router and owns the closed flag. Session credentials, pending Link
 * operations, project endpoints, and the event stream live in package-private collaborators that
 * it constructs and holds.
 */
public final class ControlPlaneHttpHandler implements HttpHandler, AutoCloseable {

    /**
     * Current control-plane API prefix.
     */
    public static final String API_PREFIX = "/api/v1";

    /**
     * Session header used instead of cookies.
     */
    public static final String SESSION_HEADER = "X-Synesis-Control-Session";

    /**
     * CSRF header required for mutations.
     */
    public static final String CSRF_HEADER = "X-Synesis-Control-CSRF";

    /**
     * Header used by the owning local runtime for non-browser transport commands.
     */
    public static final String RUNTIME_COMMAND_HEADER = "X-Synesis-Control-Command";

    /**
     * Prefix of all state-changing routes; requests under it require a CSRF token.
     */
    private static final String COMMANDS_PREFIX = API_PREFIX + "/commands/";

    /**
     * Prefix of the pending project-selection read routes.
     */
    private static final String SELECTION_PREFIX = API_PREFIX + "/selection/";

    /**
     * Public-safe read model backing snapshots, diagnostics, and project registry access.
     */
    private final ControlPlaneReadModel readModel;

    /**
     * Durable coordination service; read for head sequence and live event subscriptions.
     */
    private final CoordinationService coordination;

    /**
     * Browser session and runtime-command credentials.
     */
    private final ControlPlaneSessions sessions = new ControlPlaneSessions();

    /**
     * Link onboarding endpoints and their pending-operation registry.
     */
    private final ControlPlaneOnboardingEndpoints onboarding;

    /**
     * Project picker, registry, and daemon-selection endpoints.
     */
    private final ControlPlaneProjectEndpoints projects;

    /**
     * Server-sent-events stream.
     */
    private final ControlPlaneEventStream eventStream;

    /**
     * Set once by {@link #close()}; afterwards every request is rejected.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a control-plane handler with the optional authenticated daemon-selection bridge.
     *
     * @param readModel        explicit public-safe read model
     * @param coordination     durable coordination service
     * @param linkOperations   supported Link operation owner
     * @param eventHub         bounded onboarding event hub
     * @param selectionGateway authenticated local daemon selection bridge
     * @param projectRegistry  installation-local discovery index the project commands mutate, or {@code null} when none is configured
     */
    public ControlPlaneHttpHandler(ControlPlaneReadModel readModel,
            CoordinationService coordination,
            ControlPlaneLinkOperations linkOperations,
            ControlPlaneEventHub eventHub,
            SelectionGateway selectionGateway,
            KnownProjectRegistry projectRegistry) {
        this.readModel = Objects.requireNonNull(readModel, "read model");
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.onboarding = new ControlPlaneOnboardingEndpoints(Objects.requireNonNull(linkOperations, "link operations"));
        this.eventStream = new ControlPlaneEventStream(this.coordination, Objects.requireNonNull(eventHub, "event hub"));
        this.projects = new ControlPlaneProjectEndpoints(this.readModel, projectRegistry, Objects.requireNonNull(selectionGateway, "selection gateway"));
    }

    /**
     * Authenticated bridge to the installation daemon's bounded project-selection state.
     */
    @FunctionalInterface
    public interface SelectionGateway {

        /**
         * Gateway that fails closed when no installation daemon owns this runtime.
         */
        SelectionGateway UNAVAILABLE = new SelectionGateway() {
            @Override
            public Map<String, Object> describe(UUID selectionId) throws IOException {
                throw new IOException("daemon_unavailable");
            }

            @Override
            public Map<String, Object> select(UUID selectionId, UUID projectId) throws IOException {
                throw new IOException("daemon_unavailable");
            }
        };

        /**
         * Reads a safe pending selection projection.
         *
         * @param selectionId pending selection identifier
         * @return daemon result without the original invitation
         * @throws IOException when the local daemon cannot be reached
         */
        Map<String, Object> describe(UUID selectionId) throws IOException;

        /**
         * Claims a project without accepting a raw invitation from the browser.
         *
         * @param selectionId pending selection identifier
         * @param projectId   selected project identifier
         * @return daemon routing result
         * @throws IOException when the local daemon cannot be reached
         */
        default Map<String, Object> select(UUID selectionId, UUID projectId) throws IOException {
            throw new IOException("selection is unsupported");
        }
    }

    /**
     * Returns the one-time terminal bootstrap token for this listener.
     *
     * <p>The caller must deliver this value through the local ready channel; it is never included
     * in a snapshot, event, or diagnostic response.
     *
     * @return high-entropy bootstrap token
     */
    public String bootstrapToken() {
        return sessions.bootstrapToken();
    }

    /**
     * Returns the runtime-owned command token for the local installation shell.
     *
     * <p>This credential is not a browser session and cannot read snapshots or create arbitrary
     * commands. It is accepted only by the narrow deep-link handoff route.
     *
     * @return high-entropy runtime command token
     */
    public String runtimeCommandToken() {
        return sessions.runtimeCommandToken();
    }

    /**
     * Handles one versioned control-plane request.
     *
     * <p>Every failure is translated to a JSON error body; only transport-level
     * {@link IOException}s propagate to the server.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the transport cannot be written
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (closed.get()) {
            sendError(exchange, 503, "SERVER_CLOSED", "control plane is closed");
            return;
        }
        try {
            String origin = ControlPlaneRequestBoundary.validateRequestBoundary(exchange);
            ControlPlaneRequestBoundary.applyCorsHeaders(exchange, origin);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange);
                return;
            }
            onboarding.cleanupExpiredOperations();
            route(exchange, path(exchange.getRequestURI()));
        } catch (ApiFailure failure) {
            sendError(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IOException transportFailure) {
            // Rethrown before the catch-all below so a broken transport is not answered with a 500 body.
            throw transportFailure;
        } catch (Exception unexpected) {
            sendError(exchange, 500, "INTERNAL", "control-plane request failed");
        }
    }

    /**
     * Closes pending Link onboarding handles and prevents new requests.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        onboarding.close();
    }

    /**
     * Dispatches an already boundary-checked request to its route.
     *
     * <p>Unauthenticated routes are matched first; everything else requires a valid session.
     *
     * @param exchange HTTP exchange
     * @param path     normalized request path starting with {@link #API_PREFIX}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the request is rejected
     */
    private void route(HttpExchange exchange, String path) throws IOException, ApiFailure {
        switch (path) {
            case API_PREFIX + "/health" -> {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, health());
                return;
            }
            case API_PREFIX + "/session" -> {
                requireMethod(exchange, "POST");
                sessions.createSession(exchange);
                return;
            }
            case API_PREFIX + "/commands/deep-link" -> {
                requireMethod(exchange, "POST");
                sessions.authenticateRuntimeCommand(exchange);
                requireJson(exchange);
                onboarding.deepLink(exchange, readObject(exchange));
                return;
            }
            default -> {
                // Authenticated routes follow.
            }
        }

        boolean mutation = path.startsWith(COMMANDS_PREFIX);
        sessions.authenticate(exchange, mutation);
        if (path.equals(API_PREFIX + "/events")) {
            requireMethod(exchange, "GET");
            eventStream.events(exchange, closed, this::snapshot);
            return;
        }
        if (mutation) {
            requireMethod(exchange, "POST");
            command(exchange, path.substring(COMMANDS_PREFIX.length()));
            return;
        }
        if (path.startsWith(SELECTION_PREFIX)) {
            requireMethod(exchange, "GET");
            projects.selection(exchange, path.substring(SELECTION_PREFIX.length()));
            return;
        }
        switch (path) {
            case API_PREFIX + "/snapshot" -> {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, snapshot());
                return;
            }
            case API_PREFIX + "/diagnostics" -> {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, readModel.diagnostics());
                return;
            }
            case API_PREFIX + "/projects" -> {
                sendSection(exchange, "projects", readModel::knownProjects);
                return;
            }
            case API_PREFIX + "/agents" -> {
                sendSection(exchange, "agents", readModel::agents);
                return;
            }
            case API_PREFIX + "/workgroups" -> {
                sendSection(exchange, "workgroups", readModel::workgroups);
                return;
            }
            case API_PREFIX + "/claims" -> {
                sendSection(exchange, "claims", readModel::claims);
                return;
            }
            case API_PREFIX + "/capabilities" -> {
                sendSection(exchange, "capabilities", readModel::capabilities);
                return;
            }
            case API_PREFIX + "/network" -> {
                sendSection(exchange, "network", readModel::network);
                return;
            }
            default -> {
                // Falls through to the not-found failure.
            }
        }
        throw failure(404, "NOT_FOUND", "control-plane route not found");
    }

    /**
     * Answers a {@code GET} with one read-model section under the {@code apiVersion} envelope.
     *
     * @param exchange HTTP exchange
     * @param key      body key, also the route's last path segment
     * @param section  builds only that section of the read model
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  405 when the method is not {@code GET}
     */
    private static void sendSection(HttpExchange exchange, String key, Supplier<Object> section) throws IOException, ApiFailure {
        requireMethod(exchange, "GET");
        sendJson(exchange, 200, ordered("apiVersion", API_VERSION, key, section.get()));
    }

    /**
     * Extracts the route path and strips trailing slashes, keeping the bare {@code /api/v1/} root intact.
     *
     * @param uri request URI
     * @return normalized path
     * @throws ApiFailure 404 when the path is outside {@link #API_PREFIX}
     */
    private static String path(URI uri) throws ApiFailure {
        String path = uri.getPath();
        if (path == null || !path.startsWith(API_PREFIX)) {
            throw failure(404, "NOT_FOUND", "control-plane route not found");
        }
        int end = path.length();
        while (end > API_PREFIX.length() + 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    /**
     * Requires the request method to match, advertising the allowed methods otherwise.
     *
     * @param exchange HTTP exchange
     * @param expected the only accepted method for the route
     * @throws ApiFailure 405 when the method differs
     */
    private static void requireMethod(HttpExchange exchange, String expected) throws ApiFailure {
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            return;
        }
        exchange.getResponseHeaders().set("Allow", expected + ", OPTIONS");
        throw failure(405, "METHOD_NOT_ALLOWED", "method is not supported for this route");
    }

    /**
     * Builds the unauthenticated health response.
     *
     * @return health body
     */
    private Map<String, Object> health() {
        Map<String, Object> result = newResponse();
        result.put("status", "OK");
        result.put("loopbackOnly", true);
        result.put("projectId", readModel.projectId().toString());
        result.put("headSequence", coordination.headSequence());
        return result;
    }

    /**
     * Builds the read-model snapshot extended with non-sensitive pending onboarding operations.
     *
     * <p>Invitation and answer links are deliberately omitted; only ids, states, and expiry are exposed.
     *
     * @return snapshot body
     */
    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>(readModel.snapshot());
        result.put("onboarding", onboarding.pending());
        return result;
    }

    /**
     * Dispatches a CSRF-protected command.
     *
     * @param exchange HTTP exchange
     * @param name     command name, the path after {@code /commands/}
     * @throws IOException when the request cannot be read or the response written
     * @throws ApiFailure  when the command is unknown or fails
     */
    private void command(HttpExchange exchange, String name) throws IOException, ApiFailure {
        requireJson(exchange);
        Map<String, Object> body = readObject(exchange);
        switch (name) {
            case "invite" -> onboarding.invite(exchange, body);
            case "join" -> onboarding.join(exchange, body);
            case "answer" -> onboarding.answer(exchange, body);
            case "connect" -> onboarding.connect(exchange, body);
            case "cancel" -> onboarding.cancel(exchange, body);
            case "select-project" -> projects.selectProject(exchange, body);
            case "choose-project" -> projects.chooseProject(exchange);
            case "register-project" -> projects.registerProject(exchange, body);
            case "remove-project" -> projects.removeProject(exchange, body);
            default -> throw failure(404, "NOT_FOUND", "control-plane command not found");
        }
    }
}