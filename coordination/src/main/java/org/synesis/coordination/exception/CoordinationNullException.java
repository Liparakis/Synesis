package org.synesis.coordination.exception;

import java.io.Serial;

/**
 * A missing value at a coordination API boundary.
 */
public final class CoordinationNullException extends NullPointerException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a null-input failure.
     *
     * @param valueName name of the missing value
     */
    CoordinationNullException(String valueName) {
        super(valueName);
    }

    /**
     * Requires and returns a non-null value.
     *
     * @param <T>       value type
     * @param value     candidate value
     * @param valueName diagnostic name, or {@code null}
     * @return the non-null value
     */
    public static <T> T requireNonNull(T value, String valueName) {
        if (value == null) {
            throw new CoordinationNullException(valueName);
        }
        return value;
    }
}
