package org.synesis.cli;

import picocli.CommandLine;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.DoctorCommand;
import org.synesis.cli.command.HostCommand;
import org.synesis.cli.command.IdentityCommand;
import org.synesis.cli.command.IdentityShowCommand;
import org.synesis.cli.command.JoinCommand;
import org.synesis.cli.command.RootCommand;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.cli.terminal.ConsoleTerminal;

/**
 * Executable Picocli wiring for the standalone Synesis CLI.
 */
public final class SynesisCli {
    private SynesisCli() {
    }

    /**
     * Executes one invocation with an injectable runtime.
     *
     * @param arguments command-line arguments
     * @param runtime   manually composed runtime
     * @return stable process exit code
     */
    public static int execute(String[] arguments, CliRuntime runtime) {
        CommandLine command = new CommandLine(new RootCommand());
        command.addSubcommand("host", new HostCommand(runtime));
        command.addSubcommand("join", new JoinCommand(runtime));
        command.addSubcommand("identity", new IdentityCommand());
        CommandLine identity = command.getSubcommands().get("identity");
        identity.addSubcommand("show", new IdentityShowCommand(runtime));
        command.addSubcommand("doctor", new DoctorCommand(runtime));
        command.setOut(runtime.terminal().out());
        command.setErr(runtime.terminal().err());
        command.setParameterExceptionHandler((exception, _) -> {
            runtime.terminal().stderr("Usage error: " + exception.getMessage());
            return ExitCodes.USAGE;
        });
        try {
            return command.execute(arguments);
        } catch (RuntimeException failure) {
            return FailureMapper.internal(failure, runtime.terminal());
        }
    }

    /**
     * Runs the process entry point and exits with the command result.
     *
     * @param arguments process arguments
     */
    public static void main(String[] arguments) {
        System.exit(execute(arguments, CliRuntime.defaults(new ConsoleTerminal())));
    }
}
