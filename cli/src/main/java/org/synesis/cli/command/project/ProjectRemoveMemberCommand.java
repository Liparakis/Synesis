package org.synesis.cli.command.project;

import org.synesis.coordination.application.MembershipService;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;

/**
 * Stops serving a member. Its entries stay on the log; it can no longer propose or pull.
 */
@Command(name = "remove-member", description = "Remove a computer from this project; its open work ends (owner only).", mixinStandardHelpOptions = true)
public final class ProjectRemoveMemberCommand extends ProjectMemberCommand {

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectRemoveMemberCommand(CliRuntime runtime) {
        super(runtime);
    }

    @Override
    boolean change(MembershipService membership, String node) throws Exception {
        if (!membership.isMember(node)) {
            throw new Refusal("NOT_A_MEMBER");
        }
        membership.remove(node);
        return true;
    }
}
