package org.synesis.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Prints the unified command help.
 */
@Command(name = "help", description = "Show Synesis command help; --all lists every command.")
public final class HelpCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Option(names = "--all", description = "List every command, grouped, not only the everyday ones.")
    private boolean all;

    /**
     * Creates the help command.
     */
    public HelpCommand() {
    }

    /**
     * Prints root help.
     */
    @Override
    public void run() {
        var root = spec.commandLine().getParent();
        if (all) {
            ((RootCommand) root.getCommand()).listEveryCommand();
        }
        root.usage(spec.commandLine().getOut());
    }
}
