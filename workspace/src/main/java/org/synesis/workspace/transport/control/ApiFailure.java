package org.synesis.workspace.transport.control;

import java.io.Serial;

/**
 * A request failure that maps directly to an HTTP error response.
 *
 * <p>Top-level and package-private so the handler and its endpoint collaborators raise and catch one
 * failure type without any of them owning it.
 */
final class ApiFailure extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * HTTP status code.
     */
    private final int status;

    /**
     * Stable machine-readable error code.
     */
    private final String code;

    /**
     * Creates a failure.
     *
     * @param status  HTTP status
     * @param code    stable machine-readable code
     * @param message client-visible message
     */
    ApiFailure(int status, String code, String message) {
        // Used for control flow and never logged, so skip the cost of capturing a stack trace.
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    /**
     * Returns the HTTP status.
     *
     * @return HTTP status code
     */
    int status() {
        return status;
    }

    /**
     * Returns the machine-readable code.
     *
     * @return stable error code
     */
    String code() {
        return code;
    }
}
