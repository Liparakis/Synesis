package org.synesis.cli.command.project;

import java.util.LinkedHashSet;
import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Parameters;

/**
 * Changes the list of computers the leader serves. The list is local state of the leader in step 2 (ADR-0098); the
 * running leader reads it for every request, so a change needs no restart.
 */
abstract class ProjectMemberCommand extends ProjectRoleCommand {

    @Parameters(index = "0", arity = "1", description = "Node ID of the member ('synesis identity show --project' on its computer).")
    private String member;

    ProjectMemberCommand(CliRuntime runtime) {
        super(runtime, DAEMON);
    }

    /**
     * Applies the change to the member set.
     *
     * @param members the current members, which this may change
     * @param node    the member the command names
     * @return whether the set changed
     * @throws Refusal when the change is not allowed
     */
    abstract boolean change(Set<String> members, String node) throws Refusal;

    @Override
    final void apply(Role role) throws Exception {
        if (!NodeIdentity.isNodeId(member)) {
            throw new Refusal("INVALID_NODE_ID");
        }
        ReplicationSettings leader = role.leader();
        if (member.equals(role.location().loadOrCreateIdentity().nodeId())) {
            throw new Refusal("MEMBER_IS_THIS_COMPUTER");
        }
        Set<String> members = new LinkedHashSet<>(leader.members());
        boolean changed = change(members, member);
        if (changed) {
            role.write(new ReplicationSettings(null, members, leader.codeRemote()));
        }
        runtime.terminal().stdout("ROLE=leader");
        runtime.terminal().stdout("MEMBER_NODE_ID=" + member);
        runtime.terminal().stdout("CHANGED=" + changed);
        runtime.terminal().stdout("MEMBERS=" + members.size());
    }

    @Override
    final boolean needsRuntimeRestart() {
        return false;
    }
}
