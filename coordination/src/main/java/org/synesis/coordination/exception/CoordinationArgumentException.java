package org.synesis.coordination.exception;

import java.io.Serial;

/**
 * Invalid caller-supplied coordination input.
 */
public final class CoordinationArgumentException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an input failure.
     *
     * @param code stable validation code or diagnostic
     */
    public CoordinationArgumentException(String code) {
        super(code);
    }

    /**
     * Creates an input failure with its parsing or validation cause.
     *
     * @param code  stable validation code or diagnostic
     * @param cause underlying invalid value
     */
    public CoordinationArgumentException(String code, Throwable cause) {
        super(code, cause);
    }
}
