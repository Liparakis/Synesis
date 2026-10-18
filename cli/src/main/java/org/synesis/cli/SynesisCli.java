package org.synesis.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.agents.AgentsCommand;
import org.synesis.cli.command.agents.AgentsRunCommand;
import org.synesis.cli.command.ConstraintCommand;
import org.synesis.cli.command.ConstraintCreateCommand;
import org.synesis.cli.command.DaemonCommand;
import org.synesis.cli.command.HelpCommand;
import org.synesis.cli.command.McpCommand;
import org.synesis.cli.command.OpenCommand;
import org.synesis.cli.command.RootCommand;
import org.synesis.cli.command.VersionCommand;
import org.synesis.cli.command.collaboration.CollaborationAcknowledgeCommand;
import org.synesis.cli.command.collaboration.CollaborationAnnounceCommand;
import org.synesis.cli.command.collaboration.CollaborationCommand;
import org.synesis.cli.command.collaboration.CollaborationContractCommand;
import org.synesis.cli.command.collaboration.CollaborationContractStatusCommand;
import org.synesis.cli.command.collaboration.CollaborationHandoffCommand;
import org.synesis.cli.command.collaboration.CollaborationReadinessCommand;
import org.synesis.cli.command.collaboration.CollaborationReleaseCommand;
import org.synesis.cli.command.collaboration.CollaborationRequestCommand;
import org.synesis.cli.command.collaboration.CollaborationRespondCommand;
import org.synesis.cli.command.collaboration.CollaborationStatusCommand;
import org.synesis.cli.command.coordination.CoordinationOperatorCommand;
import org.synesis.cli.command.coordination.CoordinationServeCommand;
import org.synesis.cli.command.coordination.CoordinationStatusCommand;
import org.synesis.cli.command.coordination.EventsCommand;
import org.synesis.cli.command.coordination.EventsFollowCommand;
import org.synesis.cli.command.coordination.UiCommand;
import org.synesis.cli.command.hook.HookClaudeCodeCommand;
import org.synesis.cli.command.hook.HookCodexCommand;
import org.synesis.cli.command.hook.HookCommand;
import org.synesis.cli.command.identity.IdentityCommand;
import org.synesis.cli.command.identity.IdentityShowCommand;
import org.synesis.cli.command.lifecycle.CleanupCommand;
import org.synesis.cli.command.lifecycle.DoctorCommand;
import org.synesis.cli.command.lifecycle.IntegrationCommand;
import org.synesis.cli.command.lifecycle.IntegrationGateCommand;
import org.synesis.cli.command.lifecycle.MigrateCommand;
import org.synesis.cli.command.lifecycle.SupervisorCommand;
import org.synesis.cli.command.lifecycle.SupervisorRunCommand;
import org.synesis.cli.command.lifecycle.SupervisorStatusCommand;
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
import org.synesis.cli.command.project.InitCommand;
import org.synesis.cli.command.link.LinkAcceptCommand;
import org.synesis.cli.command.link.LinkAnswerCommand;
import org.synesis.cli.command.link.LinkDeclineCommand;
import org.synesis.cli.command.link.LinkRequestsCommand;
import org.synesis.cli.command.link.LinkCommand;
import org.synesis.cli.command.link.LinkInviteCommand;
import org.synesis.cli.command.link.LinkJoinCommand;
import org.synesis.cli.command.project.ProjectAddMemberCommand;
import org.synesis.cli.command.project.ProjectCommand;
import org.synesis.cli.command.project.ProjectFollowCommand;
import org.synesis.cli.command.project.ProjectRemoveMemberCommand;
import org.synesis.cli.command.project.ProjectRecoverCommand;
import org.synesis.cli.command.project.ProjectJoinPolicyCommand;
import org.synesis.cli.command.project.ProjectShareCommand;
import org.synesis.cli.command.project.ProjectCreateCommand;
import org.synesis.cli.command.project.ProjectValidationClearCommand;
import org.synesis.cli.command.project.ProjectValidationCommand;
import org.synesis.cli.command.project.ProjectValidationSetCommand;
import org.synesis.cli.command.project.ProjectValidationShowCommand;
import org.synesis.cli.command.provider.ProviderCommand;
import org.synesis.cli.command.provider.ProviderInstallCommand;
import org.synesis.cli.command.provider.ProviderListCommand;
import org.synesis.cli.command.provider.ProviderMigrateCommand;
import org.synesis.cli.command.provider.ProviderStatusCommand;
import org.synesis.cli.command.provider.ProviderUninstallCommand;
import org.synesis.cli.command.speculation.SpeculationCommand;
import org.synesis.cli.command.speculation.SpeculationInvalidateCommand;
import org.synesis.cli.command.speculation.SpeculationPrepareCommand;
import org.synesis.cli.command.speculation.SpeculationRetireCommand;
import org.synesis.cli.command.speculation.SpeculationValidateCommand;
import org.synesis.cli.command.sync.HostCommand;
import org.synesis.cli.command.sync.JoinCommand;
import org.synesis.cli.command.sync.SyncCommand;
import org.synesis.cli.command.sync.SyncHostCommand;
import org.synesis.cli.command.sync.SyncJoinCommand;
import org.synesis.cli.command.task.TaskClaimCommand;
import org.synesis.cli.command.task.TaskCommand;
import org.synesis.cli.command.task.TaskCreateCommand;
import org.synesis.cli.command.task.TaskShowCommand;
import org.synesis.cli.command.workspace.CheckActionCommand;
import org.synesis.cli.command.workspace.WorkspaceCommand;
import org.synesis.cli.command.workspace.WorkspaceMutateCommand;
import org.synesis.cli.command.workspace.WorkspaceVerifyCommand;
import org.synesis.cli.daemon.CliChildJvm;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.HumanTerminal;
import org.synesis.cli.terminal.Terminal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/**
 * Executable Picocli wiring for the standalone Synesis CLI.
 */
