package org.synesis.workspace.lifecycle.lease;

import java.util.Objects;

/**
 * Immutable process identity evidence capturing portable host process attributes.
 *
 * @param pid                process ID
 * @param executableIdentity executable path or name
 * @param commandLine        command line string, if available
 * @param processStartTime   process start epoch millisecond timestamp
 * @param connectionNonce    random connection nonce
 * @since 1.0
 */
public record SessionProcessIdentity(
        long pid,
        String executableIdentity,
        String commandLine,
        long processStartTime,
        String connectionNonce
) {

    /**
     * Invariant validation.
     */
    public SessionProcessIdentity {
        Objects.requireNonNull(executableIdentity, "executableIdentity");
        Objects.requireNonNull(commandLine, "commandLine");
        Objects.requireNonNull(connectionNonce, "connectionNonce");
        if (pid <= 0L || processStartTime < 0L || connectionNonce.isBlank()) {
            throw new IllegalArgumentException("invalid process identity evidence");
        }
    }
}
