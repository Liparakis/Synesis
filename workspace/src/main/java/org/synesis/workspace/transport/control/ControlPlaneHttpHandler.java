package org.synesis.workspace.transport.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Authenticated versioned local-control HTTP and SSE adapter.
 *
 * <p>The handler owns only browser-session state and bounded onboarding
 * handles. Durable project and coordination state remains in the existing
 * application services and event projections. It accepts loopback requests
 * with an exact local {@code Host} and, when present, an exact loopback
 * {@code Origin}; it never enables wildcard CORS or arbitrary filesystem
 * operations.
 */
public final class ControlPlaneHttpHandler implements HttpHandler, AutoCloseable {

    /** Current control-plane API prefix. */
    public static final String API_PREFIX = "/api/v1";
    /** Session header used instead of cookies. */
    public static final String SESSION_HEADER = "X-Synesis-Control-Session";
    /** CSRF header required for mutations. */
    public static final String CSRF_HEADER = "X-Synesis-Control-CSRF";

    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int MAX_PENDING_OPERATIONS = 16;
    private static final Duration BOOTSTRAP_LIFETIME = Duration.ofMinutes(5);
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);
    private static final Duration SSE_KEEPALIVE = Duration.ofSeconds(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ControlPlaneReadModel readModel;
    private final CoordinationService coordination;
    private final Onboarding onboarding;
    private final ControlPlaneEventHub eventHub;
    private final String bootstrapToken = token();
    private final Instant bootstrapExpiresAt = Instant.now().plus(BOOTSTRAP_LIFETIME);
    private final Object operationLock = new Object();
    private final Object onboardingLock = new Object();
    private final Map<String, Onboarding.PreparedHost> hosts = new HashMap<>();
    private final Map<String, Onboarding.PreparedJoin> joins = new HashMap<>();
    private final Map<String, Instant> operationExpiry = new HashMap<>();
    private final Set<String> activeOperations = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Session session;
    private volatile boolean bootstrapConsumed;

    /**
     * Creates a local control-plane handler.
     *
     * @param readModel explicit public-safe read model
     * @param coordination durable coordination service
     * @param onboarding Link-owned onboarding facade
     * @param eventHub bounded onboarding event hub
     */
    public ControlPlaneHttpHandler(ControlPlaneReadModel readModel, CoordinationService coordination,
            Onboarding onboarding, ControlPlaneEventHub eventHub) {
        this.readModel = java.util.Objects.requireNonNull(readModel, "read model");
        this.coordination = java.util.Objects.requireNonNull(coordination, "coordination");
        this.onboarding = java.util.Objects.requireNonNull(onboarding, "onboarding");
        this.eventHub = java.util.Objects.requireNonNull(eventHub, "event hub");
    }

    /**
     * Returns the one-time terminal bootstrap token for this listener.
     *
     * <p>The caller must deliver this value through the local ready channel;
     * it is never included in a snapshot, event, or diagnostic response.
     *
     * @return high-entropy bootstrap token
     */
    public String bootstrapToken() {
        return bootstrapToken;
    }

    /**
     * Handles one versioned control-plane request.
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
                sendEmpty(exchange, 204);
                return;
            }
            cleanupExpiredOperations();
            String path = path(exchange.getRequestURI());
            if (path.equals(API_PREFIX + "/health")) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, health());
                return;
            }
            if (path.equals(API_PREFIX + "/session")) {
                requireMethod(exchange, "POST");
                createSession(exchange);
                return;
            }
            boolean mutation = path.startsWith(API_PREFIX + "/commands/");
            authenticate(exchange, mutation);
            if (path.equals(API_PREFIX + "/events")) {
                requireMethod(exchange, "GET");
                events(exchange);
                return;
            }
            if (path.startsWith(API_PREFIX + "/commands/")) {
                requireMethod(exchange, "POST");
                command(exchange, path.substring((API_PREFIX + "/commands/").length()));
                return;
            }
            if (path.equals(API_PREFIX + "/snapshot")) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, readModel.snapshot());
                return;
            }
            if (path.equals(API_PREFIX + "/diagnostics")) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, readModel.diagnostics());
                return;
            }
            if (path.equals(API_PREFIX + "/projects")) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of("apiVersion", "v1", "projects", List.of(
                        readModel.snapshot().get("project"))));
                return;
            }
            if (path.equals(API_PREFIX + "/agents") || path.equals(API_PREFIX + "/workgroups")
                    || path.equals(API_PREFIX + "/claims") || path.equals(API_PREFIX + "/capabilities")
                    || path.equals(API_PREFIX + "/network")) {
                requireMethod(exchange, "GET");
                String key = path.substring((API_PREFIX + "/").length());
                sendJson(exchange, 200, Map.of("apiVersion", "v1", key, readModel.snapshot().get(key)));
                return;
            }
            throw failure(404, "NOT_FOUND", "control-plane route not found");
        } catch (ApiFailure failure) {
            sendError(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IOException transportFailure) {
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
        if (closed.compareAndSet(false, true)) {
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
        }
    }

    private static String path(URI uri) throws ApiFailure {
        String path = uri.getPath();
        if (path == null || !path.startsWith(API_PREFIX)) {
            throw failure(404, "NOT_FOUND", "control-plane route not found");
        }
        while (path.length() > API_PREFIX.length() + 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static void requireMethod(HttpExchange exchange, String expected) throws ApiFailure {
        if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", expected + ", OPTIONS");
            throw failure(405, "METHOD_NOT_ALLOWED", "method is not supported for this route");
        }
    }

    private static String validateRequestBoundary(HttpExchange exchange) throws ApiFailure {
        InetSocketAddress local = exchange.getLocalAddress();
        if (local == null || local.getAddress() == null || !local.getAddress().isLoopbackAddress()) {
            throw failure(403, "LOOPBACK_REQUIRED", "control plane is loopback-only");
        }
        String hostHeader = exchange.getRequestHeaders().getFirst("Host");
        if (!validAuthority(hostHeader, local)) {
            throw failure(403, "HOST_NOT_ALLOWED", "request host is not the local listener");
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && !validOrigin(origin, local)) {
            throw failure(403, "ORIGIN_NOT_ALLOWED", "request origin is not the local listener");
        }
        return origin == null || origin.isBlank() ? null : origin;
    }

    private static boolean validAuthority(String raw, InetSocketAddress local) {
        try {
            if (raw == null || raw.isBlank() || raw.contains(",")) {
                return false;
            }
            URI authority = URI.create("http://" + raw);
            if (authority.getUserInfo() != null || authority.getPath() != null
                    && !authority.getPath().isEmpty() || authority.getQuery() != null
                    || authority.getFragment() != null || authority.getPort() != local.getPort()) {
                return false;
            }
            return allowedHost(authority.getHost(), local);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean validOrigin(String raw, InetSocketAddress local) {
        try {
            URI origin = URI.create(raw);
            return "http".equalsIgnoreCase(origin.getScheme()) && origin.getUserInfo() == null
                    && (origin.getPath() == null || origin.getPath().isEmpty()) && origin.getQuery() == null
                    && origin.getFragment() == null && origin.getPort() == local.getPort()
                    && allowedHost(origin.getHost(), local);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean allowedHost(String raw, InetSocketAddress local) {
        if (raw == null) {
            return false;
        }
        String host = raw.startsWith("[") && raw.endsWith("]") ? raw.substring(1, raw.length() - 1) : raw;
        String bound = local.getAddress().getHostAddress();
        return host.equalsIgnoreCase(bound) || host.equalsIgnoreCase(local.getHostString())
                || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static void applyCorsHeaders(HttpExchange exchange, String origin) {
        if (origin != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                SESSION_HEADER + ", " + CSRF_HEADER + ", Content-Type");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    private void createSession(HttpExchange exchange) throws IOException, ApiFailure {
        requireJson(exchange);
        Map<String, Object> body = readObject(exchange);
        String supplied = text(body, "bootstrapToken", true, 256);
        if (bootstrapConsumed || Instant.now().isAfter(bootstrapExpiresAt)
                || !MessageDigest.isEqual(bootstrapToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            throw failure(401, "BOOTSTRAP_INVALID", "bootstrap token is invalid or expired");
        }
        String sessionToken = token();
        String csrfToken = token();
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        session = new Session(sessionToken, csrfToken, expiresAt);
        bootstrapConsumed = true;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiVersion", "v1");
        response.put("sessionToken", sessionToken);
        response.put("csrfToken", csrfToken);
        response.put("expiresAt", expiresAt.toString());
        sendJson(exchange, 201, response);
    }

    private void authenticate(HttpExchange exchange, boolean mutation) throws ApiFailure {
        Session current = session;
        if (current == null || Instant.now().isAfter(current.expiresAt())
                || !constantEquals(current.sessionToken(), exchange.getRequestHeaders().getFirst(SESSION_HEADER))) {
            throw failure(401, "AUTH_REQUIRED", "a valid control session is required");
        }
        if (mutation && !constantEquals(current.csrfToken(), exchange.getRequestHeaders().getFirst(CSRF_HEADER))) {
            throw failure(403, "CSRF_REQUIRED", "a valid CSRF header is required for mutations");
        }
    }

    private Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", "v1");
        result.put("status", "OK");
        result.put("loopbackOnly", true);
        result.put("projectId", readModel.projectId().toString());
        result.put("headSequence", coordination.headSequence());
        return result;
    }

    private void command(HttpExchange exchange, String name) throws IOException, ApiFailure {
        requireJson(exchange);
        Map<String, Object> body = readObject(exchange);
        switch (name) {
            case "invite" -> invite(exchange, body);
            case "join" -> join(exchange, body);
            case "answer" -> answer(exchange, body);
            case "connect" -> connect(exchange, body);
            case "cancel" -> cancel(exchange, body);
            default -> throw failure(404, "NOT_FOUND", "control-plane command not found");
        }
    }

    private void invite(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String expectedPeer = optionalText(body, "expectedPeer", 256);
        ensureCapacity();
        Onboarding.PreparedHost prepared;
        try {
            synchronized (onboardingLock) {
                prepared = onboarding.createInvitation(expectedPeer);
            }
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        }
        String link = prepared.invitationLink();
        TraversalInvitation parsed = parseInvitation(link);
        String operationId = UUID.randomUUID().toString();
        synchronized (operationLock) {
            hosts.put(operationId, prepared);
            operationExpiry.put(operationId, parsed.invitation().expiresAt());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiVersion", "v1");
        response.put("operationId", operationId);
        response.put("inviteUri", link);
        response.put("peerIdentity", parsed.offer().initiatorNodeId());
        response.put("expiresAt", parsed.invitation().expiresAt().toString());
        response.put("state", "WAITING_FOR_ANSWER");
        sendJson(exchange, 201, response);
    }

    private void join(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String link = text(body, "inviteUri", true, 65_536);
        TraversalInvitation parsed = parseInvitation(link);
        ensureCapacity();
        Onboarding.PreparedJoin prepared;
        try {
            synchronized (onboardingLock) {
                prepared = onboarding.importInvitation(link);
            }
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        }
        String operationId = UUID.randomUUID().toString();
        synchronized (operationLock) {
            joins.put(operationId, prepared);
            operationExpiry.put(operationId, parsed.invitation().expiresAt());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiVersion", "v1");
        response.put("operationId", operationId);
        response.put("answerUri", prepared.answerLink());
        response.put("peerIdentity", parsed.offer().initiatorNodeId());
        response.put("expiresAt", parsed.invitation().expiresAt().toString());
        response.put("state", "WAITING_FOR_CONNECT");
        sendJson(exchange, 201, response);
    }

    private void answer(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = text(body, "operationId", true, 128);
        String answer = text(body, "answerUri", true, 65_536);
        Onboarding.PreparedHost prepared = beginHost(operationId);
        try {
            synchronized (onboardingLock) {
                prepared.importAnswer(answer);
            }
            sendJson(exchange, 200, operationResult(operationId, "CONNECTED"));
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        } finally {
            finishHost(operationId, prepared);
        }
    }

    private void connect(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = text(body, "operationId", true, 128);
        Onboarding.PreparedJoin prepared = beginJoin(operationId);
        try {
            synchronized (onboardingLock) {
                prepared.connect();
            }
            sendJson(exchange, 200, operationResult(operationId, "CONNECTED"));
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        } finally {
            finishJoin(operationId, prepared);
        }
    }

    private void cancel(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = text(body, "operationId", true, 128);
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

    private Map<String, Object> operationResult(String operationId, String state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", "v1");
        result.put("operationId", operationId);
        result.put("state", state);
        return result;
    }

    private Onboarding.PreparedHost beginHost(String operationId) throws ApiFailure {
        synchronized (operationLock) {
            Onboarding.PreparedHost result = hosts.get(operationId);
            if (result == null) {
                throw failure(404, "OPERATION_NOT_FOUND", "host operation is not available");
            }
            if (!activeOperations.add(operationId)) {
                throw failure(409, "OPERATION_BUSY", "host operation is already running");
            }
            return result;
        }
    }

    private Onboarding.PreparedJoin beginJoin(String operationId) throws ApiFailure {
        synchronized (operationLock) {
            Onboarding.PreparedJoin result = joins.get(operationId);
            if (result == null) {
                throw failure(404, "OPERATION_NOT_FOUND", "join operation is not available");
            }
            if (!activeOperations.add(operationId)) {
                throw failure(409, "OPERATION_BUSY", "join operation is already running");
            }
            return result;
        }
    }

    private void finishHost(String operationId, Onboarding.PreparedHost prepared) {
        synchronized (operationLock) {
            hosts.remove(operationId, prepared);
            operationExpiry.remove(operationId);
            activeOperations.remove(operationId);
        }
        closeQuietly(prepared);
    }

    private void finishJoin(String operationId, Onboarding.PreparedJoin prepared) {
        synchronized (operationLock) {
            joins.remove(operationId, prepared);
            operationExpiry.remove(operationId);
            activeOperations.remove(operationId);
        }
        closeQuietly(prepared);
    }

    private void ensureCapacity() throws ApiFailure {
        synchronized (operationLock) {
            if (hosts.size() + joins.size() >= MAX_PENDING_OPERATIONS) {
                throw failure(429, "OPERATION_LIMIT", "too many pending onboarding operations");
            }
        }
    }

    private void cleanupExpiredOperations() {
        Instant now = Instant.now();
        List<AutoCloseable> expired = new ArrayList<>();
        synchronized (operationLock) {
            operationExpiry.entrySet().removeIf(entry -> {
                if (!now.isBefore(entry.getValue()) && !activeOperations.contains(entry.getKey())) {
                    Onboarding.PreparedHost host = hosts.remove(entry.getKey());
                    Onboarding.PreparedJoin join = joins.remove(entry.getKey());
                    if (host != null) {
                        expired.add(host);
                    }
                    if (join != null) {
                        expired.add(join);
                    }
                    return true;
                }
                return false;
            });
        }
        expired.forEach(ControlPlaneHttpHandler::closeQuietly);
    }

    private void events(HttpExchange exchange) throws IOException, ApiFailure {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        exchange.sendResponseHeaders(200, 0);
        try (exchange;
                CoordinationService.Subscription coordinationSubscription = coordination.subscribeLive();
                ControlPlaneEventHub.Subscription onboardingSubscription = eventHub.subscribe();
                var output = exchange.getResponseBody()) {
            writeEvent(output, "snapshot", Long.toString(coordination.headSequence()), snapshotEvent());
            long keepaliveAt = System.nanoTime() + SSE_KEEPALIVE.toNanos();
            while (!Thread.currentThread().isInterrupted() && !closed.get()) {
                if (coordinationSubscription.overflowed() || onboardingSubscription.overflowed()) {
                    writeEvent(output, "refresh_required", "refresh", Map.of(
                            "apiVersion", "v1", "reason", "subscriber_queue_overflow"));
                    break;
                }
                PredictionEvent event = coordinationSubscription.poll();
                if (event != null) {
                    String eventType = uiEventType(event.type());
                    writeEvent(output, eventType, Long.toString(event.sequence()), Map.of(
                            "apiVersion", "v1", "sequence", event.sequence(), "type", eventType));
                    keepaliveAt = System.nanoTime() + SSE_KEEPALIVE.toNanos();
                    continue;
                }
                ControlPlaneEventHub.Event onboardingEvent = onboardingSubscription.poll();
                if (onboardingEvent != null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("apiVersion", "v1");
                    data.put("type", onboardingEvent.type());
                    if (!onboardingEvent.value().isEmpty()) {
                        data.put("value", onboardingEvent.value());
                    }
                    writeEvent(output, "link.updated", Long.toString(onboardingEvent.sequence()), data);
                    keepaliveAt = System.nanoTime() + SSE_KEEPALIVE.toNanos();
                    continue;
                }
                if (System.nanoTime() >= keepaliveAt) {
                    output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    keepaliveAt = System.nanoTime() + SSE_KEEPALIVE.toNanos();
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private Map<String, Object> snapshotEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("apiVersion", "v1");
        event.put("headSequence", coordination.headSequence());
        event.put("snapshot", readModel.snapshot());
        return event;
    }

    private static String uiEventType(PredictionEventType type) {
        return switch (type) {
            case WORK_INTENT_ANNOUNCED, WORK_INTENT_RELEASED, COORDINATION_REQUESTED,
                    COORDINATION_RESPONDED, PARTICIPANT_HEARTBEAT, CLAIM_HANDOFF_ACCEPTED,
                    PARTICIPANT_ABANDONED, PARTICIPANT_SUSPENDED, PARTICIPANT_REVOKED,
                    PARTICIPANT_CANCELLED, PARTICIPANT_DETACHED, SESSION_FINALIZED,
                    SESSION_ABANDONED -> "agent.updated";
            case WORK_GROUP_CREATED, WORK_GROUP_STATUS_CHANGED, LANE_GRANT_ISSUED,
                    LANE_GRANT_CONSUMED, LANE_REVOKED, LANE_CONTINUATION_ACCEPTED -> "workgroup.updated";
            case TASK_CLAIMED, TASK_RELEASED, OWNERSHIP_CLAIMED, OWNERSHIP_RELEASED,
                    COMPLETION_PREPARED, COMPLETION_UNWOUND, REPAIR_REQUIRED, REPAIR_LANE_CREATED -> "claim.updated";
            case CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED,
                    CAPABILITY_REQUEST_ACCEPTED, CAPABILITY_REQUEST_REJECTED,
                    CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                    CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED,
                    CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                    CONTRACT_PUBLISHED, CONTRACT_DEPENDENCY_BOUND, CONTRACT_SUPERSEDED,
                    CAPABILITY_AVAILABLE, DEPENDENCY_INVALIDATED -> "capability.updated";
            case TASK_CREATED, TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED,
                    TASK_WAITING_FOR_DEPENDENCIES, TASK_CANCELLATION_REQUESTED, TASK_CANCELLED,
                    INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED, INTEGRATION_CONFLICTED,
                    INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED,
                    INTEGRATION_BLOCKED, REVIEW_VALIDATION_RECORDED -> "task.updated";
            default -> "coordination.updated";
        };
    }

    private static void writeEvent(java.io.OutputStream output, String type, String id,
            Map<String, Object> data) throws IOException {
        String value = ProviderJson.write(data);
        output.write(("event: " + type + "\nid: " + id + "\ndata: " + value + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static Map<String, Object> readObject(HttpExchange exchange) throws IOException, ApiFailure {
        try {
            Object parsed = ProviderJson.parse(new String(readBody(exchange), StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return result;
            }
            throw failure(400, "INVALID_JSON", "request body must be a JSON object");
        } catch (IllegalArgumentException malformed) {
            throw failure(400, "INVALID_JSON", "request body is not valid JSON");
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException, ApiFailure {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long declared = Long.parseLong(contentLength);
                if (declared < 0) {
                    throw failure(400, "INVALID_CONTENT_LENGTH", "content length is invalid");
                }
                if (declared > MAX_BODY_BYTES) {
                    throw failure(413, "BODY_TOO_LARGE", "request body exceeds its bound");
                }
            } catch (NumberFormatException malformed) {
                throw failure(400, "INVALID_CONTENT_LENGTH", "content length is invalid");
            }
        }
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BODY_BYTES) {
                    throw failure(413, "BODY_TOO_LARGE", "request body exceeds its bound");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void requireJson(HttpExchange exchange) throws ApiFailure {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw failure(415, "UNSUPPORTED_MEDIA_TYPE", "JSON content type is required");
        }
    }

    private static String text(Map<String, Object> body, String key, boolean required, int max) throws ApiFailure {
        Object value = body.get(key);
        if (value == null && !required) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) {
            throw failure(400, "INVALID_REQUEST", key + " is missing or invalid");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> body, String key, int max) throws ApiFailure {
        if (!body.containsKey(key) || body.get(key) == null) {
            return null;
        }
        return text(body, key, true, max);
    }

    private static TraversalInvitation parseInvitation(String link) throws ApiFailure {
        try {
            return TraversalInvitation.fromShareLink(link);
        } catch (IOException | IllegalArgumentException invalid) {
            throw failure(400, "INVITE_INVALID", "invitation link is invalid");
        }
    }

    private static ApiFailure onboardingFailure(OnboardingFailure failure) {
        return failure(400, "ONBOARDING_" + failure.code().name(), "onboarding operation failed");
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = ProviderJson.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        sendJson(exchange, status, Map.of("apiVersion", "v1", "error", error));
    }

    private static ApiFailure failure(int status, String code, String message) {
        return new ApiFailure(status, code, message);
    }

    private static void closeQuietly(AutoCloseable value) {
        try {
            value.close();
        } catch (Exception ignored) {
        }
    }

    private record Session(String sessionToken, String csrfToken, Instant expiresAt) {
    }

    private static final class ApiFailure extends Exception {

        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;

        private ApiFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        private int status() {
            return status;
        }

        private String code() {
            return code;
        }
    }
}
