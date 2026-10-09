package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpHandler.CSRF_HEADER;
import static org.synesis.workspace.transport.control.ControlPlaneHttpHandler.SESSION_HEADER;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * Browser-origin admission for the control plane: loopback-only listener, exact {@code Host} and
 * {@code Origin} matching, and the CORS response headers that follow from a validated origin.
 *
 * <p>Stateless and separate from the handler so the DNS-rebinding and cross-site defences can be
 * read and reviewed on their own; the handler runs them before any routing or authentication.
 */
final class ControlPlaneRequestBoundary {

    private ControlPlaneRequestBoundary() {
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
    static String validateRequestBoundary(HttpExchange exchange) throws ApiFailure {
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
    static void applyCorsHeaders(HttpExchange exchange, String origin) {
        Headers headers = exchange.getResponseHeaders();
        if (origin != null) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", SESSION_HEADER + ", " + CSRF_HEADER + ", Content-Type");
        headers.set("X-Content-Type-Options", "nosniff");
    }
}