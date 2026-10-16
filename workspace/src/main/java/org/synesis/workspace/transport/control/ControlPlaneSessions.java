package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpHandler.CSRF_HEADER;
import static org.synesis.workspace.transport.control.ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER;
import static org.synesis.workspace.transport.control.ControlPlaneHttpHandler.SESSION_HEADER;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.readObject;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requireJson;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requiredText;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Credentials and authentication for the control plane: the one-time bootstrap tokens, the single
 * browser session one of them can be exchanged for, and the runtime-owned command token.
 *
 * <p>A bootstrap token opens the UI once. The runtime prints the first one when it starts; the
 * holder of the runtime command token asks for another each time it opens the UI again, because
 * the runtime outlives the browser tab. Only the newest unredeemed token is redeemable, and a new
 * session replaces the one before it.
 *
 * <p>Owns every secret and the mutable session state so none of it is reachable from routing code;
 * the handler holds one instance and reaches the secrets only through the two accessors it already
 * publishes.
 */
final class ControlPlaneSessions {

    /**
     * Maximum accepted length of a bootstrap token.
     */
    private static final int MAX_TOKEN_LENGTH = 256;

    /**
     * How long a one-time bootstrap token stays redeemable after it is issued.
     */
    private static final Duration BOOTSTRAP_LIFETIME = Duration.ofMinutes(5);

    /**
     * Lifetime of a browser session; sessions are not renewed.
     */
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);

    /**
     * Source of all tokens issued by this class.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The bootstrap token issued at construction; delivered out of band by the caller.
     */
    private final String initialBootstrapToken = token();

    /**
     * Secret accepted only by the runtime-owned routes.
     */
    private final String runtimeCommandToken = token();

    /**
     * The one bootstrap token that can still be redeemed, or {@code null} once it has been.
     * Guarded by this object's monitor.
     */
    private Bootstrap pendingBootstrap = new Bootstrap(initialBootstrapToken, Instant.now().plus(BOOTSTRAP_LIFETIME));

    /**
     * The current browser session, or {@code null} until a bootstrap token has been redeemed.
     */
    private volatile Session session;

    /**
     * Returns the bootstrap token issued at construction.
     *
     * @return high-entropy bootstrap token
     */
    String bootstrapToken() {
        return initialBootstrapToken;
    }

    /**
     * Returns the runtime-owned command token.
     *
     * @return high-entropy runtime command token
     */
    String runtimeCommandToken() {
        return runtimeCommandToken;
    }

    /**
     * Issues a fresh one-time bootstrap token to the holder of the runtime command token, so the
     * UI can be opened again on a runtime whose earlier token is spent or expired. An earlier
     * unredeemed token stops working.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  401 when the runtime command token is missing or wrong
     */
    void issueBootstrap(HttpExchange exchange) throws IOException, ApiFailure {
        authenticateRuntimeCommand(exchange);
        Bootstrap issued = new Bootstrap(token(), Instant.now().plus(BOOTSTRAP_LIFETIME));
        synchronized (this) {
            pendingBootstrap = issued;
        }
        Map<String, Object> response = newResponse();
        response.put("bootstrapToken", issued.token());
        response.put("expiresAt", issued.expiresAt().toString());
        sendJson(exchange, 201, response);
    }

    /**
     * Redeems a one-time bootstrap token for a browser session.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  401 when the token is wrong, expired, or already used
     */
    void createSession(HttpExchange exchange) throws IOException, ApiFailure {
        requireJson(exchange);
        String supplied = requiredText(readObject(exchange), "bootstrapToken", MAX_TOKEN_LENGTH);
        Session created = redeem(supplied);
        Map<String, Object> response = newResponse();
        response.put("sessionToken", created.sessionToken());
        response.put("csrfToken", created.csrfToken());
        response.put("expiresAt", created.expiresAt().toString());
        sendJson(exchange, 201, response);
    }

    private synchronized Session redeem(String supplied) throws ApiFailure {
        Bootstrap pending = pendingBootstrap;
        if (pending == null || Instant.now().isAfter(pending.expiresAt()) || tokenMismatch(pending.token(), supplied)) {
            throw failure(401, "BOOTSTRAP_INVALID", "bootstrap token is invalid or expired");
        }
        pendingBootstrap = null;
        session = new Session(token(), token(), Instant.now().plus(SESSION_LIFETIME));
        return session;
    }

    /**
     * Requires a live browser session and, for mutations, its CSRF token.
     *
     * @param exchange HTTP exchange
     * @param mutation whether the route changes state
     * @throws ApiFailure 401 without a valid session, 403 without a valid CSRF token
     */
    void authenticate(HttpExchange exchange, boolean mutation) throws ApiFailure {
        if (!tokenMismatch(runtimeCommandToken, exchange.getRequestHeaders().getFirst(RUNTIME_COMMAND_HEADER))) {
            // The runtime command token already mints a bootstrap token and with it a session, so presenting it
            // is the same authority. A browser never holds it, so the CSRF header that guards a browser's
            // ambient credentials has nothing to guard here, and a command line client need not replace the
            // session of an open browser tab to use the control plane.
            return;
        }
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
    void authenticateRuntimeCommand(HttpExchange exchange) throws ApiFailure {
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
     * An issued bootstrap token.
     *
     * @param token     the one-time secret
     * @param expiresAt instant after which it can no longer be redeemed
     */
    private record Bootstrap(String token, Instant expiresAt) {

    }

    /**
     * An issued browser session.
     *
     * @param sessionToken secret sent in {@link ControlPlaneHttpHandler#SESSION_HEADER}
     * @param csrfToken    secret sent in {@link ControlPlaneHttpHandler#CSRF_HEADER} on mutations
     * @param expiresAt    instant after which the session is rejected
     */
    private record Session(String sessionToken, String csrfToken, Instant expiresAt) {

    }
}