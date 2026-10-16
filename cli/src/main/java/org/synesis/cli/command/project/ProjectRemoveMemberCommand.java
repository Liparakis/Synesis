package org.synesis.cli.command.project;

import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;

/**
 * Stops serving a member. Its entries stay on the log; it can no longer propose or pull.
 */
@Command(name = "remove-member", description = "Stop serving a member of this project (leader only).", mixinStandardHelpOptions = true)
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
    boolean change(Set<String> members, String node) throws Refusal {
        if (!members.remove(node)) {
            throw new Refusal("NOT_A_MEMBER");
        }
        return true;
    }
}
