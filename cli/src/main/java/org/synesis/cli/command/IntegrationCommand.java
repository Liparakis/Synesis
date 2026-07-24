package org.synesis.cli.command;

import picocli.CommandLine.Command;

/**
 * Parent for prediction integration gates.
 */
@Command(name = "integration", description = "Gate prediction integration into a project.", mixinStandardHelpOptions = true)
public final class IntegrationCommand implements Runnable {

    /**
     * Creates the integration command parent.
     */
    public IntegrationCommand() {
    }

    /**
     * Runs the parent help command.
     */
    @Override
    public void run() {
    }
}
