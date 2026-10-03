package org.synesis.workspace.transport.control;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.ControlPlaneLinkOperations.PendingHost;
import org.synesis.workspace.transport.control.ControlPlaneLinkOperations.PendingJoin;

/**
 * Authenticated, versioned local-control HTTP and SSE adapter.
 *
 * <p>The handler owns only browser-session state and bounded control-plane operation handles.
 * Durable project and coordination state stays in the existing application services and event
 * projections. Requests are accepted only over loopback with an exact local {@code Host} and,
 * when present, an exact loopback {@code Origin}. Wildcard CORS and arbitrary filesystem
 * operations are never enabled.
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
     * Value of the {@code apiVersion} field in every response body.
     */
    private static final String API_VERSION = "v1";

    /**
     * Prefix of all state-changing routes; requests under it require a CSRF token.
     */
    private static final String COMMANDS_PREFIX = API_PREFIX + "/commands/";

    /**
     * Prefix of the pending project-selection read routes.
     */
    private static final String SELECTION_PREFIX = API_PREFIX + "/selection/";

    /**
     * Upper bound for a request body, enforced on both the declared and the streamed length.
     */
    private static final int MAX_BODY_BYTES = 128 * 1024;

    /**
     * Upper bound for concurrently pending host and join operations combined.
     */
    private static final int MAX_PENDING_OPERATIONS = 16;

    /**
     * Maximum accepted length of a bootstrap token.
     */
    private static final int MAX_TOKEN_LENGTH = 256;

    /**
     * Maximum accepted length of an expected-peer identity.
     */
    private static final int MAX_PEER_LENGTH = 256;

    /**
     * Maximum accepted length of an operation identifier.
     */
    private static final int MAX_OPERATION_ID_LENGTH = 128;

    /**
     * Maximum accepted length of a textual UUID field.
     */
    private static final int MAX_UUID_LENGTH = 64;

    /**
     * Maximum accepted length of an invitation, answer, or deep-link URI.
     */
    private static final int MAX_LINK_LENGTH = 65_536;

    /**
     * Maximum accepted length of a project path.
     */
    private static final int MAX_PATH_LENGTH = 4_096;

    /**
     * How long the one-time bootstrap token stays redeemable after construction.
     */
    private static final Duration BOOTSTRAP_LIFETIME = Duration.ofMinutes(5);

    /**
     * Lifetime of a browser session; sessions are not renewed.
     */
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);

    /**
     * Idle period after which an SSE stream emits a comment frame to keep proxies open.
     */
    private static final Duration SSE_KEEPALIVE = Duration.ofSeconds(15);

    /**
     * Sleep between polls of the two independent SSE event sources.
     */
    private static final long SSE_POLL_INTERVAL_MS = 100;

    /**
     * SSE comment frame sent when a stream has been idle for {@link #SSE_KEEPALIVE}.
     */
    private static final byte[] SSE_KEEPALIVE_FRAME = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Maximum time the native folder picker may keep the user's dialog open.
     */
    private static final Duration PICKER_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Error code shared by every folder-picker failure.
     */
    private static final String PICKER_UNAVAILABLE_CODE = "PROJECT_PICKER_UNAVAILABLE";

    /**
     * Default folder-picker failure message.
     */
    private static final String PICKER_UNAVAILABLE_MESSAGE = "the Windows Explorer folder picker is unavailable";

    /**
     * Source of all tokens issued by this class.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Public-safe read model backing snapshots, diagnostics, and project registry access.
     */
    private final ControlPlaneReadModel readModel;

    /**
     * Durable coordination service; read for head sequence and live event subscriptions.
     */
    private final CoordinationService coordination;

    /**
     * Owner of Link invitation, import, and teardown operations.
     */
    private final ControlPlaneLinkOperations linkOperations;

    /**
     * Bounded hub of onboarding events streamed to the browser.
     */
    private final ControlPlaneEventHub eventHub;

    /**
     * Bridge to the installation daemon's project-selection state.
     */
    private final SelectionGateway selectionGateway;

    /**
     * One-time secret exchanged for a browser session; delivered out of band by the caller.
     */
    private final String bootstrapToken = token();

    /**
     * Secret accepted only by the runtime-owned deep-link route.
     */
    private final String runtimeCommandToken = token();

    /**
     * Instant after which {@link #bootstrapToken} can no longer be redeemed.
     */
    private final Instant bootstrapExpiresAt = Instant.now().plus(BOOTSTRAP_LIFETIME);

    /**
     * Guards {@link #hosts}, {@link #joins}, {@link #operationExpiry}, and {@link #activeOperations}.
     */
    private final Object operationLock = new Object();

    /**
     * Serializes creation and import of Link invitations, which the Link layer does not support concurrently.
     */
    private final Object onboardingLock = new Object();

    /**
     * Pending host operations by operation id. Guarded by {@link #operationLock}.
     */
    private final Map<String, PendingHost> hosts = new HashMap<>();

    /**
     * Pending join operations by operation id. Guarded by {@link #operationLock}.
     */
    private final Map<String, PendingJoin> joins = new HashMap<>();

    /**
     * Expiry of each pending operation by operation id. Guarded by {@link #operationLock}.
     */
    private final Map<String, Instant> operationExpiry = new HashMap<>();

    /**
     * Ids of operations currently executing; these are exempt from expiry and cancellation. Guarded by {@link #operationLock}.
     */
    private final Set<String> activeOperations = new HashSet<>();

    /**
     * Set once by {@link #close()}; afterwards every request is rejected.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The current browser session, or {@code null} until the bootstrap token has been redeemed.
     */
    private volatile Session session;

    /**
     * Whether the bootstrap token has already been redeemed.
     */
    private volatile boolean bootstrapConsumed;

    /**
     * Creates a local control-plane handler.
     *
     * @param readModel    explicit public-safe read model
     * @param coordination durable coordination service
     * @param onboarding   Link-owned onboarding facade
     * @param eventHub     bounded onboarding event hub
     */
    public ControlPlaneHttpHandler(ControlPlaneReadModel readModel, CoordinationService coordination, Onboarding onboarding, ControlPlaneEventHub eventHub) {
        this(readModel, coordination, new OnboardingControlPlaneOperations(onboarding), eventHub);
    }

    /**
     * Creates a local control-plane handler with Link onboarding and daemon selection.
     *
     * @param readModel        explicit public-safe read model
     * @param coordination     durable coordination service
     * @param onboarding       Link-owned onboarding facade
     * @param eventHub         bounded onboarding event hub
     * @param selectionGateway authenticated local daemon selection bridge
     */
    public ControlPlaneHttpHandler(ControlPlaneReadModel readModel, CoordinationService coordination, Onboarding onboarding, ControlPlaneEventHub eventHub, SelectionGateway selectionGateway) {
        this(readModel, coordination, new OnboardingControlPlaneOperations(onboarding), eventHub, selectionGateway);
    }

    /**
     * Creates a local control-plane handler with an explicit Link operation owner.
     *
     * @param readModel      explicit public-safe read model
     * @param coordination   durable coordination service
     * @param linkOperations supported Link operation owner
     * @param eventHub       bounded onboarding event hub
     */
    public ControlPlaneHttpHandler(ControlPlaneReadModel readModel, CoordinationService coordination, ControlPlaneLinkOperations linkOperations, ControlPlaneEventHub eventHub) {
        this(readModel, coordination, linkOperations, eventHub, SelectionGateway.UNAVAILABLE);
    }

    /**
     * Creates a control-plane handler with the optional authenticated daemon-selection bridge.
     *
     * @param readModel        explicit public-safe read model
     * @param coordination     durable coordination service
     * @param linkOperations   supported Link operation owner
     * @param eventHub         bounded onboarding event hub
     * @param selectionGateway authenticated local daemon selection bridge
     */
    public ControlPlaneHttpHandler(ControlPlaneReadModel readModel, CoordinationService coordination, ControlPlaneLinkOperations linkOperations, ControlPlaneEventHub eventHub, SelectionGateway selectionGateway) {
        this.readModel = Objects.requireNonNull(readModel, "read model");
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.linkOperations = Objects.requireNonNull(linkOperations, "link operations");
        this.eventHub = Objects.requireNonNull(eventHub, "event hub");
        this.selectionGateway = Objects.requireNonNull(selectionGateway, "selection gateway");
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
        return bootstrapToken;
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
        return runtimeCommandToken;
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
            String origin = validateRequestBoundary(exchange);
            applyCorsHeaders(exchange, origin);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange);
                return;
            }
            cleanupExpiredOperations();
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
        List<AutoCloseable> pending = new ArrayList<>();
        synchronized (operationLock) {
            pending.addAll(hosts.values());
            pending.addAll(joins.values());
            hosts.clear();
            joins.clear();
            operationExpiry.clear();
            activeOperations.clear();
        }
        pending.forEach(ControlPlaneHttpHandler::closeQuietly);
        linkOperations.close();
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
                createSession(exchange);
                return;
            }
            case API_PREFIX + "/commands/deep-link" -> {
                requireMethod(exchange, "POST");
                authenticateRuntimeCommand(exchange);
                requireJson(exchange);
                deepLink(exchange, readObject(exchange));
                return;
            }
            default -> {
                // Authenticated routes follow.
            }
        }

        boolean mutation = path.startsWith(COMMANDS_PREFIX);
        authenticate(exchange, mutation);
        if (path.equals(API_PREFIX + "/events")) {
            requireMethod(exchange, "GET");
            events(exchange);
            return;
        }
        if (mutation) {
            requireMethod(exchange, "POST");
            command(exchange, path.substring(COMMANDS_PREFIX.length()));
            return;
        }
        if (path.startsWith(SELECTION_PREFIX)) {
            requireMethod(exchange, "GET");
            selection(exchange, path.substring(SELECTION_PREFIX.length()));
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
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "projects", readModel.knownProjects()));
                return;
            }
            case API_PREFIX + "/agents", API_PREFIX + "/workgroups", API_PREFIX + "/claims", API_PREFIX + "/capabilities", API_PREFIX + "/network" -> {
                requireMethod(exchange, "GET");
                String key = path.substring((API_PREFIX + "/").length());
                sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, key, readModel.snapshot().get(key)));
                return;
            }
            default -> {
                // Falls through to the not-found failure.
            }
        }
        throw failure(404, "NOT_FOUND", "control-plane route not found");
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
     * Requires a JSON request body.
     *
     * @param exchange HTTP exchange
     * @throws ApiFailure 415 when the content type is missing or not {@code application/json}
     */
    private static void requireJson(HttpExchange exchange) throws ApiFailure {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw failure(415, "UNSUPPORTED_MEDIA_TYPE", "JSON content type is required");
        }
    }

    /**
     * Rejects requests that did not arrive on the loopback listener with a matching {@code Host}
     * and, when present, a matching {@code Origin}.
     *
     * <p>This is the DNS-rebinding and cross-site defence; it runs before any routing or authentication.
     *
     * @param exchange HTTP exchange
     * @return the validated {@code Origin}, or {@code null} when absent or blank
     * @throws ApiFailure 403 when the boundary check fails
     */
    private static String validateRequestBoundary(HttpExchange exchange) throws ApiFailure {
        InetSocketAddress local = exchange.getLocalAddress();
        if (local == null || local.getAddress() == null || !local.getAddress().isLoopbackAddress()) {
            throw failure(403, "LOOPBACK_REQUIRED", "control plane is loopback-only");
        }
        if (!validAuthority(exchange.getRequestHeaders().getFirst("Host"), local)) {
            throw failure(403, "HOST_NOT_ALLOWED", "request host is not the local listener");
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && origin.isBlank()) {
            origin = null;
        }
        if (origin != null && !validOrigin(origin, local)) {
            throw failure(403, "ORIGIN_NOT_ALLOWED", "request origin is not the local listener");
        }
        return origin;
    }

    /**
     * Checks that a {@code Host} header is a bare local authority on the listener's port.
     *
     * @param raw   raw header value
     * @param local listener address
     * @return {@code true} when the authority addresses this listener
     */
    private static boolean validAuthority(String raw, InetSocketAddress local) {
        if (raw == null || raw.isBlank() || raw.contains(",")) {
            return false;
        }
        try {
            URI authority = URI.create("http://" + raw);
            return hasOnlyAuthority(authority) && authority.getPort() == local.getPort() && allowedHost(authority.getHost(), local);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Checks that an {@code Origin} header is a plain {@code http} origin for this listener.
     *
     * @param raw   raw header value
     * @param local listener address
     * @return {@code true} when the origin addresses this listener
     */
    private static boolean validOrigin(String raw, InetSocketAddress local) {
        try {
            URI origin = URI.create(raw);
            return "http".equalsIgnoreCase(origin.getScheme()) && hasOnlyAuthority(origin) && origin.getPort() == local.getPort() && allowedHost(origin.getHost(), local);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Checks that a URI carries nothing but scheme and authority host/port.
     *
     * @param uri parsed URI
     * @return {@code true} when user info, path, query, and fragment are all absent
     */
    private static boolean hasOnlyAuthority(URI uri) {
        return uri.getUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getQuery() == null && uri.getFragment() == null;
    }

    /**
     * Checks whether a host names this loopback listener.
     *
     * @param raw   host, optionally bracketed for IPv6
     * @param local listener address
     * @return {@code true} for the bound address, the listener's host string, or a loopback literal
     */
    private static boolean allowedHost(String raw, InetSocketAddress local) {
        if (raw == null) {
            return false;
        }
        String host = raw.startsWith("[") && raw.endsWith("]") ? raw.substring(1, raw.length() - 1) : raw;
        return host.equalsIgnoreCase(local.getAddress().getHostAddress()) || host.equalsIgnoreCase(local.getHostString()) || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    /**
     * Applies CORS and content-sniffing headers. The origin is echoed only after validation, never wildcarded.
     *
     * @param exchange HTTP exchange
     * @param origin   validated origin, or {@code null} for same-origin and non-browser callers
     */
    private static void applyCorsHeaders(HttpExchange exchange, String origin) {
        Headers headers = exchange.getResponseHeaders();
        if (origin != null) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", SESSION_HEADER + ", " + CSRF_HEADER + ", Content-Type");
        headers.set("X-Content-Type-Options", "nosniff");
    }

    /**
     * Redeems the one-time bootstrap token for a browser session.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  401 when the token is wrong, expired, or already used
     */
    private void createSession(HttpExchange exchange) throws IOException, ApiFailure {
        requireJson(exchange);
        String supplied = requiredText(readObject(exchange), "bootstrapToken", MAX_TOKEN_LENGTH);
        if (bootstrapConsumed || Instant.now().isAfter(bootstrapExpiresAt) || tokenMismatch(bootstrapToken, supplied)) {
            throw failure(401, "BOOTSTRAP_INVALID", "bootstrap token is invalid or expired");
        }
        String sessionToken = token();
        String csrfToken = token();
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        session = new Session(sessionToken, csrfToken, expiresAt);
        bootstrapConsumed = true;
        Map<String, Object> response = newResponse();
        response.put("sessionToken", sessionToken);
        response.put("csrfToken", csrfToken);
        response.put("expiresAt", expiresAt.toString());
        sendJson(exchange, 201, response);
    }

    /**
     * Requires a live browser session and, for mutations, its CSRF token.
     *
     * @param exchange HTTP exchange
     * @param mutation whether the route changes state
     * @throws ApiFailure 401 without a valid session, 403 without a valid CSRF token
     */
    private void authenticate(HttpExchange exchange, boolean mutation) throws ApiFailure {
        Session current = session;
        if (current == null || Instant.now().isAfter(current.expiresAt()) || tokenMismatch(current.sessionToken(), exchange.getRequestHeaders().getFirst(SESSION_HEADER))) {
            throw failure(401, "AUTH_REQUIRED", "a valid control session is required");
        }
        if (mutation && tokenMismatch(current.csrfToken(), exchange.getRequestHeaders().getFirst(CSRF_HEADER))) {
            throw failure(403, "CSRF_REQUIRED", "a valid CSRF header is required for mutations");
        }
    }

    /**
     * Requires the runtime-owned command token used by the local installation shell.
     *
     * @param exchange HTTP exchange
     * @throws ApiFailure 401 when the token is missing or wrong
     */
    private void authenticateRuntimeCommand(HttpExchange exchange) throws ApiFailure {
        if (tokenMismatch(runtimeCommandToken, exchange.getRequestHeaders().getFirst(RUNTIME_COMMAND_HEADER))) {
            throw failure(401, "RUNTIME_COMMAND_REQUIRED", "a valid runtime command is required");
        }
    }

    /**
     * Generates a 256-bit URL-safe random token.
     *
     * @return unpadded base64url token
     */
    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Compares tokens in constant time with respect to their content.
     *
     * @param expected the secret
     * @param actual   the caller-supplied value, possibly {@code null}
     * @return {@code true} when {@code actual} is absent or differs from {@code expected}
     */
    private static boolean tokenMismatch(String expected, String actual) {
        return actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
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
        List<Map<String, Object>> pendingHosts = new ArrayList<>();
        List<Map<String, Object>> pendingJoins = new ArrayList<>();
        synchronized (operationLock) {
            for (Map.Entry<String, PendingHost> host : hosts.entrySet()) {
                Instant expiresAt = operationExpiry.get(host.getKey());
                if (expiresAt == null) {
                    continue;
                }
                boolean compact = CompactBootstrapInvitation.isShareLink(host.getValue().invitationLink());
                pendingHosts.add(Map.of("operationId", host.getKey(), "state", hostState(compact), "expiresAt", expiresAt.toString()));
            }
            for (String operationId : joins.keySet()) {
                Instant expiresAt = operationExpiry.get(operationId);
                if (expiresAt == null) {
                    continue;
                }
                pendingJoins.add(Map.of("operationId", operationId, "state", "WAITING_FOR_CONNECT", "expiresAt", expiresAt.toString()));
            }
        }
        result.put("onboarding", Map.of("pendingHosts", pendingHosts, "pendingJoins", pendingJoins));
        return result;
    }

    /**
     * Names the waiting state of a host operation.
     *
     * @param compact whether the invitation is a compact bootstrap invitation
     * @return {@code WAITING_FOR_CONNECTION} for compact invitations, otherwise {@code WAITING_FOR_ANSWER}
     */
    private static String hostState(boolean compact) {
        return compact ? "WAITING_FOR_CONNECTION" : "WAITING_FOR_ANSWER";
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
            case "invite" -> invite(exchange, body);
            case "join" -> join(exchange, body);
            case "answer" -> answer(exchange, body);
            case "connect" -> connect(exchange, body);
            case "cancel" -> cancel(exchange, body);
            case "select-project" -> selectProject(exchange, body);
            case "choose-project" -> chooseProject(exchange);
            case "register-project" -> registerProject(exchange, body);
            case "remove-project" -> removeProject(exchange, body);
            default -> throw failure(404, "NOT_FOUND", "control-plane command not found");
        }
    }

    /**
     * Routes a {@code synesis://} deep link handed over by the local installation shell.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code uri}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the link is invalid, unsupported, or matches no pending operation
     */
    private void deepLink(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String link = requiredText(body, "uri", MAX_LINK_LENGTH);
        URI parsed;
        try {
            parsed = URI.create(link);
        } catch (IllegalArgumentException malformed) {
            throw failure(400, "URI_INVALID", "deep-link URI is invalid");
        }
        String action = parsed.getHost();
        if (!"synesis".equals(parsed.getScheme()) || action == null) {
            throw failure(400, "URI_UNSUPPORTED", "deep-link URI is not a supported Synesis link");
        }
        if ("join".equals(action)) {
            join(exchange, Map.of("inviteUri", link));
            return;
        }
        if ("answer".equals(action)) {
            String operationId = matchingHostOperation(parseAnswer(link));
            answer(exchange, Map.of("operationId", operationId, "answerUri", link));
            return;
        }
        throw failure(400, "URI_UNSUPPORTED", "deep-link action is not supported");
    }

    /**
     * Opens the native Windows folder picker and reports the chosen folder.
     *
     * <p>Blocks the calling handler thread until the user closes the dialog or
     * {@link #PICKER_TIMEOUT} elapses.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the picker output cannot be read or the response written
     * @throws ApiFailure  503 when the picker is unavailable, times out, or returns an invalid path
     */
    private void chooseProject(HttpExchange exchange) throws IOException, ApiFailure {
        if (GraphicsEnvironment.isHeadless() || !isWindows()) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
        Path picker = nativeFolderPicker();
        if (picker == null) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
        Process process = runPicker(picker);
        String output = removeTerminalLineBreak(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        int exitCode = process.exitValue();
        if (exitCode == 2 && output.isEmpty()) {
            sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "CANCELLED", "path", "", "name", ""));
            return;
        }
        if (exitCode != 0 || output.isEmpty()) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
        Path selected;
        try {
            selected = Path.of(output).toAbsolutePath().normalize();
        } catch (InvalidPathException malformed) {
            throw pickerUnavailable("the Windows Explorer folder picker returned an invalid folder");
        }
        Path fileName = selected.getFileName();
        sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "SELECTED", "path", selected.toString(), "name", fileName == null ? selected.toString() : fileName.toString()));
    }

    /**
     * Starts the picker and waits for it to exit.
     *
     * @param picker picker executable
     * @return the exited process, with stderr merged into stdout
     * @throws ApiFailure 503 when the picker cannot start, is interrupted, or exceeds {@link #PICKER_TIMEOUT}
     */
    private static Process runPicker(Path picker) throws ApiFailure {
        try {
            Process process = new ProcessBuilder(picker.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(PICKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw pickerUnavailable("the Windows Explorer folder picker timed out");
            }
            return process;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw pickerUnavailable("the Windows Explorer folder picker was interrupted");
        } catch (IOException unavailable) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * Builds the failure shared by every folder-picker error.
     *
     * @param message client-visible detail
     * @return 503 failure
     */
    private static ApiFailure pickerUnavailable(String message) {
        return failure(503, PICKER_UNAVAILABLE_CODE, message);
    }

    /**
     * Checks whether the JVM runs on Windows.
     *
     * @return {@code true} when {@code os.name} contains {@code win}
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Locates the bundled folder-picker executable relative to the running CLI jar.
     *
     * <p>Only the packaged layout {@code <payload>/<dir>/synesis-cli.jar} is honoured, so the
     * route can never be pointed at an arbitrary executable.
     *
     * @return the picker executable, or {@code null} when not running from the packaged layout
     */
    private static Path nativeFolderPicker() {
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            try {
                Path jar = Path.of(entry).toAbsolutePath().normalize();
                Path fileName = jar.getFileName();
                if (fileName == null || !"synesis-cli.jar".equalsIgnoreCase(fileName.toString())) {
                    continue;
                }
                Path jarDirectory = jar.getParent();
                Path payload = jarDirectory == null ? null : jarDirectory.getParent();
                if (payload == null) {
                    continue;
                }
                Path picker = payload.resolve("bin").resolve("synesis-folder-picker.exe");
                if (Files.isRegularFile(picker)) {
                    return picker;
                }
            } catch (InvalidPathException ignored) {
                // Continue searching the remaining classpath entries.
            }
        }
        return null;
    }

    /**
     * Strips a single trailing {@code \n} or {@code \r\n}.
     *
     * @param value process output
     * @return the value without its terminal line break
     */
    private static String removeTerminalLineBreak(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Registers a folder as a known project.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code path}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the path is invalid or the registry rejects it
     */
    private void registerProject(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String rawPath = requiredText(body, "path", MAX_PATH_LENGTH);
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException malformed) {
            throw failure(400, "PROJECT_PATH_INVALID", "project path is invalid");
        }
        try {
            readModel.registerKnownProject(path);
        } catch (KnownProjectRegistry.RegistryException rejected) {
            throw registryFailure(rejected);
        }
        sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "REGISTERED", "projects", readModel.knownProjects()));
    }

    /**
     * Removes a known project from the registry.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code projectId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the id is invalid or the registry rejects the removal
     */
    private void removeProject(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        UUID projectId = uuid(requiredText(body, "projectId", MAX_UUID_LENGTH), "projectId");
        try {
            readModel.removeKnownProject(projectId);
        } catch (KnownProjectRegistry.RegistryException rejected) {
            throw registryFailure(rejected);
        }
        sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "REMOVED", "projectId", projectId.toString(), "projects", readModel.knownProjects()));
    }

    /**
     * Maps a registry rejection to an HTTP failure; unknown codes are treated as 503.
     *
     * @param failure registry exception
     * @return API failure carrying the registry's own code and message
     */
    private static ApiFailure registryFailure(KnownProjectRegistry.RegistryException failure) {
        int status = switch (failure.code()) {
            case "CURRENT_PROJECT_PROTECTED", "PROJECT_NOT_FOUND", "IDENTITY_MISMATCH", "REGISTRY_FULL" -> 409;
            case "NOT_FOUND", "CONFLICT", "MALFORMED", "PROJECT_INVALID", "GIT_INIT_FAILED", "PROJECT_INITIALIZATION_FAILED" -> 400;
            default -> 503;
        };
        return failure(status, failure.code(), failure.getMessage());
    }

    /**
     * Reads a pending project selection from the daemon.
     *
     * @param exchange       HTTP exchange
     * @param rawSelectionId selection id taken from the path
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the id is invalid or the daemon reports failure
     */
    private void selection(HttpExchange exchange, String rawSelectionId) throws IOException, ApiFailure {
        UUID selectionId = uuid(rawSelectionId, "selectionId");
        sendSelectionResult(exchange, daemonSelection(() -> selectionGateway.describe(selectionId)));
    }

    /**
     * Claims a project for a pending selection through the daemon.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code selectionId} and {@code projectId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when an id is invalid or the daemon reports failure
     */
    private void selectProject(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        UUID selectionId = uuid(requiredText(body, "selectionId", MAX_UUID_LENGTH), "selectionId");
        UUID projectId = uuid(requiredText(body, "projectId", MAX_UUID_LENGTH), "projectId");
        sendSelectionResult(exchange, daemonSelection(() -> selectionGateway.select(selectionId, projectId)));
    }

    /**
     * Sends a daemon result, converting an explicit {@code ok: false} into a failure.
     *
     * @param exchange HTTP exchange
     * @param result   daemon result
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the daemon reported failure
     */
    private static void sendSelectionResult(HttpExchange exchange, Map<String, Object> result) throws IOException, ApiFailure {
        if (Boolean.FALSE.equals(result.get("ok"))) {
            throw selectionFailure(result);
        }
        sendJson(exchange, 200, result);
    }

    /**
     * Runs a daemon call, hiding transport detail from the client.
     *
     * @param call daemon call
     * @return daemon result
     * @throws ApiFailure 503 when the daemon cannot be reached
     */
    private static Map<String, Object> daemonSelection(SelectionCall call) throws ApiFailure {
        try {
            return call.run();
        } catch (IOException unavailable) {
            throw failure(503, "SELECTION_UNAVAILABLE", "local project selection is unavailable");
        }
    }

    /**
     * Maps a daemon-reported selection error to an HTTP failure; unknown codes are treated as 400.
     *
     * @param result daemon result whose {@code error} field names the failure
     * @return API failure with a generic message
     */
    private static ApiFailure selectionFailure(Map<String, Object> result) {
        String code = String.valueOf(result.getOrDefault("error", "SELECTION_FAILED"));
        int status = switch (code) {
            case "SELECTION_UNKNOWN" -> 404;
            case "SELECTION_EXPIRED" -> 410;
            case "SELECTION_CONSUMED", "PROJECT_NOT_ELIGIBLE", "PROJECT_UNAVAILABLE" -> 409;
            default -> 400;
        };
        return failure(status, code, "project selection cannot be completed");
    }

    /**
     * A daemon call that may fail with an {@link IOException}.
     */
    @FunctionalInterface
    private interface SelectionCall {

        /**
         * Performs the call.
         *
         * @return daemon result
         * @throws IOException when the daemon cannot be reached
         */
        Map<String, Object> run() throws IOException;
    }

    /**
     * Creates an invitation and registers it as a pending host operation.
     *
     * <p>Compact invitations connect on their own, so completion is monitored; classic ones wait for an answer.
     *
     * @param exchange HTTP exchange
     * @param body     request body with optional {@code expectedPeer}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when capacity is exhausted or the Link layer fails
     */
    private void invite(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String expectedPeer = body.get("expectedPeer") == null ? null : requiredText(body, "expectedPeer", MAX_PEER_LENGTH);
        ensureCapacity();
        PendingHost prepared;
        try {
            synchronized (onboardingLock) {
                prepared = linkOperations.createInvitation(expectedPeer);
            }
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        }
        String link = prepared.invitationLink();
        InvitationDetails parsed = parseInvitationDetails(link);
        String operationId = UUID.randomUUID().toString();
        synchronized (operationLock) {
            hosts.put(operationId, prepared);
            operationExpiry.put(operationId, parsed.expiresAt());
        }
        if (parsed.compact()) {
            monitorCompactHost(operationId, prepared);
        }
        Map<String, Object> response = newResponse();
        response.put("operationId", operationId);
        response.put("inviteUri", link);
        response.put("peerIdentity", parsed.peerIdentity());
        response.put("expiresAt", parsed.expiresAt().toString());
        response.put("state", hostState(parsed.compact()));
        sendJson(exchange, 201, response);
    }

    /**
     * Imports an invitation as a pending join operation.
     *
     * <p>Compact invitations connect immediately; classic ones return an answer link and wait for {@code connect}.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code inviteUri}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the invitation is invalid, capacity is exhausted, or the Link layer fails
     */
    private void join(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String link = requiredText(body, "inviteUri", MAX_LINK_LENGTH);
        InvitationDetails parsed = parseInvitationDetails(link);
        ensureCapacity();
        PendingJoin prepared;
        try {
            synchronized (onboardingLock) {
                prepared = linkOperations.importInvitation(link);
            }
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        }
        String operationId = UUID.randomUUID().toString();
        synchronized (operationLock) {
            joins.put(operationId, prepared);
            operationExpiry.put(operationId, parsed.expiresAt());
        }
        if (parsed.compact()) {
            connectCompactJoin(exchange, operationId, prepared, parsed.peerIdentity());
            return;
        }
        Map<String, Object> response = newResponse();
        response.put("operationId", operationId);
        response.put("answerUri", prepared.answerLink());
        response.put("peerIdentity", parsed.peerIdentity());
        response.put("expiresAt", parsed.expiresAt().toString());
        response.put("state", "WAITING_FOR_CONNECT");
        sendJson(exchange, 201, response);
    }

    /**
     * Connects a freshly imported compact join in the same request and always retires its operation.
     *
     * @param exchange     HTTP exchange
     * @param operationId  id of the registered join
     * @param prepared     the join handle
     * @param peerIdentity host node id to echo to the client
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the Link layer fails
     */
    private void connectCompactJoin(HttpExchange exchange, String operationId, PendingJoin prepared, String peerIdentity) throws IOException, ApiFailure {
        boolean retain = false;
        try {
            prepared.connect();
            retain = prepared.retainsSession();
            Map<String, Object> connected = operationResult(operationId, "CONNECTED");
            connected.put("peerIdentity", peerIdentity);
            sendJson(exchange, 201, connected);
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        } finally {
            finish(joins, operationId, prepared, retain);
        }
    }

    /**
     * Completes a pending host operation with the joiner's answer.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId} and {@code answerUri}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the operation is unknown or busy, or the Link layer fails
     */
    private void answer(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        String answer = requiredText(body, "answerUri", MAX_LINK_LENGTH);
        PendingHost prepared = begin(hosts, operationId, "host");
        boolean retain = false;
        try {
            // The retained host handle owns its bounded wait. Do not serialize it
            // with a join handle: the Link handshake requires both sides to run at
            // the same time when they are hosted by this runtime.
            prepared.importAnswer(answer);
            retain = prepared.retainsSession();
            sendJson(exchange, 200, operationResult(operationId, "CONNECTED"));
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        } finally {
            finish(hosts, operationId, prepared, retain);
        }
    }

    /**
     * Connects a pending join operation.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the operation is unknown or busy, or the Link layer fails
     */
    private void connect(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        PendingJoin prepared = begin(joins, operationId, "join");
        boolean retain = false;
        try {
            // The retained join handle owns its bounded candidate race. It must be
            // able to overlap a host answer operation in the same runtime.
            prepared.connect();
            retain = prepared.retainsSession();
            sendJson(exchange, 200, operationResult(operationId, "CONNECTED"));
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        } finally {
            finish(joins, operationId, prepared, retain);
        }
    }

    /**
     * Cancels an idle pending operation and closes its handle.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  409 when the operation is running, 404 when it does not exist
     */
    private void cancel(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        AutoCloseable pending;
        synchronized (operationLock) {
            if (activeOperations.contains(operationId)) {
                throw failure(409, "OPERATION_BUSY", "onboarding operation is already running");
            }
            pending = hosts.remove(operationId);
            if (pending == null) {
                pending = joins.remove(operationId);
            }
            if (pending == null) {
                throw failure(404, "OPERATION_NOT_FOUND", "onboarding operation is not available");
            }
            operationExpiry.remove(operationId);
        }
        closeQuietly(pending);
        sendJson(exchange, 200, operationResult(operationId, "CANCELLED"));
    }

    /**
     * Finds the single pending host operation whose invitation the given answer belongs to.
     *
     * @param answer parsed answer
     * @return operation id
     * @throws ApiFailure 404 when nothing matches, 409 when more than one operation matches
     */
    private String matchingHostOperation(TraversalAnswer answer) throws ApiFailure {
        List<String> matches = new ArrayList<>();
        synchronized (operationLock) {
            for (Map.Entry<String, PendingHost> entry : hosts.entrySet()) {
                try {
                    TraversalInvitation invitation = TraversalInvitation.fromShareLink(entry.getValue().invitationLink());
                    if (invitation.invitation().sessionId().equals(answer.sessionId())) {
                        matches.add(entry.getKey());
                    }
                } catch (IOException | IllegalArgumentException invalidStoredInvitation) {
                    // A malformed retained handle cannot become a routing oracle.
                }
            }
        }
        if (matches.isEmpty()) {
            throw failure(404, "OPERATION_NOT_FOUND", "answer does not match a pending host operation");
        }
        if (matches.size() != 1) {
            throw failure(409, "OPERATION_AMBIGUOUS", "answer matches multiple host operations");
        }
        return matches.getFirst();
    }

    /**
     * Extracts the display details of either invitation flavour.
     *
     * @param link share link
     * @return peer identity, expiry, and whether the invitation is compact
     * @throws ApiFailure 400 when the link is invalid
     */
    private static InvitationDetails parseInvitationDetails(String link) throws ApiFailure {
        try {
            if (CompactBootstrapInvitation.isShareLink(link)) {
                CompactBootstrapInvitation invitation = CompactBootstrapInvitation.fromShareLink(link);
                return new InvitationDetails(invitation.hostNodeId(), invitation.expiresAt(), true);
            }
            TraversalInvitation invitation = TraversalInvitation.fromShareLink(link);
            return new InvitationDetails(invitation.offer().initiatorNodeId(), invitation.invitation().expiresAt(), false);
        } catch (IOException | IllegalArgumentException invalid) {
            throw failure(400, "INVITE_INVALID", "invitation link is invalid");
        }
    }

    /**
     * Parses a traversal answer.
     *
     * @param link share link
     * @return parsed answer
     * @throws ApiFailure 400 when the link is invalid
     */
    private static TraversalAnswer parseAnswer(String link) throws ApiFailure {
        try {
            return TraversalAnswer.fromShareLink(link);
        } catch (IOException | IllegalArgumentException invalid) {
            throw failure(400, "ANSWER_INVALID", "answer link is invalid");
        }
    }

    /**
     * Maps a Link onboarding failure to a 400 without leaking its detail.
     *
     * @param failure Link failure
     * @return API failure coded {@code ONBOARDING_<code>}
     */
    private static ApiFailure onboardingFailure(OnboardingFailure failure) {
        return failure(400, "ONBOARDING_" + failure.code().name(), "onboarding operation failed");
    }

    /**
     * Builds a bare operation response.
     *
     * @param operationId operation id
     * @param state       operation state
     * @return mutable response body so callers can add fields
     */
    private Map<String, Object> operationResult(String operationId, String state) {
        Map<String, Object> result = newResponse();
        result.put("operationId", operationId);
        result.put("state", state);
        return result;
    }

    /**
     * Marks an operation as running and returns its handle.
     *
     * @param registry    {@link #hosts} or {@link #joins}
     * @param operationId operation id
     * @param kind        {@code host} or {@code join}, used in the client-visible message
     * @param <T>         handle type
     * @return the operation handle
     * @throws ApiFailure 404 when unknown, 409 when already running
     */
    private <T> T begin(Map<String, T> registry, String operationId, String kind) throws ApiFailure {
        synchronized (operationLock) {
            T result = registry.get(operationId);
            if (result == null) {
                throw failure(404, "OPERATION_NOT_FOUND", kind + " operation is not available");
            }
            if (!activeOperations.add(operationId)) {
                throw failure(409, "OPERATION_BUSY", kind + " operation is already running");
            }
            return result;
        }
    }

    /**
     * Retires a finished operation and closes its handle unless the established session must outlive it.
     *
     * @param registry    {@link #hosts} or {@link #joins}
     * @param operationId operation id
     * @param prepared    the handle that ran
     * @param retain      whether the handle now owns a live session and must stay open
     * @param <T>         handle type
     */
    private <T extends AutoCloseable> void finish(Map<String, T> registry, String operationId, T prepared, boolean retain) {
        synchronized (operationLock) {
            registry.remove(operationId, prepared);
            operationExpiry.remove(operationId);
            activeOperations.remove(operationId);
        }
        if (!retain) {
            closeQuietly(prepared);
        }
    }

    /**
     * Retires a compact host operation once its peer connects or the attempt fails.
     *
     * @param operationId operation id
     * @param prepared    the host handle
     */
    private void monitorCompactHost(String operationId, PendingHost prepared) {
        prepared.completion().whenComplete((ignored, failure) -> finish(hosts, operationId, prepared, failure == null && prepared.retainsSession()));
    }

    /**
     * Rejects new operations once the pending limit is reached.
     *
     * @throws ApiFailure 429 when {@link #MAX_PENDING_OPERATIONS} is reached
     */
    private void ensureCapacity() throws ApiFailure {
        synchronized (operationLock) {
            if (hosts.size() + joins.size() >= MAX_PENDING_OPERATIONS) {
                throw failure(429, "OPERATION_LIMIT", "too many pending onboarding operations");
            }
        }
    }

    /**
     * Closes and forgets idle operations past their expiry; running operations are left alone.
     */
    private void cleanupExpiredOperations() {
        Instant now = Instant.now();
        List<AutoCloseable> expired = new ArrayList<>();
        synchronized (operationLock) {
            operationExpiry.entrySet().removeIf(entry -> {
                if (now.isBefore(entry.getValue()) || activeOperations.contains(entry.getKey())) {
                    return false;
                }
                PendingHost host = hosts.remove(entry.getKey());
                PendingJoin join = joins.remove(entry.getKey());
                if (host != null) {
                    expired.add(host);
                }
                if (join != null) {
                    expired.add(join);
                }
                return true;
            });
        }
        // Closed outside the lock: handle teardown may block.
        expired.forEach(ControlPlaneHttpHandler::closeQuietly);
    }

    /**
     * Streams a snapshot followed by coordination and onboarding events until the client leaves,
     * the handler closes, or a subscriber queue overflows.
     *
     * <p>Takes ownership of the exchange and closes it on exit.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the stream cannot be written
     */
    private void events(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-store");
        exchange.sendResponseHeaders(200, 0);
        try (exchange;
                CoordinationService.Subscription coordinationSubscription = coordination.subscribeLive();
                ControlPlaneEventHub.Subscription onboardingSubscription = eventHub.subscribe();
                OutputStream output = exchange.getResponseBody()) {
            writeEvent(output, "snapshot", Long.toString(coordination.headSequence()), snapshotEvent());
            long keepaliveAt = nextKeepalive();
            while (!Thread.currentThread().isInterrupted() && !closed.get()) {
                if (coordinationSubscription.overflowed() || onboardingSubscription.overflowed()) {
                    writeEvent(output, "refresh_required", "refresh", Map.of("apiVersion", API_VERSION, "reason", "subscriber_queue_overflow"));
                    break;
                }
                PredictionEvent event = coordinationSubscription.poll();
                if (event != null) {
                    String eventType = uiEventType(event.type());
                    writeEvent(output, eventType, Long.toString(event.sequence()), Map.of("apiVersion", API_VERSION, "sequence", event.sequence(), "type", eventType));
                    keepaliveAt = nextKeepalive();
                    continue;
                }
                Event onboardingEvent = onboardingSubscription.poll();
                if (onboardingEvent != null) {
                    String eventType = uiLinkEventType(onboardingEvent.type());
                    Map<String, Object> data = newResponse();
                    data.put("type", eventType);
                    if (!onboardingEvent.value().isEmpty()) {
                        data.put("value", onboardingEvent.value());
                    }
                    writeEvent(output, eventType, Long.toString(onboardingEvent.sequence()), data);
                    keepaliveAt = nextKeepalive();
                    continue;
                }
                if (System.nanoTime() >= keepaliveAt) {
                    output.write(SSE_KEEPALIVE_FRAME);
                    output.flush();
                    keepaliveAt = nextKeepalive();
                    continue;
                }
                pauseBetweenPolls();
            }
        }
    }

    /**
     * Builds the first event of every stream.
     *
     * @return snapshot event payload
     */
    private Map<String, Object> snapshotEvent() {
        Map<String, Object> event = newResponse();
        event.put("headSequence", coordination.headSequence());
        event.put("snapshot", snapshot());
        return event;
    }

    /**
     * Computes the next keepalive deadline.
     *
     * @return {@link System#nanoTime()} value {@link #SSE_KEEPALIVE} from now
     */
    private static long nextKeepalive() {
        return System.nanoTime() + SSE_KEEPALIVE.toNanos();
    }

    /**
     * Sleeps between polls so the two independent event sources are served without spinning.
     * Restores the interrupt flag, which ends the stream loop.
     */
    private static void pauseBetweenPolls() {
        try {
            Thread.sleep(SSE_POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes and flushes one SSE frame.
     *
     * @param output stream to write to
     * @param type   SSE event name
     * @param id     SSE event id
     * @param data   JSON-serializable payload
     * @throws IOException when the stream cannot be written
     */
    private static void writeEvent(OutputStream output, String type, String id, Map<String, Object> data) throws IOException {
        String value = ProviderJson.write(data);
        output.write(("event: " + type + "\nid: " + id + "\ndata: " + value + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * Collapses a coordination event type into the coarse event name the UI subscribes to.
     *
     * @param type domain event type
     * @return UI event name; {@code coordination.updated} for anything not specifically grouped
     */
    private static String uiEventType(PredictionEventType type) {
        return switch (type) {
            case WORK_INTENT_ANNOUNCED, WORK_INTENT_RELEASED, COORDINATION_REQUESTED, COORDINATION_RESPONDED, PARTICIPANT_HEARTBEAT, CLAIM_HANDOFF_ACCEPTED, PARTICIPANT_ABANDONED, PARTICIPANT_SUSPENDED,
                 PARTICIPANT_REVOKED, PARTICIPANT_CANCELLED, PARTICIPANT_DETACHED, SESSION_FINALIZED, SESSION_ABANDONED -> "agent.updated";
            case WORK_GROUP_CREATED, WORK_GROUP_STATUS_CHANGED, LANE_GRANT_ISSUED, LANE_GRANT_CONSUMED, LANE_REVOKED, LANE_CONTINUATION_ACCEPTED -> "workgroup.updated";
            case TASK_CLAIMED, TASK_RELEASED, OWNERSHIP_CLAIMED, OWNERSHIP_RELEASED, COMPLETION_PREPARED, COMPLETION_UNWOUND, REPAIR_REQUIRED, REPAIR_LANE_CREATED -> "claim.updated";
            case CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED, CAPABILITY_REQUEST_ACCEPTED, CAPABILITY_REQUEST_REJECTED, CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                 CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED, CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED, CONTRACT_PUBLISHED, CONTRACT_DEPENDENCY_BOUND,
                 CONTRACT_SUPERSEDED, CAPABILITY_AVAILABLE, DEPENDENCY_INVALIDATED -> "capability.updated";
            case TASK_CREATED, TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED, TASK_WAITING_FOR_DEPENDENCIES, TASK_CANCELLATION_REQUESTED, TASK_CANCELLED, INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED,
                 INTEGRATION_CONFLICTED, INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, INTEGRATION_BLOCKED, REVIEW_VALIDATION_RECORDED -> "task.updated";
            default -> "coordination.updated";
        };
    }

    /**
     * Collapses an onboarding event type into the coarse event name the UI subscribes to.
     *
     * @param type {@link OnboardingEventType} name; unknown names are tolerated
     * @return UI event name; {@code link.updated} for anything not specifically grouped
     */
    private static String uiLinkEventType(String type) {
        try {
            return switch (OnboardingEventType.valueOf(type)) {
                case PEER_CONNECTED -> "peer.connected";
                case SESSION_CLOSED -> "peer.disconnected";
                case LIVENESS -> "peer.updated";
                default -> "link.updated";
            };
        } catch (IllegalArgumentException invalid) {
            return "link.updated";
        }
    }

    /**
     * Reads the request body as a JSON object.
     *
     * @param exchange HTTP exchange
     * @return parsed object
     * @throws IOException when the body cannot be read
     * @throws ApiFailure  when the body is too large, not JSON, or not an object
     */
    private static Map<String, Object> readObject(HttpExchange exchange) throws IOException, ApiFailure {
        Object parsed;
        try {
            parsed = ProviderJson.parse(new String(readBody(exchange), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw failure(400, "INVALID_JSON", "request body is not valid JSON");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw failure(400, "INVALID_JSON", "request body must be a JSON object");
        }
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    /**
     * Reads the request body, bounded by {@link #MAX_BODY_BYTES}.
     *
     * <p>A declared {@code Content-Length} is rejected up front, and the stream is bounded
     * independently because the header may be absent (chunked) or wrong.
     *
     * @param exchange HTTP exchange
     * @return body bytes
     * @throws IOException when the body cannot be read
     * @throws ApiFailure  400 for a malformed length, 413 when over the bound
     */
    private static byte[] readBody(HttpExchange exchange) throws IOException, ApiFailure {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            long declared;
            try {
                declared = Long.parseLong(contentLength);
            } catch (NumberFormatException malformed) {
                throw failure(400, "INVALID_CONTENT_LENGTH", "content length is invalid");
            }
            if (declared < 0) {
                throw failure(400, "INVALID_CONTENT_LENGTH", "content length is invalid");
            }
            if (declared > MAX_BODY_BYTES) {
                throw failure(413, "BODY_TOO_LARGE", "request body exceeds its bound");
            }
        }
        try (InputStream input = exchange.getRequestBody()) {
            // One byte past the bound is enough to prove the body is too large without buffering more.
            byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw failure(413, "BODY_TOO_LARGE", "request body exceeds its bound");
            }
            return body;
        }
    }

    /**
     * Reads a required, non-blank, length-bounded string field.
     *
     * @param body JSON object
     * @param key  field name
     * @param max  maximum accepted length
     * @return the field value
     * @throws ApiFailure 400 when the field is missing, not a string, blank, or too long
     */
    private static String requiredText(Map<String, Object> body, String key, int max) throws ApiFailure {
        if (!(body.get(key) instanceof String text) || text.isBlank() || text.length() > max) {
            throw failure(400, "INVALID_REQUEST", key + " is missing or invalid");
        }
        return text;
    }

    /**
     * Parses a UUID field.
     *
     * @param value raw value
     * @param field field name, used for the error code and message
     * @return parsed UUID
     * @throws ApiFailure 400 coded {@code <FIELD>_INVALID} when malformed
     */
    private static UUID uuid(String value, String field) throws ApiFailure {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw failure(400, field.toUpperCase(Locale.ROOT) + "_INVALID", field + " is invalid");
        }
    }

    /**
     * Starts a response body with the {@code apiVersion} field.
     *
     * @return mutable, insertion-ordered body
     */
    private static Map<String, Object> newResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiVersion", API_VERSION);
        return response;
    }

    /**
     * Sends a JSON response and closes the exchange.
     *
     * @param exchange HTTP exchange
     * @param status   HTTP status
     * @param body     JSON-serializable body
     * @throws IOException when the response cannot be written
     */
    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = ProviderJson.write(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * Sends {@code 204 No Content} and closes the exchange.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the response cannot be written
     */
    private static void sendEmpty(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    /**
     * Sends the standard error envelope.
     *
     * @param exchange HTTP exchange
     * @param status   HTTP status
     * @param code     stable machine-readable code
     * @param message  client-visible message
     * @throws IOException when the response cannot be written
     */
    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        sendJson(exchange, status, Map.of("apiVersion", API_VERSION, "error", error));
    }

    /**
     * Creates an {@link ApiFailure}.
     *
     * @param status  HTTP status
     * @param code    stable machine-readable code
     * @param message client-visible message
     * @return the failure, to be thrown by the caller so control flow stays visible at the call site
     */
    private static ApiFailure failure(int status, String code, String message) {
        return new ApiFailure(status, code, message);
    }

    /**
     * Closes a handle, deliberately discarding any failure.
     *
     * @param value handle to close
     */
    private static void closeQuietly(AutoCloseable value) {
        try {
            value.close();
        } catch (Exception ignored) {
            // Best-effort teardown: nothing useful can be done about a failed close.
        }
    }

    /**
     * An issued browser session.
     *
     * @param sessionToken secret sent in {@link #SESSION_HEADER}
     * @param csrfToken    secret sent in {@link #CSRF_HEADER} on mutations
     * @param expiresAt    instant after which the session is rejected
     */
    private record Session(String sessionToken, String csrfToken, Instant expiresAt) {

    }

    /**
     * Display details extracted from an invitation link.
     *
     * @param peerIdentity node id of the inviting peer
     * @param expiresAt    invitation expiry
     * @param compact      whether this is a compact bootstrap invitation
     */
    private record InvitationDetails(String peerIdentity, Instant expiresAt, boolean compact) {

    }

    /**
     * A request failure that maps directly to an HTTP error response.
     */
    private static final class ApiFailure extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * HTTP status code.
         */
        private final int status;

        /**
         * Stable machine-readable error code.
         */
        private final String code;

        /**
         * Creates a failure.
         *
         * @param status  HTTP status
         * @param code    stable machine-readable code
         * @param message client-visible message
         */
        private ApiFailure(int status, String code, String message) {
            // Used for control flow and never logged, so skip the cost of capturing a stack trace.
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }

        /**
         * Returns the HTTP status.
         *
         * @return HTTP status code
         */
        private int status() {
            return status;
        }

        /**
         * Returns the machine-readable code.
         *
         * @return stable error code
         */
        private String code() {
            return code;
        }
    }
}