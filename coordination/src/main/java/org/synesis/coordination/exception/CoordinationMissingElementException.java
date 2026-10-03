package org.synesis.coordination.exception;

import java.io.Serial;
import java.util.NoSuchElementException;

/**
 * Coordination-specific missing-value failure that preserves Optional's public exception contract.
 */
public final class CoordinationMissingElementException extends NoSuchElementException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the standard missing-value diagnostic used by {@link java.util.Optional#orElseThrow()}.
     */
    public CoordinationMissingElementException() {
        super("No value present");
    }
}
