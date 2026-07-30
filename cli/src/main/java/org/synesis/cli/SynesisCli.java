package org.synesis.cli;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.workspace.CheckActionCommand;
import org.synesis.cli.command.lifecycle.CleanupCommand;
import org.synesis.cli.command.ConstraintCommand;
import org.synesis.cli.command.ConstraintCreateCommand;
import org.synesis.cli.command.collaboration.CollaborationAnnounceCommand;
import org.synesis.cli.command.collaboration.CollaborationAcknowledgeCommand;
import org.synesis.cli.command.collaboration.CollaborationCommand;
import org.synesis.cli.command.collaboration.CollaborationStatusCommand;
import org.synesis.cli.command.collaboration.CollaborationReleaseCommand;
import org.synesis.cli.command.collaboration.CollaborationRequestCommand;
import org.synesis.cli.command.collaboration.CollaborationRespondCommand;
import org.synesis.cli.command.collaboration.CollaborationHandoffCommand;
import org.synesis.cli.command.collaboration.CollaborationContractCommand;
import org.synesis.cli.command.collaboration.CollaborationReadinessCommand;
import org.synesis.cli.command.coordination.CoordinationDemoCommand;
import org.synesis.cli.command.coordination.CoordinationOperatorCommand;
import org.synesis.cli.command.coordination.CoordinationServeCommand;
import org.synesis.cli.command.coordination.CoordinationStatusCommand;
import org.synesis.cli.command.lifecycle.DoctorCommand;
import org.synesis.cli.command.coordination.EventsCommand;
import org.synesis.cli.command.coordination.EventsFollowCommand;
import org.synesis.cli.command.HelpCommand;
import org.synesis.cli.command.hook.HookAntigravityCommand;
import org.synesis.cli.command.hook.HookClaudeCodeCommand;
import org.synesis.cli.command.hook.HookCodexCommand;
import org.synesis.cli.command.hook.HookCommand;
import org.synesis.cli.command.sync.HostCommand;
import org.synesis.cli.command.identity.IdentityCommand;
import org.synesis.cli.command.identity.IdentityShowCommand;
import org.synesis.cli.command.project.InitCommand;
import org.synesis.cli.command.lifecycle.IntegrationCommand;
import org.synesis.cli.command.lifecycle.IntegrationGateCommand;
import org.synesis.cli.command.sync.JoinCommand;
import org.synesis.cli.command.McpCommand;
import org.synesis.cli.command.ownership.OwnershipClaimCommand;
import org.synesis.cli.command.ownership.OwnershipCommand;
import org.synesis.cli.command.ownership.OwnershipReleaseCommand;
import org.synesis.cli.command.ownership.OwnershipShowCommand;
import org.synesis.cli.command.prediction.PredictionCommand;
import org.synesis.cli.command.prediction.PredictionCreateCommand;
import org.synesis.cli.command.prediction.PredictionListCommand;
import org.synesis.cli.command.prediction.PredictionPublishCommand;
import org.synesis.cli.command.prediction.PredictionRespondCommand;
import org.synesis.cli.command.prediction.PredictionShowCommand;
import org.synesis.cli.command.project.ProjectCommand;
import org.synesis.cli.command.project.ProjectCreateCommand;
import org.synesis.cli.command.provider.ProviderCommand;
import org.synesis.cli.command.provider.ProviderInstallCommand;
import org.synesis.cli.command.provider.ProviderListCommand;
import org.synesis.cli.command.provider.ProviderMigrateCommand;
import org.synesis.cli.command.provider.ProviderStatusCommand;
import org.synesis.cli.command.provider.ProviderUninstallCommand;
import org.synesis.cli.command.RootCommand;
import org.synesis.cli.command.speculation.SpeculationCommand;
import org.synesis.cli.command.speculation.SpeculationInvalidateCommand;
import org.synesis.cli.command.speculation.SpeculationPrepareCommand;
import org.synesis.cli.command.speculation.SpeculationRetireCommand;
import org.synesis.cli.command.speculation.SpeculationValidateCommand;
import org.synesis.cli.command.lifecycle.SupervisorCommand;
import org.synesis.cli.command.lifecycle.SupervisorRunCommand;
import org.synesis.cli.command.lifecycle.SupervisorStatusCommand;
import org.synesis.cli.command.sync.SyncCommand;
import org.synesis.cli.command.sync.SyncHostCommand;
import org.synesis.cli.command.sync.SyncJoinCommand;
import org.synesis.cli.command.task.TaskClaimCommand;
import org.synesis.cli.command.task.TaskCommand;
import org.synesis.cli.command.task.TaskCreateCommand;
import org.synesis.cli.command.task.TaskShowCommand;
import org.synesis.cli.command.VersionPlaceholderCommand;
import org.synesis.cli.command.workspace.WorkspaceCommand;
import org.synesis.cli.command.workspace.WorkspaceMutateCommand;
import org.synesis.cli.command.workspace.WorkspaceVerifyCommand;
import org.synesis.cli.command.lifecycle.MigrateCommand;
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
        command.addSubcommand("reconcile", new org.synesis.cli.command.lifecycle.ReconcileCommand(runtime));
        command.addSubcommand("repair", new org.synesis.cli.command.lifecycle.RepairCommand(runtime));
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
        CommandLine collaboration = new CommandLine(new CollaborationCommand())
                .addSubcommand("announce", new CollaborationAnnounceCommand(runtime))
                .addSubcommand("acknowledge", new CollaborationAcknowledgeCommand(runtime))
                .addSubcommand("status", new CollaborationStatusCommand(runtime))
                .addSubcommand("release", new CollaborationReleaseCommand(runtime))
                .addSubcommand("request", new CollaborationRequestCommand(runtime))
                .addSubcommand("respond", new CollaborationRespondCommand(runtime))
                .addSubcommand("handoff", new CollaborationHandoffCommand(runtime))
                .addSubcommand("contract", new CollaborationContractCommand(runtime))
                .addSubcommand("readiness", new CollaborationReadinessCommand(runtime));
        command.addSubcommand("collaboration", collaboration);
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
