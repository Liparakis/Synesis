package org.synesis.cli.command.project;

import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Parameters;

/**
 * Lets a computer in, or removes it, by an entry of this computer's log (ADR-0098 step 6): the running side reads the
 * membership for every request, so a change needs no restart. Removing is {@code ProjectSharing.removeMember}, the
 * same steps the project page takes, which also drops the node from {@code replication.peers}.
 */
abstract class ProjectMemberCommand extends ProjectRoleCommand {

    @Parameters(index = "0", arity = "1", description = "Node ID of the member ('synesis identity show --project' on its computer).")
    private String member;

    ProjectMemberCommand(CliRuntime runtime) {
        super(runtime, DAEMON);
    }

    /**
     * Checks the request and writes the change.
     *
     * @param role this project's role as found
     * @param node the member
     * @return whether anything was written
     * @throws Exception the refusal of the request or of the entry
     */
    abstract boolean change(Role role, String node) throws Exception;

    @Override
    final void apply(Role role) throws Exception {
        boolean changed = change(role, member);
        Set<String> members = role.location().openCoordinationStore().membershipProjection().members();
        runtime.terminal().stdout("ROLE=shared");
        runtime.terminal().stdout("MEMBER_NODE_ID=" + member);
        runtime.terminal().stdout("CHANGED=" + changed);
        runtime.terminal().stdout("MEMBERS=" + members.size());
    }

    @Override
    final boolean needsRuntimeRestart() {
        return false;
    }
}
