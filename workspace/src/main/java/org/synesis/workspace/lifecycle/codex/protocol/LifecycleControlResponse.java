package org.synesis.workspace.lifecycle.codex.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Bounded lifecycle response returned by the production owner.
 *
 * @param success           whether the operation succeeded
 * @param diagnostic        stable bounded diagnostic
 * @param state             authoritative lifecycle state
 * @param lifecycleRevision lifecycle revision
 * @param threadId          exact thread identity
 * @param turnId            exact turn identity
 * @param result            bounded result and diagnostics
 * @since 1.0
 */
public record LifecycleControlResponse(boolean success, String diagnostic, String state, long lifecycleRevision, String threadId, String turnId, Map<String, Object> result) {

    /**
     * Maximum bounded response body.
     */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * Validates and freezes response values.
     */
    public LifecycleControlResponse {
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
    public static LifecycleControlResponse decode(byte[] bytes) throws IOException {
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
            return new LifecycleControlResponse(Boolean.TRUE.equals(value.get("success")), text(value, "diagnostic"), text(value, "state"), number(value), optional(value, "threadId"), optional(value, "turnId"), result);
        } catch (RuntimeException failure) {
            throw new IOException("malformed lifecycle response", failure);
        }
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("lifecycle_response_invalid_utf8", failure);
        }
    }

    private static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? "" : String.valueOf(item);
    }

    private static String optional(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? null : String.valueOf(item);
    }

    private static long number(Map<String, Object> value) {
        Object item = value.get("lifecycleRevision");
        return item instanceof Number number ? number.longValue() : 0L;
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
}
