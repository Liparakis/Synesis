package org.synesis.cli.command.link;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;

/**
 * Accepts a computer that opened this project's invitation: it becomes a member and is served the project's log.
 */
@Command(name = "accept", description = "Accept a computer waiting to join this project.", mixinStandardHelpOptions = true)
public final class LinkAcceptCommand extends LinkDecideCommand {

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkAcceptCommand(CliRuntime runtime) {
        super(runtime, HttpRuntimeControl.daemon(), "accept-join");
    }

    LinkAcceptCommand(CliRuntime runtime, RuntimeControl.Source source) {
        super(runtime, source, "accept-join");
    }
}
