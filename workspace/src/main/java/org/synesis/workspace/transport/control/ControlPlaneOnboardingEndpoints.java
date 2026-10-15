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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.protocol.CompactAnswer;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.IncompatibleVersionException;
import org.synesis.link.protocol.LinkCompatibility;
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
     * Guards {@link #hosts}, {@link #joins}, and {@link #operationExpiry}.
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
                pendingHosts.add(ordered("operationId", host.getKey(), "state", "WAITING_FOR_CONNECTION", "expiresAt", expiresAt.toString()));
            }
            for (String operationId : joins.keySet()) {
                Instant expiresAt = operationExpiry.get(operationId);
                if (expiresAt == null) {
                    continue;
                }
                pendingJoins.add(ordered("operationId", operationId, "state", "CONNECTING", "expiresAt", expiresAt.toString()));
            }
        }
        return ordered("pendingHosts", pendingHosts, "pendingJoins", pendingJoins);
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
     * <p>The peer connects on its own, so completion is monitored; an answer only helps it through.
     *
     * @param exchange HTTP exchange
     * @param body     request body with optional {@code expectedPeer} and optional boolean
     *                 {@code openRouterPort}, the user's choice to have the router forward a port
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when capacity is exhausted or the Link layer fails
     */
    void invite(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String expectedPeer = body.get("expectedPeer") == null ? null : requiredText(body, "expectedPeer", MAX_PEER_LENGTH);
        boolean openRouterPort = Boolean.TRUE.equals(body.get("openRouterPort"));
        ensureCapacity();
        PendingHost prepared;
        try {
            synchronized (onboardingLock) {
                prepared = linkOperations.createInvitation(expectedPeer, openRouterPort);
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
        monitorHost(operationId, prepared);
        Map<String, Object> response = newResponse();
        response.put("operationId", operationId);
        response.put("inviteUri", link);
        response.put("peerIdentity", parsed.peerIdentity());
        response.put("expiresAt", parsed.expiresAt().toString());
        response.put("state", "WAITING_FOR_CONNECTION");
        sendJson(exchange, 201, response);
    }

    /**
     * Imports an invitation as a pending join operation.
     *
     * <p>The join dials the host in the background until it connects or the invitation expires, and
     * the response returns at once with the answer link the host's user may need.
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
        // Not marked running: a join dials until its invitation expires, and cancelling or expiry
        // must be able to close it, which ends the dialing.
        Thread.ofVirtual().start(() -> {
            boolean connected = false;
            try {
                prepared.connect();
                connected = true;
            } catch (OnboardingFailure failed) {
                // The operation leaves the pending list without a peer in the network view.
            } finally {
                finish(joins, operationId, prepared, connected && prepared.retainsSession());
            }
        });
        Map<String, Object> response = newResponse();
        response.put("operationId", operationId);
        response.put("answerUri", prepared.answerLink());
        response.put("peerIdentity", parsed.peerIdentity());
        response.put("expiresAt", parsed.expiresAt().toString());
        response.put("state", "CONNECTING");
        sendJson(exchange, 201, response);
    }

    /**
     * Gives a waiting host operation the joiner's answer, which makes it send toward the joiner.
     * The operation keeps waiting for the peer.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId} and {@code answerUri}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the operation is unknown or the Link layer rejects the answer
     */
    void answer(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        String answer = requiredText(body, "answerUri", MAX_LINK_LENGTH);
        PendingHost prepared;
        synchronized (operationLock) {
            prepared = hosts.get(operationId);
        }
        if (prepared == null) {
            throw failure(404, "OPERATION_NOT_FOUND", "host operation is not available");
        }
        try {
            prepared.importAnswer(answer);
        } catch (OnboardingFailure failure) {
            throw onboardingFailure(failure);
        }
        sendJson(exchange, 200, operationResult(operationId, "SENDING_TO_JOINER"));
    }

    /**
     * Cancels a pending operation and closes its handle, which also ends a join that is still dialing.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code operationId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  404 when the operation does not exist
     */
    void cancel(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String operationId = requiredText(body, "operationId", MAX_OPERATION_ID_LENGTH);
        AutoCloseable pending;
        synchronized (operationLock) {
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
    private String matchingHostOperation(CompactAnswer answer) throws ApiFailure {
        List<String> matches = new ArrayList<>();
        synchronized (operationLock) {
            for (Map.Entry<String, PendingHost> entry : hosts.entrySet()) {
                try {
                    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.fromShareLink(entry.getValue().invitationLink());
                    if (invitation.sessionId().equals(answer.sessionId())) {
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
     * Extracts the display details of an invitation.
     *
     * @param link share link
     * @return peer identity and expiry
     * @throws ApiFailure 400 when the link is invalid
     */
    private static InvitationDetails parseInvitationDetails(String link) throws ApiFailure {
        try {
            CompactBootstrapInvitation invitation = CompactBootstrapInvitation.fromShareLink(link);
            return new InvitationDetails(invitation.hostNodeId(), invitation.expiresAt());
        } catch (IncompatibleVersionException differentVersion) {
            throw incompatible(differentVersion.mismatch());
        } catch (IOException | IllegalArgumentException invalid) {
            throw failure(400, "INVITE_INVALID", "invitation link is invalid");
        }
    }

    /**
     * Parses an answer link.
     *
     * @param link share link
     * @return parsed answer
     * @throws ApiFailure 400 when the link is invalid
     */
    private static CompactAnswer parseAnswer(String link) throws ApiFailure {
        try {
            return CompactAnswer.fromShareLink(link);
        } catch (IncompatibleVersionException differentVersion) {
            throw incompatible(differentVersion.mismatch());
        } catch (IOException | IllegalArgumentException invalid) {
            throw failure(400, "ANSWER_INVALID", "answer link is invalid");
        }
    }

    /**
     * A different compatibility number is not a malformed link, so it gets its own code and the
     * message that says which side is older and what to do. The message holds only the two
     * numbers, so it is safe to return.
     */
    private static ApiFailure incompatible(LinkCompatibility.Mismatch mismatch) {
        return failure(400, "INCOMPATIBLE_VERSION", mismatch.message());
    }

    /**
     * Maps a Link onboarding failure to a 400 without leaking its detail.
     *
     * @param failure Link failure
     * @return API failure coded {@code ONBOARDING_<code>}
     */
    private static ApiFailure onboardingFailure(OnboardingFailure failure) {
        if (failure.mismatch() != null) {
            return incompatible(failure.mismatch());
        }
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
        }
        if (!retain) {
            closeQuietly(prepared);
        }
    }

    /**
     * Retires a host operation once its peer connects or the wait fails.
     *
     * @param operationId operation id
     * @param prepared    the host handle
     */
    private void monitorHost(String operationId, PendingHost prepared) {
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
     * Closes and forgets operations past their expiry.
     */
    void cleanupExpiredOperations() {
        Instant now = Instant.now();
        List<AutoCloseable> expired = new ArrayList<>();
        synchronized (operationLock) {
            operationExpiry.entrySet().removeIf(entry -> {
                if (now.isBefore(entry.getValue())) {
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
     * Display details extracted from an invitation link.
     *
     * @param peerIdentity node id of the inviting peer
     * @param expiresAt    invitation expiry
     */
    private record InvitationDetails(String peerIdentity, Instant expiresAt) {

    }
}