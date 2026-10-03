package org.synesis.coordination.exception;

import java.io.Serial;
import java.security.GeneralSecurityException;

/**
 * Coordination identity, authority, or event-signature failure.
 */
public final class CoordinationSecurityException extends GeneralSecurityException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a security failure.
     *
     * @param code stable authorization or signature failure code
     */
    public CoordinationSecurityException(String code) {
        super(code);
    }

}
