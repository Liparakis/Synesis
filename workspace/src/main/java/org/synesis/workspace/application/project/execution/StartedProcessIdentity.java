package org.synesis.workspace.application.project.execution;

import java.util.Objects;

/**
 * Exact process identity captured immediately after a command starts.
 *
 * @param pid                operating-system process ID
 * @param executableIdentity executable identity reported by the process handle
 * @param commandLine        bounded process command line evidence
 * @param processStartTime   process start epoch milliseconds
 */
public record StartedProcessIdentity(long pid, String executableIdentity, String commandLine, long processStartTime) {

    /**
     * Validates process identity evidence.
     */
    public StartedProcessIdentity {
        if (pid <= 0 || processStartTime < 0) {
            throw new IllegalArgumentException("invalid started process identity");
        }
        Objects.requireNonNull(executableIdentity, "executableIdentity");
        Objects.requireNonNull(commandLine, "commandLine");
    }
}
