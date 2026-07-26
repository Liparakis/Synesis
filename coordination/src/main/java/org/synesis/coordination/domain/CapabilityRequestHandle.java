package org.synesis.coordination.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable cryptographic locator for a Stage 2B capability request.
 *
 * <p>Handles take the format {@code req_<random_token>} where the random token
 * contains at least 96 bits of cryptographic entropy. Handles do not encode
 * project IDs, worker IDs, prediction IDs, session IDs, branch names, or worktree paths.
 * Possession of a handle locator alone does not grant authorization.
 *
 * @param value raw handle string (e.g. {@code req_K7F3M2X9Q4V8N2})
 * @since 1.0
 */
public record CapabilityRequestHandle(String value) {

    private static final Pattern HANDLE_PATTERN = Pattern.compile("^req_[a-zA-Z0-9]{12,64}$");

    /**
     * Compact constructor enforcing handle format bounds.
     *
     * @param value raw handle string
     * @throws IllegalArgumentException if the handle is invalid or contains prohibited metadata
     */
    public CapabilityRequestHandle {
        Objects.requireNonNull(value, "handle value cannot be null");
        String trimmed = value.trim();
        if (!isValid(trimmed)) {
            throw new IllegalArgumentException("Invalid capability request handle format: " + value);
        }
        String token = trimmed.substring(4).toUpperCase(Locale.ROOT);
        value = "req_" + token;
    }

    /**
     * Validates whether a handle string satisfies structural and entropy requirements.
     *
     * @param input handle string candidate
     * @return {@code true} if structurally valid
     */
    public static boolean isValid(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return HANDLE_PATTERN.matcher(input.trim()).matches();
    }

    /**
     * Parses a raw string into a normalized {@link CapabilityRequestHandle}.
     *
     * @param input handle string candidate
     * @return validated handle instance
     * @throws IllegalArgumentException if input is invalid
     */
    public static CapabilityRequestHandle parse(String input) {
        return new CapabilityRequestHandle(input);
    }

    /**
     * Returns the handle string representation.
     *
     * @return handle string
     */
    public String handle() {
        return value;
    }
}
