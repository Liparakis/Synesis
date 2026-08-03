package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Bounded loopback HTTP client for the Codex lifecycle route.
 *
 * <p>The client sends the exact signed envelope supplied by
 * {@link LifecycleControlRequestEnvelope.SignedEnvelope}. A transport retry
 * must call {@link #submit} again with the same immutable envelope; this class
 * does not reconstruct semantic fields or extend its deadline. The client is
 * thread-safe and uses the JDK HTTP client.
 *
 * @since 1.0
 */
public final class CodexLifecycleHttpClient {

    /** Maximum bounded response body. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final URI endpoint;
    private final HttpClient client;

    /**
     * Creates a client for one existing coordination host.
     *
     * @param endpoint host base endpoint or lifecycle route
     */
    public CodexLifecycleHttpClient(URI endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getHost() == null
                || !("localhost".equalsIgnoreCase(endpoint.getHost())
                || "127.0.0.1".equals(endpoint.getHost()) || "::1".equals(endpoint.getHost()))) {
            throw new IllegalArgumentException("Codex lifecycle endpoint must be loopback HTTP");
        }
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Submits one signed envelope using its original deadline.
     *
     * @param envelope signed immutable request
     * @return bounded owner response
     * @throws IOException when transport or owner rejects the request
     * @throws InterruptedException when the caller is interrupted
     */
    public Response submit(LifecycleControlRequestEnvelope.SignedEnvelope envelope)
            throws IOException, InterruptedException {
        Objects.requireNonNull(envelope, "envelope");
        long remaining = envelope.request().callerDeadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new IOException("lifecycle_caller_deadline_expired");
        }
        HttpRequest request = HttpRequest.newBuilder(route())
                .timeout(Duration.ofMillis(Math.max(1L, remaining)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(envelope.encoded()))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new IOException("lifecycle response exceeds bound");
        }
        Response decoded = Response.decode(response.body());
        if (response.statusCode() >= 400 || !decoded.success()) {
            throw new IOException(decoded.diagnostic());
        }
        return decoded;
    }

    /**
     * Returns the normalized Codex-only route.
     *
     * @return lifecycle route
     */
    public URI route() {
        String base = endpoint.toString().endsWith("/") ? endpoint.toString() : endpoint + "/";
        if (base.endsWith("/codex-lifecycle/v1/")) {
            return URI.create(base.substring(0, base.length() - 1));
        }
        return URI.create(base + "codex-lifecycle/v1");
    }

    /**
     * Bounded lifecycle response returned by the production owner.
     *
     * @param success whether the operation succeeded
     * @param diagnostic stable bounded diagnostic
     * @param state authoritative lifecycle state
     * @param lifecycleRevision lifecycle revision
     * @param threadId exact thread identity
     * @param turnId exact turn identity
     * @param result bounded result and diagnostics
     */
    public record Response(boolean success, String diagnostic, String state, long lifecycleRevision,
            String threadId, String turnId, Map<String, Object> result) {
        /** Validates and freezes response values. */
        public Response {
            diagnostic = diagnostic == null ? "" : diagnostic;
            state = state == null ? "UNKNOWN" : state;
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }

        /**
         * Decodes a bounded JSON response.
         *
         * @param bytes response bytes
         * @return decoded response
         * @throws IOException malformed response
         */
        public static Response decode(byte[] bytes) throws IOException {
            try {
                Object parsed = ProviderJson.parse(strictUtf8(bytes));
                if (!(parsed instanceof Map<?, ?> raw)) {
                    throw new IOException("lifecycle response must be an object");
                }
                Map<String, Object> value = new LinkedHashMap<>();
                raw.forEach((key, item) -> value.put(String.valueOf(key), item));
                Map<String, Object> result = new LinkedHashMap<>();
                Object rawResult = value.get("result");
                if (rawResult instanceof Map<?, ?> map) {
                    map.forEach((key, item) -> result.put(String.valueOf(key), item));
                }
                return new Response(Boolean.TRUE.equals(value.get("success")), text(value, "diagnostic"),
                        text(value, "state"), number(value, "lifecycleRevision"), optional(value, "threadId"),
                        optional(value, "turnId"), result);
            } catch (RuntimeException failure) {
                throw new IOException("malformed lifecycle response", failure);
            }
        }

        private static String strictUtf8(byte[] bytes) throws IOException {
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes));
                return decoded.toString();
            } catch (CharacterCodingException failure) {
                throw new IOException("lifecycle_response_invalid_utf8", failure);
            }
        }

        /**
         * Returns the encoded bounded response.
         *
         * @return UTF-8 JSON response
         */
        public byte[] encoded() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("success", success);
            value.put("diagnostic", diagnostic);
            value.put("state", state);
            value.put("lifecycleRevision", lifecycleRevision);
            value.put("threadId", threadId);
            value.put("turnId", turnId);
            value.put("result", result);
            return ProviderJson.write(value).getBytes(StandardCharsets.UTF_8);
        }

        private static String text(Map<String, Object> value, String key) {
            Object item = value.get(key);
            return item == null ? "" : String.valueOf(item);
        }

        private static String optional(Map<String, Object> value, String key) {
            Object item = value.get(key);
            return item == null ? null : String.valueOf(item);
        }

        private static long number(Map<String, Object> value, String key) {
            Object item = value.get(key);
            return item instanceof Number number ? number.longValue() : 0L;
        }
    }
}
