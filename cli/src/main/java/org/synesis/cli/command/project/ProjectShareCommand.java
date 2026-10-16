package org.synesis.cli.command.project;

import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.persistence.ReplicationSettings;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Makes this computer the leader of the project, so that other computers can join it (ADR-0098 step 2).
 */
@Command(name = "share", description = "Make this computer the leader of the project, so members can follow it.", mixinStandardHelpOptions = true)
public final class ProjectShareCommand extends ProjectRoleCommand {

    @Option(names = "--code-remote", required = true, description = "Name of the Git remote of this clone that code moves through. Every computer must reach it.")
    private String codeRemote;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectShareCommand(CliRuntime runtime) {
        this(runtime, DAEMON);
    }

    ProjectShareCommand(CliRuntime runtime, RuntimeStopper stopper) {
        super(runtime, stopper);
    }

    @Override
    void apply(Role role) throws Exception {
        // A leader sharing again keeps its members; a computer that follows another one is refused.
        Set<String> members = role.settings().isPresent() ? role.leader().members() : Set.of();
        requireRemote(role, codeRemote);
        role.write(new ReplicationSettings(null, members, codeRemote));
        runtime.terminal().stdout("ROLE=leader");
        runtime.terminal().stdout("NODE_ID=" + role.location().loadOrCreateIdentity().nodeId());
        runtime.terminal().stdout("CODE_REMOTE=" + codeRemote);
        runtime.terminal().stdout("MEMBERS=" + members.size());
    }

    @Override
    boolean needsRuntimeRestart() {
        return true;
    }
}