public final class SynesisCli {

    private static final Map<String, List<String>> COMMAND_GROUPS = commandGroups();
    private static final List<String> PLUMBING_COMMANDS = List.of("check-action", "daemon", "hook", "integration", "mcp", "open", "prediction", "speculation", "supervisor", "workspace");

    private SynesisCli() {
    }

    private static Map<String, List<String>> commandGroups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("Set up", List.of("init", "provider", "doctor", "ui", "version", "help"));
        groups.put("Work with another person", List.of("host", "join", "sync", "identity"));
        groups.put("Coordinate work", List.of("agents", "collaboration", "task", "ownership", "constraint", "events", "coordination"));
        groups.put("Maintain a project", List.of("operator", "cleanup", "reconcile", "repair", "migrate", "project"));
        return groups;
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
        command.addSubcommand("daemon", new DaemonCommand());
        command.addSubcommand("open", new OpenCommand(runtime));
        command.addSubcommand("migrate", new MigrateCommand(runtime));
        command.addSubcommand("cleanup", new CleanupCommand(runtime));
        command.addSubcommand("reconcile", new org.synesis.cli.command.lifecycle.ReconcileCommand(runtime));
        command.addSubcommand("repair", new org.synesis.cli.command.lifecycle.RepairCommand(runtime));
        command.addSubcommand("provider", new ProviderCommand());
        command.getSubcommands().get("provider").addSubcommand("list", new ProviderListCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("install", new ProviderInstallCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("status", new ProviderStatusCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("uninstall", new ProviderUninstallCommand(runtime));
        command.getSubcommands().get("provider").addSubcommand("migrate", new ProviderMigrateCommand(runtime));
        CommandLine workspace = new CommandLine(new WorkspaceCommand()).addSubcommand("verify", new WorkspaceVerifyCommand(runtime)).addSubcommand("mutate", new WorkspaceMutateCommand(runtime));
        command.addSubcommand("workspace", workspace);
        command.addSubcommand("mcp", new McpCommand(runtime));
        command.addSubcommand("help", new HelpCommand());
        command.addSubcommand("version", new VersionCommand(runtime));
        command.addSubcommand("init", new InitCommand(runtime));
        command.addSubcommand("project", new ProjectCommand());
        command.getSubcommands().get("project").addSubcommand("create", new ProjectCreateCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("share", new ProjectShareCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("add-member", new ProjectAddMemberCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("remove-member", new ProjectRemoveMemberCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("recover", new ProjectRecoverCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("join-policy", new ProjectJoinPolicyCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("follow", new ProjectFollowCommand(runtime));
        command.getSubcommands().get("project").addSubcommand("validation", new CommandLine(new ProjectValidationCommand()).addSubcommand("set", new ProjectValidationSetCommand(runtime))
                .addSubcommand("show", new ProjectValidationShowCommand(runtime)).addSubcommand("clear", new ProjectValidationClearCommand(runtime)));
        command.addSubcommand("link", new LinkCommand());
        command.getSubcommands().get("link").addSubcommand("invite", new LinkInviteCommand(runtime));
        command.getSubcommands().get("link").addSubcommand("join", new LinkJoinCommand(runtime));
        command.getSubcommands().get("link").addSubcommand("answer", new LinkAnswerCommand(runtime));
        command.getSubcommands().get("link").addSubcommand("requests", new LinkRequestsCommand(runtime));
        command.getSubcommands().get("link").addSubcommand("accept", new LinkAcceptCommand(runtime));
        command.getSubcommands().get("link").addSubcommand("decline", new LinkDeclineCommand(runtime));
        command.addSubcommand("constraint", new ConstraintCommand());
        command.getSubcommands().get("constraint").addSubcommand("create", new ConstraintCreateCommand(runtime));
        CommandLine collaboration = new CommandLine(new CollaborationCommand()).addSubcommand("announce", new CollaborationAnnounceCommand(runtime))
                .addSubcommand("acknowledge", new CollaborationAcknowledgeCommand(runtime)).addSubcommand("status", new CollaborationStatusCommand(runtime))
                .addSubcommand("release", new CollaborationReleaseCommand(runtime)).addSubcommand("request", new CollaborationRequestCommand(runtime)).addSubcommand("respond", new CollaborationRespondCommand(runtime))
                .addSubcommand("handoff", new CollaborationHandoffCommand(runtime))
                .addSubcommand("contract", new CommandLine(new CollaborationContractCommand(runtime)).addSubcommand("status", new CollaborationContractStatusCommand(runtime)))
                .addSubcommand("readiness", new CollaborationReadinessCommand(runtime));
        command.addSubcommand("collaboration", collaboration);
        command.addSubcommand("sync", new SyncCommand());
        command.getSubcommands().get("sync").addSubcommand("host", new SyncHostCommand(runtime));
        command.getSubcommands().get("sync").addSubcommand("join", new SyncJoinCommand(runtime));
        command.addSubcommand("check-action", new CheckActionCommand(runtime));
        command.addSubcommand("coordination", new CoordinationOperatorCommand());
        command.getSubcommands().get("coordination").addSubcommand("serve", new CoordinationServeCommand(runtime));
        command.addSubcommand("ui", new UiCommand(runtime));
        command.getSubcommands().get("coordination").addSubcommand("status", new CoordinationStatusCommand(runtime));
        command.addSubcommand("task", new TaskCommand());
        command.getSubcommands().get("task").addSubcommand("create", new TaskCreateCommand(runtime));
        command.getSubcommands().get("task").addSubcommand("claim", new TaskClaimCommand(runtime));
        command.getSubcommands().get("task").addSubcommand("show", new TaskShowCommand(runtime));
        command.addSubcommand("ownership", new OwnershipCommand());
        command.getSubcommands().get("ownership").addSubcommand("claim", new OwnershipClaimCommand(runtime));
        command.getSubcommands().get("ownership").addSubcommand("show", new OwnershipShowCommand(runtime));
        command.getSubcommands().get("ownership").addSubcommand("release", new OwnershipReleaseCommand(runtime));
        command.addSubcommand("supervisor", new SupervisorCommand());
        command.getSubcommands().get("supervisor").addSubcommand("run", new SupervisorRunCommand(runtime));
        command.getSubcommands().get("supervisor").addSubcommand("status", new SupervisorStatusCommand(runtime));
        command.addSubcommand("events", new EventsCommand());
        command.getSubcommands().get("events").addSubcommand("follow", new EventsFollowCommand(runtime));
        command.addSubcommand("prediction", new PredictionCommand());
        command.getSubcommands().get("prediction").addSubcommand("create", new PredictionCreateCommand(runtime));
        command.getSubcommands().get("prediction").addSubcommand("show", new PredictionShowCommand(runtime));
        command.getSubcommands().get("prediction").addSubcommand("list", new PredictionListCommand(runtime));
        command.getSubcommands().get("prediction").addSubcommand("respond", new PredictionRespondCommand(runtime));
        command.getSubcommands().get("prediction").addSubcommand("publish", new PredictionPublishCommand(runtime));
        command.addSubcommand("speculation", new SpeculationCommand());
        command.getSubcommands().get("speculation").addSubcommand("prepare", new SpeculationPrepareCommand(runtime));
        command.getSubcommands().get("speculation").addSubcommand("validate", new SpeculationValidateCommand(runtime));
        command.getSubcommands().get("speculation").addSubcommand("retire", new SpeculationRetireCommand(runtime));
        command.getSubcommands().get("speculation").addSubcommand("invalidate", new SpeculationInvalidateCommand(runtime));
        command.addSubcommand("integration", new IntegrationCommand());
        command.getSubcommands().get("integration").addSubcommand("gate", new IntegrationGateCommand(runtime));
        command.addSubcommand("agents", new AgentsCommand());
        command.getSubcommands().get("agents").addSubcommand("run", new AgentsRunCommand(runtime));
        command.addSubcommand("operator", new org.synesis.cli.command.operator.OperatorCommand());
        command.getSubcommands().get("operator").addSubcommand("list", new org.synesis.cli.command.operator.OperatorListCommand(runtime));
        command.getSubcommands().get("operator").addSubcommand("recheck", new org.synesis.cli.command.operator.OperatorRecheckCommand(runtime));
        command.getSubcommands().get("operator").addSubcommand("close", new org.synesis.cli.command.operator.OperatorCloseCommand(runtime));
        command.addSubcommand("conversations", new org.synesis.cli.command.conversation.ConversationsCommand(runtime));
        command.addSubcommand("hook", new HookCommand());
        command.getSubcommands().get("hook").addSubcommand("claude", new HookClaudeCodeCommand(runtime));
        command.getSubcommands().get("hook").addSubcommand("codex", new HookCodexCommand(runtime));
        groupRootCommands(command);
        configureHandlers(command, runtime);
        try {
            return command.execute(arguments);
        } catch (RuntimeException failure) {
            return FailureMapper.internal(runtime.terminal());
        }
    }

    /**
     * Lists root commands by what a person is trying to do instead of in registration order.
     *
     * <p>Commands that only agents and integrations call are named on one line so they stay
     * discoverable without crowding the everyday ones. A command missing from every group is
     * still listed, so registering a new command can never hide it from help.</p>
     */
    static void groupRootCommands(CommandLine command) {
        command.getHelpSectionMap().put(CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING, _ -> "");
        command.getHelpSectionMap().put(CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST, help -> {
            Map<String, CommandLine.Help> remaining = new LinkedHashMap<>(help.subcommands());
            StringBuilder text = new StringBuilder();
            COMMAND_GROUPS.forEach((heading, names) -> {
                Map<String, CommandLine.Help> members = new LinkedHashMap<>();
                names.forEach(name -> {
                    CommandLine.Help member = remaining.remove(name);
                    if (member != null) {
                        members.put(name, member);
                    }
                });
                if (!members.isEmpty()) {
                    text.append(String.format("%n%s:%n", heading)).append(help.commandList(members));
                }
            });
            List<String> plumbing = PLUMBING_COMMANDS.stream().filter(name -> remaining.remove(name) != null).toList();
            if (!remaining.isEmpty()) {
                text.append(String.format("%nOther commands:%n")).append(help.commandList(remaining));
            }
            if (!plumbing.isEmpty()) {
                text.append(String.format("%nCalled by agents and integrations:%n  %s%n", String.join(", ", plumbing)));
            }
            return text.toString();
        });
    }

    static void configureHandlers(CommandLine command, CliRuntime runtime) {
        command.setOut(runtime.terminal().out());
        command.setErr(runtime.terminal().err());
        command.setParameterExceptionHandler((exception, _) -> {
            CommandLine failed = exception.getCommandLine();
            if (runtime.terminal() instanceof HumanTerminal) {
                // A person can act on the parser's own explanation and on near-miss suggestions;
                // the machine contract below stays a single stable line.
                runtime.terminal().stderr(exception.getMessage());
                if (!CommandLine.UnmatchedArgumentException.printSuggestions(exception, runtime.terminal().err())) {
                    runtime.terminal().stderr("Run '" + failed.getCommandSpec().qualifiedName() + " --help' for valid commands and options.");
                }
                return ExitCodes.USAGE;
            }
            runtime.terminal().stderr("Usage error in '" + failed.getCommandName() + "'. Run 'synesis help' for valid commands and options.");
            return ExitCodes.USAGE;
        });
        command.setExecutionExceptionHandler((exception, _, _) -> FailureMapper.internal(runtime.terminal()));
        command.getSubcommands().values().forEach(subcommand -> configureHandlers(subcommand, runtime));
    }

    /**
     * Configures the Windows JDK loopback socket directory when the launcher did not provide one.
     */
    static void configureWindowsLoopbackSocketDirectory() {
        if (!CliChildJvm.onWindows() || !System.getProperty("jdk.net.unixdomain.tmpdir", "").isBlank()) {
            return;
        }
        String publicDirectory = System.getenv("PUBLIC");
        Path directory = publicDirectory == null || publicDirectory.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"), "s") : Path.of(publicDirectory, "s");
        try {
            Files.createDirectories(directory);
            System.setProperty("jdk.net.unixdomain.tmpdir", directory.toString());
        } catch (IOException ignored) {
            // Preserve the JDK's normal failure behavior if the fallback directory cannot be created.
        }
    }

    /**
     * Runs the process entry point and exits with the command result.
     *
     * @param arguments process arguments
     */
    public static void main(String[] arguments) {
        configureWindowsLoopbackSocketDirectory();
        Terminal terminal = new ConsoleTerminal();
        java.io.Console console = System.console();
        if (HumanTerminal.selected(List.of(arguments), console != null && console.isTerminal(), System.getenv(HumanTerminal.OUTPUT_VARIABLE))) {
            terminal = new HumanTerminal(terminal, HumanTerminal.colorSupported(System.getenv()));
        }
        System.exit(execute(arguments, CliRuntime.defaults(terminal)));
    }
}
