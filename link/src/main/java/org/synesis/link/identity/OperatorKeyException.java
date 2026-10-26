package org.synesis.link.identity;

import java.io.IOException;

/**
 * The operator key could not be used, with a stable code a command prints.
 */
public final class OperatorKeyException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the failure.
     *
     * @param code stable code, for example {@code OPERATOR_PASSPHRASE_WRONG}
     */
    public OperatorKeyException(String code) {
        super(code);
        this.code = code;
    }

    /**
     * Returns the stable code.
     *
     * @return the code
     */
    public String code() {
        return code;
    }
}
