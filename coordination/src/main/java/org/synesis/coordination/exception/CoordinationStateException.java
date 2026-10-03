package org.synesis.coordination.exception;

import java.io.Serial;

/**
 * Coordination state could not be read or violates an internal invariant.
 */
public final class CoordinationStateException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a state failure.
     *
     * @param code stable validation code or diagnostic
     */
    public CoordinationStateException(String code) {
        super(code);
    }

    /**
     * Creates a state failure.
     *
     * @param code  stable validation code or diagnostic
     * @param cause underlying state-read failure, when present
     */
    public CoordinationStateException(String code, Throwable cause) {
        super(code, cause);
    }

    /**
     * Preserves a cause-only internal state failure.
     *
     * @param cause underlying state-read failure
     */
    public CoordinationStateException(Throwable cause) {
        super(cause);
    }
}
