package org.synesis.mcp.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads typed values out of the untrusted argument object of a {@code tools/call} request.
 *
 * <p>Kept apart from {@link McpProtocolHandler} because these rules decide which wire values are
 * accepted and which are rejected with {@link IllegalArgumentException}, and they depend on nothing
 * but the argument map. They can therefore be inspected without a connection, a project root, or an
 * application service.
 */
final class McpToolArguments {

    /** Utility class; these rules are exposed through static operations. */
    private McpToolArguments() {
    }

    /**
     * Reads the required nonblank finish outcome field.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return the outcome text
     * @throws IllegalArgumentException when the outcome is missing, blank, or not a string
     */
    static String requiredStringArgument(Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("outcome") instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("invalid outcome");
        }
        return value;
    }

    /**
     * Reads an optional string argument and rejects values of any other JSON type.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @return the value, or {@code null} when absent or JSON null
     * @throws IllegalArgumentException when the value is not a string
     */
    static String optionalStringArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return null;
        }
        if (!(arguments.get(key) instanceof String value)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    /**
     * Reads an optional boolean argument, using {@code false} when omitted.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @return the value, or {@code false} when absent or JSON null
     * @throws IllegalArgumentException when the value is not a boolean
     */
    @SuppressWarnings("SameParameterValue")
    static boolean optionalBooleanArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return false;
        }
        if (!(arguments.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    /**
     * Parses an optional UUID string while preserving the argument name in validation errors.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @return the parsed UUID, or {@code null} when absent
     * @throws IllegalArgumentException when the value is not a string holding a UUID
     */
    static UUID optionalUuidArgument(Map<String, Object> arguments, String key) {
        String value = optionalStringArgument(arguments, key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid " + key, invalid);
        }
    }

    /**
     * Reads an optional integral JSON number constrained by a caller-supplied minimum.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param key       argument name
     * @param minimum   smallest accepted value
     * @return the value, or {@code null} when absent
     * @throws IllegalArgumentException when the value is not an integral number or is below {@code minimum}
     */
    static Long optionalLongArgument(Map<String, Object> arguments, String key, long minimum) {
        if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
            return null;
        }
        Object raw = arguments.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        long value = number.longValue();
        if ((raw instanceof Double || raw instanceof Float) && number.doubleValue() != value) {
            throw new IllegalArgumentException("invalid " + key);
        }
        if (value < minimum) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return value;
    }

    /**
     * Names a requester validation snapshot: the capability request and the implementation revision
     * published for it. Both are {@code null} when the caller selected no snapshot.
     *
     * @param capabilityRequestHandle handle of the capability request, or {@code null}
     * @param implementationRevision  published implementation revision, or {@code null}
     */
    record ValidationSnapshotSelector(String capabilityRequestHandle, Integer implementationRevision) {
    }

    /**
     * Reads the optional validation-snapshot selector shared by {@code read_file} and
     * {@code run_command}.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @return the selector, with {@code null} members when omitted
     * @throws IllegalArgumentException when the handle is not a string, or the revision is not a positive
     *                                  integer that fits an {@code int}
     */
    static ValidationSnapshotSelector validationSnapshotSelector(Map<String, Object> arguments) {
        String handle = optionalStringArgument(arguments, "capabilityRequestHandle");
        Long revision = optionalLongArgument(arguments, "implementationRevision", 1);
        if (revision != null && revision > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid implementationRevision");
        }
        return new ValidationSnapshotSelector(handle, revision == null ? null : revision.intValue());
    }

    /**
     * Returns the first non-blank string under the canonical key or a bounded provider-shaped alias.
     * Aliases are input normalization only: the raw ten-tool catalog remains canonical and
     * authorization is unchanged.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param canonical canonical wire key
     * @param alias     provider-shaped spelling accepted at dispatch
     * @return selected value or {@code null}
     */
    static String stringArgument(Map<String, Object> arguments, String canonical, String alias) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(canonical);
        if (!(value instanceof String string) || string.isBlank()) {
            value = arguments.get(alias);
        }
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    /**
     * Keeps the string entries of a JSON array, silently dropping everything else.
     *
     * @param value untrusted value, possibly {@code null}
     * @return the string entries, empty when the value is not an array
     */
    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * Decodes an optional JSON string array without silently dropping malformed entries.
     *
     * @param value untrusted value from a JSON object
     * @param field field name used in validation errors
     * @return {@code null} when absent, otherwise an immutable list of strings
     * @throws IllegalArgumentException when the value is not a string array
     */
    static List<String> nullableStringList(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> strings = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String text)) {
                throw new IllegalArgumentException(field + " entries must be strings");
            }
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    /**
     * Returns whether a parsed JSON number has an integral representation.
     *
     * @param number parsed JSON number
     * @return {@code true} for a non-floating-point number whose value is exactly integral
     */
    static boolean isStrictInteger(Number number) {
        if (number instanceof Double || number instanceof Float) {
            return false;
        }
        long value = number.longValue();
        return number.doubleValue() == value;
    }
}
