package org.synesis.cli.command.project;

import org.synesis.coordination.application.MembershipService;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;

/**
 * Allows a computer to follow this project as a member.
 */
@Command(name = "add-member", description = "Let a computer into this project by its node ID (the owner, or any member when the project allows it).", mixinStandardHelpOptions = true)
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
    boolean change(MembershipService membership, String node) throws Exception {
        // A computer let in by name: shared by this computer if it is not yet, asked for and accepted at once.
        membership.share(java.util.Optional.empty());
        if (membership.isMember(node)) {
            return false;
        }
        membership.requestJoin(node);
        membership.answer(node, true);
        return true;
    }
}
