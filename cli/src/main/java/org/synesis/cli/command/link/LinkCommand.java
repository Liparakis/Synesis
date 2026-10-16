package org.synesis.cli.command.link;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for connecting this project to another computer through the project's running runtime.
 */
@Command(name = "link", description = "Connect this project to another computer; the connection stays with the project's runtime.", mixinStandardHelpOptions = true)
public final class LinkCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the link parent command.
     */
    public LinkCommand() {
    }

    /**
     * Prints link help.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
