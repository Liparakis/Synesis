package org.synesis.cli.command.link;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;

/**
 * Declines a computer that opened this project's invitation: its session is closed and it is not a member.
 */
@Command(name = "decline", description = "Decline a computer waiting to join this project.", mixinStandardHelpOptions = true)
public final class LinkDeclineCommand extends LinkDecideCommand {

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkDeclineCommand(CliRuntime runtime) {
        super(runtime, HttpRuntimeControl.daemon(), "decline-join");
    }

    LinkDeclineCommand(CliRuntime runtime, RuntimeControl.Source source) {
        super(runtime, source, "decline-join");
    }
}
