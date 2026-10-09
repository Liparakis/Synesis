package org.synesis.workspace.transport.control;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Request-body reading, JSON response writing, and the {@link ApiFailure} factory shared by the
 * control-plane handler and its endpoint collaborators.
 *
 * <p>Separate from the handler so every endpoint group builds the same wire envelope (API version,
 * error shape, body bound) from one definition without depending on handler state.
 */
final class ControlPlaneHttpJson {

    /**
     * Value of the {@code apiVersion} field in every response body.
     */
    static final String API_VERSION = "v1";

    /**
     * Upper bound for a request body, enforced on both the declared and the streamed length.
     */
    private static final int MAX_BODY_BYTES = 128 * 1024;

    private ControlPlaneHttpJson() {
    }

    /**
     * Requires a JSON request body.
     *
     * @param exchange HTTP exchange
     * @throws ApiFailure 415 when the content type is missing or not {@code application/json}
     */
    static void requireJson(HttpExchange exchange) throws ApiFailure {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw failure(415, "UNSUPPORTED_MEDIA_TYPE", "JSON content type is required");
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
    static Map<String, Object> readObject(HttpExchange exchange) throws IOException, ApiFailure {
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
    static String requiredText(Map<String, Object> body, String key, int max) throws ApiFailure {
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
    static UUID uuid(String value, String field) throws ApiFailure {
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
    static Map<String, Object> newResponse() {
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
    static void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
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
    static void sendEmpty(HttpExchange exchange) throws IOException {
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
    static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
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
    static ApiFailure failure(int status, String code, String message) {
        return new ApiFailure(status, code, message);
    }
}