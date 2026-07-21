package org.synesis.cli;

import picocli.CommandLine;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.DoctorCommand;
import org.synesis.cli.command.CheckActionCommand;
import org.synesis.cli.command.ConstraintCommand;
import org.synesis.cli.command.ConstraintCreateCommand;
import org.synesis.cli.command.HookAntigravityCommand;
import org.synesis.cli.command.HookClaudeCodeCommand;
import org.synesis.cli.command.HookCommand;
import org.synesis.cli.command.HelpCommand;
import org.synesis.cli.command.HostCommand;
import org.synesis.cli.command.IdentityCommand;
import org.synesis.cli.command.IdentityShowCommand;
import org.synesis.cli.command.InitCommand;
import org.synesis.cli.command.JoinCommand;
import org.synesis.cli.command.ProjectCommand;
import org.synesis.cli.command.ProjectCreateCommand;
import org.synesis.cli.command.ProviderCommand;
import org.synesis.cli.command.ProviderInstallCommand;
import org.synesis.cli.command.ProviderListCommand;
import org.synesis.cli.command.ProviderStatusCommand;
import org.synesis.cli.command.ProviderUninstallCommand;
import org.synesis.cli.command.RootCommand;
import org.synesis.cli.command.SyncCommand;
import org.synesis.cli.command.SyncHostCommand;
import org.synesis.cli.command.SyncJoinCommand;
import org.synesis.cli.command.VersionPlaceholderCommand;
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
        command.addSubcommand("provider", new ProviderCommand());
        command.getSubcommands().get("provider").addSubcommand("list", new ProviderListCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("install", new ProviderInstallCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("status", new ProviderStatusCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("uninstall", new ProviderUninstallCommand(runtime));
        command.addSubcommand("help", new HelpCommand());
        command.addSubcommand("version-placeholder", new VersionPlaceholderCommand(runtime));
        command.addSubcommand("init", new InitCommand(runtime));
        command.addSubcommand("project", new ProjectCommand());
        command.getSubcommands().get("project").addSubcommand("create", new ProjectCreateCommand(runtime));
        command.addSubcommand("constraint", new ConstraintCommand());
        command.getSubcommands().get("constraint").addSubcommand("create", new ConstraintCreateCommand(runtime));
        command.addSubcommand("sync", new SyncCommand());
        command.getSubcommands().get("sync").addSubcommand("host", new SyncHostCommand(runtime));
        command.getSubcommands().get("sync").addSubcommand("join", new SyncJoinCommand(runtime));
        command.addSubcommand("check-action", new CheckActionCommand(runtime));
        command.addSubcommand("hook", new HookCommand());
        command.getSubcommands().get("hook").addSubcommand("antigravity", new HookAntigravityCommand(runtime));
        command.getSubcommands().get("hook").addSubcommand("claude-code", new HookClaudeCodeCommand(runtime));
        command.setOut(runtime.terminal().out());
        command.setErr(runtime.terminal().err());
        command.setParameterExceptionHandler((exception, _) -> {
            runtime.terminal().stderr("Usage error: " + exception.getMessage());
            return ExitCodes.USAGE;
        });
        try {
            return command.execute(arguments);
        } catch (RuntimeException failure) {
            return FailureMapper.internal(runtime.terminal());
        }
    }

    /**
     * Runs the process entry point and exits with the command result.
     *
     * @param arguments process arguments
     */
    static void main(String[] arguments) {
        System.exit(execute(arguments, CliRuntime.defaults(new ConsoleTerminal())));
    }
}
