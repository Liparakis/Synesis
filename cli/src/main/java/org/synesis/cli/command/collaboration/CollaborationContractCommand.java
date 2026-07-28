package org.synesis.cli.command.collaboration;

import org.synesis.cli.bootstrap.CliRuntime;
import picocli.CommandLine.Command;

/** Parent command for shared contract operations. */
@Command(name = "contract", description = "Publish, bind, and inspect shared contracts.", mixinStandardHelpOptions = true,
        subcommands = {CollaborationContractPublishCommand.class, CollaborationContractBindCommand.class,
                CollaborationContractStatusCommand.class})
public final class CollaborationContractCommand implements Runnable {
    private final CliRuntime runtime;
    /** Creates the command.
     * @param runtime CLI runtime
     */
    public CollaborationContractCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Prints child help. */
    @Override public void run() { runtime.terminal().stdout("Use collaboration contract publish, bind, or status."); }
}
