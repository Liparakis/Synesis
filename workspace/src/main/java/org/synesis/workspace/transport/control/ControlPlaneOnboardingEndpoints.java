package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.ordered;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requiredText;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.workspace.transport.control.ControlPlaneLinkOperations.PendingHost;
import org.synesis.workspace.transport.control.ControlPlaneLinkOperations.PendingJoin;

/**
 * Link onboarding endpoints and the bounded registry of pending host and join operations they create.
 *
 * <p>Owns the operation maps, their expiry, and the locks that guard them, because every endpoint
 * here, the snapshot's pending-operation view, expiry cleanup, and shutdown must observe one
 * consistent registry. The handler holds one instance and never touches the maps directly.
 */
final class ControlPlaneOnboardingEndpoints {

    /**
     * Upper bound for concurrently pending host and join operations combined.
     */
    private static final int MAX_PENDING_OPERATIONS = 16;

    /**
     * Maximum accepted length of an expected-peer identity.
     */
    private static final int MAX_PEER_LENGTH = 256;

    /**
     * Maximum accepted length of an operation identifier.
     */
    private static final int MAX_OPERATION_ID_LENGTH = 128;

    /**
     * Maximum accepted length of an invitation, answer, or deep-link URI.
     */
    private static final int MAX_LINK_LENGTH = 65_536;

    /**
     * Owner of Link invitation, import, and teardown operations.
     */
    private final ControlPlaneLinkOperations linkOperations;

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
     * Creates the onboarding endpoints over a Link operation owner.
     *
     * @param linkOperations supported Link operation owner
     */
    ControlPlaneOnboardingEndpoints(ControlPlaneLinkOperations linkOperations) {
        this.linkOperations = linkOperations;
    }

    /**
     * Closes pending Link onboarding handles and then the Link operation owner.
     */
    void close() {
        List<AutoCloseable> pending = new ArrayList<>();
        synchronized (operationLock) {
            pending.addAll(hosts.values());
            pending.addAll(joins.values());
            hosts.clear();
            joins.clear();
            operationExpiry.clear();
            activeOperations.clear();
        }
        pending.forEach(ControlPlaneOnboardingEndpoints::closeQuietly);
        linkOperations.close();
    }

    /**
     * Describes the non-sensitive pending operations for the snapshot.
     *
     * <p>Invitation and answer links are deliberately omitted; only ids, states, and expiry are exposed.
     *
     * @return {@code pendingHosts} and {@code pendingJoins}
     */
    Map<String, Object> pending() {
        List<Map<String, Object>> pendingHosts = new ArrayList<>();
        List<Map<String, Object>> pendingJoins = new ArrayList<>();
        synchronized (operationLock) {
            for (Map.Entry<String, PendingHost> host : hosts.entrySet()) {
                Instant expiresAt = operationExpiry.get(host.getKey());
                if (expiresAt == null) {
                    continue;
                }
                boolean compact = CompactBootstrapInvitation.isShareLink(host.getValue().invitationLink());
                pendingHosts.add(ordered("operationId", host.getKey(), "state", hostState(compact), "expiresAt", expiresAt.toString()));
            }
            for (String operationId : joins.keySet()) {
                Instant expiresAt = operationExpiry.get(operationId);
                if (expiresAt == null) {
                    continue;
                }
                pendingJoins.add(ordered("operationId", operationId, "state", "WAITING_FOR_CONNECT", "expiresAt", expiresAt.toString()));
            }
        }
        return ordered("pendingHosts", pendingHosts, "pendingJoins", pendingJoins);
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
     * Routes a {@code synesis://} deep link handed over by the local installation shell.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code uri}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the link is invalid, unsupported, or matches no pending operation
     */
    void deepLink(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
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
     * Creates an invitation and registers it as a pending host operation.
     *
     * <p>Compact invitations connect on their own, so completion is monitored; classic ones wait for an answer.
     *
     * @param exchange HTTP exchange
     * @param body     request body with optional {@code expectedPeer}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when capacity is exhausted or the Link layer fails
     */
    void invite(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
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
    void join(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
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
            Map<String, Object> connected = operationResult(operationId, "CONNECTED");
            connected.put("peerIdentity", parsed.peerIdentity());
            complete(exchange, 201, connected, joins, operationId, prepared, prepared::connect, prepared::retainsSession);
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
     * Completes a pending host operation with the joiner's answer.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId} and {@code answerUri}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the operation is unknown or busy, or the Link layer fails
     */
    void answer(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        String answer = requiredText(body, "answerUri", MAX_LINK_LENGTH);
        PendingHost prepared = begin(hosts, operationId, "host");
        // The retained host handle owns its bounded wait. Do not serialize it
        // with a join handle: the Link handshake requires both sides to run at
        // the same time when they are hosted by this runtime.
        complete(exchange, 200, operationResult(operationId, "CONNECTED"), hosts, operationId, prepared, () -> prepared.importAnswer(answer), prepared::retainsSession);
    }

    /**
     * Connects a pending join operation.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the operation is unknown or busy, or the Link layer fails
     */
    void connect(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        PendingJoin prepared = begin(joins, operationId, "join");
        // The retained join handle owns its bounded candidate race. It must be
        // able to overlap a host answer operation in the same runtime.
        complete(exchange, 200, operationResult(operationId, "CONNECTED"), joins, operationId, prepared, prepared::connect, prepared::retainsSession);
    }

    /**
     * Cancels an idle pending operation and closes its handle.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  409 when the operation is running, 404 when it does not exist
     */
    void cancel(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
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
     * Runs the Link step of a registered operation, sends the result, and always retires the operation.
     *
     * @param exchange        HTTP exchange
     * @param status          HTTP status of the success response
     * @param result          success response body
     * @param registry        {@link #hosts} or {@link #joins}
     * @param operationId     operation id
     * @param prepared        the operation handle
     * @param step            the Link call that completes the operation
     * @param retainsSession  whether the handle now owns a live session; read once the step succeeded
     * @param <T>             handle type
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  400 when the Link layer fails
     */
    private <T extends AutoCloseable> void complete(HttpExchange exchange, int status, Map<String, Object> result, Map<String, T> registry, String operationId, T prepared, LinkStep step, BooleanSupplier retainsSession)
            throws IOException, ApiFailure {
        boolean retain = false;
        try {
            step.run();
            retain = retainsSession.getAsBoolean();
            sendJson(exchange, status, result);
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        } finally {
            finish(registry, operationId, prepared, retain);
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
    void cleanupExpiredOperations() {
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
        expired.forEach(ControlPlaneOnboardingEndpoints::closeQuietly);
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
     * The Link call that completes a pending operation.
     */
    @FunctionalInterface
    private interface LinkStep {

        /**
         * Performs the call.
         *
         * @throws OnboardingFailure when the Link layer rejects it
         */
        void run() throws OnboardingFailure;
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
}