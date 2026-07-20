package org.synesis.link.demo;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * One bounded correlated result in the demo-only work exchange.
 *
 * @param requestId request correlation ID
 * @param status fixed safe result status
 * @param message bounded safe result message
 */
public record DemoWorkResult(UUID requestId, DemoWorkStatus status, String message) {
    /** Maximum encoded result-message length. */
    public static final int MAX_MESSAGE_BYTES = 1_024;

    /** Validates status and bounded UTF-8 message data. */
    public DemoWorkResult {
        Objects.requireNonNull(requestId, "request ID");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("result message exceeds the supported bound");
        }
    }
}
