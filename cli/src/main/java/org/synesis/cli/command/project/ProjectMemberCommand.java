package org.synesis.cli.command.project;

import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.SharedSettingsMigration;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Parameters;

/**
 * Lets a computer in, or removes it, by an entry of this computer's log (ADR-0098 step 6): the running side reads the
 * membership for every request, so a change needs no restart. A node that is not a member afterwards is also dropped
 * from {@code replication.peers}, which a restarted server would otherwise dial or admit it from.
 */
abstract class ProjectMemberCommand extends ProjectRoleCommand {

    @Parameters(index = "0", arity = "1", description = "Node ID of the member ('synesis identity show --project' on its computer).")
    private String member;

    ProjectMemberCommand(CliRuntime runtime) {
        super(runtime, DAEMON);
    }

    /**
     * Writes the change.
     *
     * @param membership this computer's membership entries
     * @param node       the member
     * @return whether anything was written
     * @throws Exception the refusal of the entry
     */
    abstract boolean change(MembershipService membership, String node) throws Exception;

    @Override
    final void apply(Role role) throws Exception {
        if (!NodeIdentity.isNodeId(member)) {
            throw new Refusal("INVALID_NODE_ID");
        }
        role.leader();
        NodeIdentity identity = role.location().loadOrCreateIdentity();
        if (member.equals(identity.nodeId())) {
            throw new Refusal("MEMBER_IS_THIS_COMPUTER");
        }
        PredictionEventStore store = role.location().openCoordinationStore();
        SharedSettingsMigration.migrate(role.coordination(), store, identity);
        boolean changed = change(new MembershipService(store, identity), member);
        Set<String> members = role.location().openCoordinationStore().membershipProjection().members();
        if (!members.contains(member)) {
            // Whatever the change was, a node that is not a member must not be dialed or admitted again after a restart.
            ReplicationPeers.forget(role.coordination(), member);
        }
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
