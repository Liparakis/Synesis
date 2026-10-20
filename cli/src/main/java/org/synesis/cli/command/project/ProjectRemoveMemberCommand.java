package org.synesis.cli.command.project;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.workspace.transport.control.ProjectSharing;

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
    boolean change(Role role, String node) throws Exception {
        try {
            ProjectSharing.removeMember(role.location(), role.coordination(), node);
        } catch (ProjectSharing.Refusal refusal) {
            throw new Refusal(refusal.code());
        }
        return true;
    }
}
