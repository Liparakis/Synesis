package org.synesis.cli.command.operator;

import picocli.CommandLine.Command;

/**
 * Parent command for the requests a project makes of its operator.
 */
@Command(name = "operator", description = "See and re-check what a project is waiting on you for.", mixinStandardHelpOptions = true)
public final class OperatorCommand {

    /**
     * Creates the parent command.
     */
    public OperatorCommand() {
    }
}
