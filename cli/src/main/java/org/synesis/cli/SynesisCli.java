package org.synesis.cli;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.CheckActionCommand;
import org.synesis.cli.command.CleanupCommand;
import org.synesis.cli.command.ConstraintCommand;
import org.synesis.cli.command.ConstraintCreateCommand;
import org.synesis.cli.command.CoordinationDemoCommand;
import org.synesis.cli.command.CoordinationOperatorCommand;
import org.synesis.cli.command.CoordinationServeCommand;
import org.synesis.cli.command.CoordinationStatusCommand;
import org.synesis.cli.command.DoctorCommand;
import org.synesis.cli.command.EventsCommand;
import org.synesis.cli.command.EventsFollowCommand;
import org.synesis.cli.command.HelpCommand;
import org.synesis.cli.command.HookAntigravityCommand;
import org.synesis.cli.command.HookClaudeCodeCommand;
import org.synesis.cli.command.HookCodexCommand;
import org.synesis.cli.command.HookCommand;
import org.synesis.cli.command.HostCommand;
import org.synesis.cli.command.IdentityCommand;
import org.synesis.cli.command.IdentityShowCommand;
import org.synesis.cli.command.InitCommand;
import org.synesis.cli.command.IntegrationCommand;
import org.synesis.cli.command.IntegrationGateCommand;
import org.synesis.cli.command.JoinCommand;
import org.synesis.cli.command.McpCommand;
import org.synesis.cli.command.OwnershipClaimCommand;
import org.synesis.cli.command.OwnershipCommand;
import org.synesis.cli.command.OwnershipReleaseCommand;
import org.synesis.cli.command.OwnershipShowCommand;
import org.synesis.cli.command.PredictionCommand;
import org.synesis.cli.command.PredictionCreateCommand;
import org.synesis.cli.command.PredictionListCommand;
import org.synesis.cli.command.PredictionPublishCommand;
import org.synesis.cli.command.PredictionRespondCommand;
import org.synesis.cli.command.PredictionShowCommand;
import org.synesis.cli.command.ProjectCommand;
import org.synesis.cli.command.ProjectCreateCommand;
import org.synesis.cli.command.ProviderCommand;
import org.synesis.cli.command.ProviderInstallCommand;
import org.synesis.cli.command.ProviderListCommand;
import org.synesis.cli.command.ProviderMigrateCommand;
import org.synesis.cli.command.ProviderStatusCommand;
import org.synesis.cli.command.ProviderUninstallCommand;
import org.synesis.cli.command.RootCommand;
import org.synesis.cli.command.SpeculationCommand;
import org.synesis.cli.command.SpeculationInvalidateCommand;
import org.synesis.cli.command.SpeculationPrepareCommand;
import org.synesis.cli.command.SpeculationRetireCommand;
import org.synesis.cli.command.SpeculationValidateCommand;
import org.synesis.cli.command.SupervisorCommand;
import org.synesis.cli.command.SupervisorRunCommand;
import org.synesis.cli.command.SupervisorStatusCommand;
import org.synesis.cli.command.SyncCommand;
import org.synesis.cli.command.SyncHostCommand;
import org.synesis.cli.command.SyncJoinCommand;
import org.synesis.cli.command.TaskClaimCommand;
import org.synesis.cli.command.TaskCommand;
import org.synesis.cli.command.TaskCreateCommand;
import org.synesis.cli.command.TaskShowCommand;
import org.synesis.cli.command.VersionPlaceholderCommand;
import org.synesis.cli.command.WorkspaceCommand;
import org.synesis.cli.command.WorkspaceMutateCommand;
import org.synesis.cli.command.WorkspaceVerifyCommand;
import org.synesis.cli.command.MigrateCommand;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.cli.terminal.ConsoleTerminal;
import picocli.CommandLine;

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
        CommandLine identity = command.getSubcommands()
                .get("identity");
        identity.addSubcommand("show", new IdentityShowCommand(runtime));
        command.addSubcommand("doctor", new DoctorCommand(runtime));
        command.addSubcommand("migrate", new MigrateCommand(runtime));
        command.addSubcommand("cleanup", new CleanupCommand(runtime));
        command.addSubcommand("reconcile", new org.synesis.cli.command.ReconcileCommand(runtime));
        command.addSubcommand("repair", new org.synesis.cli.command.RepairCommand(runtime));
        command.addSubcommand("provider", new ProviderCommand());
        command.getSubcommands()
                .get("provider")
                .addSubcommand("list", new ProviderListCommand(runtime));
        command.getSubcommands()
                .get("provider")
                .addSubcommand("install", new ProviderInstallCommand(runtime));
        command.getSubcommands()
                .get("provider")
                .addSubcommand("status", new ProviderStatusCommand(runtime));
        command.getSubcommands()
                .get("provider")
                .addSubcommand("uninstall", new ProviderUninstallCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("migrate", new ProviderMigrateCommand(runtime));
        CommandLine workspace = new CommandLine(new WorkspaceCommand())
                .addSubcommand("verify", new WorkspaceVerifyCommand(runtime))
                .addSubcommand("mutate", new WorkspaceMutateCommand(runtime));
        command.addSubcommand("workspace", workspace);
        command.addSubcommand("mcp", new McpCommand(runtime));
        command.addSubcommand("help", new HelpCommand());
        command.addSubcommand("version", new VersionPlaceholderCommand(runtime));
        command.addSubcommand("init", new InitCommand(runtime));
        command.addSubcommand("project", new ProjectCommand());
        command.getSubcommands()
                .get("project")
                .addSubcommand("create", new ProjectCreateCommand(runtime));
        command.addSubcommand("constraint", new ConstraintCommand());
        command.getSubcommands()
                .get("constraint")
                .addSubcommand("create", new ConstraintCreateCommand(runtime));
        command.addSubcommand("sync", new SyncCommand());
        command.getSubcommands()
                .get("sync")
                .addSubcommand("host", new SyncHostCommand(runtime));
        command.getSubcommands()
                .get("sync")
                .addSubcommand("join", new SyncJoinCommand(runtime));
        command.addSubcommand("check-action", new CheckActionCommand(runtime));
        command.addSubcommand("coordination-demo", new CoordinationDemoCommand());
        command.addSubcommand("coordination", new CoordinationOperatorCommand());
        command.getSubcommands()
                .get("coordination")
                .addSubcommand("serve", new CoordinationServeCommand(runtime));
        command.getSubcommands()
                .get("coordination")
                .addSubcommand("status", new CoordinationStatusCommand(runtime));
        command.addSubcommand("task", new TaskCommand());
        command.getSubcommands()
                .get("task")
                .addSubcommand("create", new TaskCreateCommand(runtime));
        command.getSubcommands()
                .get("task")
                .addSubcommand("claim", new TaskClaimCommand(runtime));
        command.getSubcommands()
                .get("task")
                .addSubcommand("show", new TaskShowCommand(runtime));
        command.addSubcommand("ownership", new OwnershipCommand());
        command.getSubcommands()
                .get("ownership")
                .addSubcommand("claim", new OwnershipClaimCommand(runtime));
        command.getSubcommands()
                .get("ownership")
                .addSubcommand("show", new OwnershipShowCommand(runtime));
        command.getSubcommands()
                .get("ownership")
                .addSubcommand("release", new OwnershipReleaseCommand(runtime));
        command.addSubcommand("supervisor", new SupervisorCommand());
        command.getSubcommands()
                .get("supervisor")
                .addSubcommand("run", new SupervisorRunCommand(runtime));
        command.getSubcommands()
                .get("supervisor")
                .addSubcommand("status", new SupervisorStatusCommand(runtime));
        command.addSubcommand("events", new EventsCommand());
        command.getSubcommands()
                .get("events")
                .addSubcommand("follow", new EventsFollowCommand(runtime));
        command.addSubcommand("prediction", new PredictionCommand());
        command.getSubcommands()
                .get("prediction")
                .addSubcommand("create", new PredictionCreateCommand(runtime));
        command.getSubcommands()
                .get("prediction")
                .addSubcommand("show", new PredictionShowCommand(runtime));
        command.getSubcommands()
                .get("prediction")
                .addSubcommand("list", new PredictionListCommand(runtime));
        command.getSubcommands()
                .get("prediction")
                .addSubcommand("respond", new PredictionRespondCommand(runtime));
        command.getSubcommands()
                .get("prediction")
                .addSubcommand("publish", new PredictionPublishCommand(runtime));
        command.addSubcommand("speculation", new SpeculationCommand());
        command.getSubcommands()
                .get("speculation")
                .addSubcommand("prepare", new SpeculationPrepareCommand(runtime));
        command.getSubcommands()
                .get("speculation")
                .addSubcommand("validate", new SpeculationValidateCommand(runtime));
        command.getSubcommands()
                .get("speculation")
                .addSubcommand("retire", new SpeculationRetireCommand(runtime));
        command.getSubcommands()
                .get("speculation")
                .addSubcommand("invalidate", new SpeculationInvalidateCommand(runtime));
        command.addSubcommand("integration", new IntegrationCommand());
        command.getSubcommands()
                .get("integration")
                .addSubcommand("gate", new IntegrationGateCommand(runtime));
        command.addSubcommand("hook", new HookCommand());
        command.getSubcommands()
                .get("hook")
                .addSubcommand("antigravity", new HookAntigravityCommand(runtime));
        command.getSubcommands()
                .get("hook")
                .addSubcommand("claude-code", new HookClaudeCodeCommand(runtime));
        command.getSubcommands()
                .get("hook")
                .addSubcommand("codex", new HookCodexCommand(runtime));
        command.setOut(runtime.terminal()
                .out());
        command.setErr(runtime.terminal()
                .err());
        command.setParameterExceptionHandler((exception, _) -> {
            runtime.terminal()
                    .stderr("Usage error: " + exception.getMessage());
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
    public static void main(String[] arguments) {
        System.exit(execute(arguments, CliRuntime.defaults(new ConsoleTerminal())));
    }
}
