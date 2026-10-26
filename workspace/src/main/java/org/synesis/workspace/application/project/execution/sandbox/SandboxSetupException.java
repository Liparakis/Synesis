package org.synesis.workspace.application.project.execution.sandbox;

/**
 * The sandbox was available at the probe but could not be set up for one run. The command is not started and is
 * never retried unconfined.
 */
public final class SandboxSetupException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the platform's reason
     */
    public SandboxSetupException(String message) {
        super(message);
    }
}
