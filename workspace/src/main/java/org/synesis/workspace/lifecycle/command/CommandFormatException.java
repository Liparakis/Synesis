package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals incompatible, corrupt, or unverifiable durable command state.
 */
public final class CommandFormatException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a format failure with a stable diagnostic message.
     *
     * @param message stable diagnostic message
     */
    public CommandFormatException(String message) {
        super(message);
    }

    /**
     * Creates a format failure with a stable diagnostic message and cause.
     *
     * @param message stable diagnostic message
     * @param cause   underlying failure
     */
    public CommandFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
