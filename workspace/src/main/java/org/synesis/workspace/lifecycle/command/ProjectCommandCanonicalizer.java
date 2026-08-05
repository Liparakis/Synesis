package org.synesis.workspace.lifecycle.command;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.application.project.ProjectProcessExecutor;

/** Canonicalizes bounded typed JSON-RPC request IDs for durable lookup. */
public final class ProjectCommandCanonicalizer {

    /** Maximum UTF-8-independent Java character count for one request ID. */
    public static final int MAX_REQUEST_ID_LENGTH = 256;

    private ProjectCommandCanonicalizer() {
    }

    /** Returns a type-preserving canonical request key for a JSON-RPC ID.
     * @param id JSON-RPC string or integer ID
     * @return bounded type-preserving canonical ID
     */
    public static String requestId(Object id) {
        Objects.requireNonNull(id, "id");
        String result;
        if (id instanceof String string) {
            if (string.isBlank()) {
                throw new IllegalArgumentException("request ID must not be blank");
            }
            result = "s:" + string;
        } else if (id instanceof Byte || id instanceof Short || id instanceof Integer || id instanceof Long) {
            result = "n:" + id;
        } else if (id instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
            if (decimal.scale() > 0) {
                throw new IllegalArgumentException("request ID must be an integer JSON number");
            }
            result = "n:" + decimal.toPlainString();
        } else {
            throw new IllegalArgumentException("request ID must be a JSON string or integer");
        }
        if (result.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("request ID exceeds bounded length");
        }
        return result;
    }

    /** Computes the digest of the canonical direct-command request.
     * @param argv direct executable and arguments
     * @param workingDirectory bounded relative working directory
     * @param timeoutSeconds command timeout, or {@code null} for default
     * @return SHA-256 request digest
     */
    public static String requestDigest(List<String> argv, String workingDirectory, Integer timeoutSeconds) {
        Objects.requireNonNull(argv, "argv");
        return digest(Map.of("argv", List.copyOf(argv), "workingDirectory",
                workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory,
                "timeoutSeconds", timeoutSeconds == null
                        ? ProjectProcessExecutor.DEFAULT_TIMEOUT_SECONDS : timeoutSeconds));
    }

    /** Computes the semantic digest including the authority identity of a request.
     * @param requestDigest canonical request digest
     * @param provider canonical provider ID
     * @param connectionInstanceId exact MCP connection ID
     * @param scopeLocator physical-worktree locator
     * @return SHA-256 semantic digest
     */
    public static String semanticDigest(String requestDigest, String provider,
            String connectionInstanceId, String scopeLocator) {
        return digest(Map.of("requestDigest", Objects.requireNonNull(requestDigest, "requestDigest"),
                "provider", Objects.requireNonNull(provider, "provider"),
                "connectionInstanceId", Objects.requireNonNull(connectionInstanceId, "connectionInstanceId"),
                "scopeLocator", Objects.requireNonNull(scopeLocator, "scopeLocator")));
    }

    /** Computes the keyed locator from complete anchor identity and typed request ID only.
     * @param anchorId complete immutable process-anchor identity
     * @param requestId canonical typed JSON-RPC request ID
     * @return SHA-256 keyed request locator
     */
    public static String requestKey(String anchorId, String requestId) {
        return digest(Map.of("anchorId", Objects.requireNonNull(anchorId, "anchorId"),
                "requestId", Objects.requireNonNull(requestId, "requestId")));
    }

    private static String digest(Map<String, Object> value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ProviderJson.write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("command digest unavailable", failure);
        }
    }
}
