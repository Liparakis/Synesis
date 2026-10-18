package org.synesis.coordination.exception;

import java.io.IOException;
import java.io.Serial;

/**
 * Coded coordination rejection or malformed durable coordination data.
 */
public class CoordinationException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Stable machine-readable coordination failure code.
     */
    private final String code;

    /**
     * Creates a coordination failure with its existing stable error code.
     *
     * @param code stable machine-readable failure code or bounded diagnostic
     */
    public CoordinationException(String code) {
        super(code);
        this.code = code;
    }

    /**
     * Creates a coordination failure while retaining its cause.
     *
     * @param code  stable machine-readable failure code or bounded diagnostic
     * @param cause underlying decoding or validation failure
     */
    public CoordinationException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    /**
     * Returns the stable coordination failure code.
     *
     * @return machine-readable coordination code
     */
    @SuppressWarnings("unused")
    public String code() {
        return code;
    }
}
