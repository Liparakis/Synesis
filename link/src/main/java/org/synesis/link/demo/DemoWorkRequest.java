package org.synesis.link.demo;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * One bounded request in the demo-only {@code synesis-demo-work/1} protocol.
 *
 * @param requestId stable correlation ID
 * @param operation fixed operation name
 */
public record DemoWorkRequest(UUID requestId, String operation) {
    /** The only operation implemented by the demonstration. */
    public static final String DESCRIBE_SESSION = "describe-session";
    /** Maximum encoded operation length. */
    public static final int MAX_OPERATION_BYTES = 64;

    /** Validates the fixed operation and bounded UTF-8 representation. */
    public DemoWorkRequest {
        Objects.requireNonNull(requestId, "request ID");
        Objects.requireNonNull(operation, "operation");
        if (!DESCRIBE_SESSION.equals(operation)) {
            throw new IllegalArgumentException("unsupported demo operation");
        }
        if (operation.getBytes(StandardCharsets.UTF_8).length > MAX_OPERATION_BYTES) {
            throw new IllegalArgumentException("operation exceeds the supported bound");
        }
    }
}
