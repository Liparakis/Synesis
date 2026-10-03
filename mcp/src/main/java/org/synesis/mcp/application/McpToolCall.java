package org.synesis.mcp.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated, typed view of the MCP {@code tools/call} envelope.
 *
 * <p>Unknown tool-specific fields remain JSON values for the owning operation to validate. The
 * envelope itself guarantees a raw tool name and a string-keyed argument object.
 *
 * @param name      raw catalog tool name, without the internal {@code synesis.} prefix
 * @param arguments optional immutable argument object; absence is distinct from an empty object
 */
record McpToolCall(String name, Optional<Map<String, Object>> arguments) {

    /** Prefix reserved for internal tool names; clients must send the raw name. */
    private static final String INTERNAL_PREFIX = "synesis.";

    /**
     * Enforces the record invariants for both decoded and directly constructed values, and takes an
     * unmodifiable copy of the arguments.
     */
    McpToolCall {
        if (!isRawName(name)) {
            throw new IllegalArgumentException("raw MCP tool name required");
        }
        arguments = Objects.requireNonNull(arguments, "arguments")
                .map(values -> Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /**
     * Validates an untrusted JSON-RPC parameter value as a tool-call envelope.
     *
     * @param params parsed JSON value from the request's {@code params} field
     * @return validated tool-call envelope
     * @throws IllegalArgumentException if the envelope shape or a top-level key is invalid
     */
    static McpToolCall decode(Object params) {
        if (!(params instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Invalid params: missing params object");
        }
        // The name is checked here, ahead of the arguments, so a bad name wins over a bad argument shape.
        if (!(values.get("name") instanceof String name) || !isRawName(name)) {
            throw new IllegalArgumentException("raw MCP tool name required");
        }

        Object rawArguments = values.get("arguments");
        if (rawArguments == null) {
            return new McpToolCall(name, Optional.empty());
        }
        if (!(rawArguments instanceof Map<?, ?> argumentValues)) {
            throw new IllegalArgumentException("tool arguments must be an object");
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : argumentValues.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("tool argument names must be strings");
            }
            arguments.put(key, entry.getValue());
        }
        return new McpToolCall(name, Optional.of(arguments));
    }

    /**
     * Checks that a tool name is non-blank and not an internal one.
     *
     * @param name candidate name, possibly {@code null}
     * @return {@code true} for a usable raw tool name
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isRawName(String name) {
        return name != null && !name.isBlank() && !name.startsWith(INTERNAL_PREFIX);
    }
}