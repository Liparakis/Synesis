package org.synesis.coordination.exception;

import java.io.Serial;

/**
 * Signals an impossible failure inside an in-memory coordination operation.
 */
public final class CoordinationInvariantError extends AssertionError {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an invariant failure with its impossible underlying cause.
     *
     * @param cause cause that should be impossible for this operation
     */
    public CoordinationInvariantError(Throwable cause) {
        super(cause);
    }
}
