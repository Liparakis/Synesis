package org.synesis.cli.command.project;

import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;

/**
 * Allows a computer to follow this project as a member.
 */
@Command(name = "add-member", description = "Allow a computer to follow this project (leader only).", mixinStandardHelpOptions = true)
public final class ProjectAddMemberCommand extends ProjectMemberCommand {

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectAddMemberCommand(CliRuntime runtime) {
        super(runtime);
    }

    @Override
    boolean change(Set<String> members, String node) {
        return members.add(node);
    }
}
