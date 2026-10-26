package org.synesis.cli.command.project;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.SharedSettingsMigration;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.link.identity.NodeIdentity;

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
    boolean change(Role role, String node) throws Exception {
        if (!NodeIdentity.isNodeId(node)) {
            throw new Refusal("INVALID_NODE_ID");
        }
        role.leader();
        NodeIdentity identity = role.location().loadOrCreateIdentity();
        if (node.equals(identity.nodeId())) {
            throw new Refusal("MEMBER_IS_THIS_COMPUTER");
        }
        PredictionEventStore store = role.location().openCoordinationStore();
        SharedSettingsMigration.migrate(role.coordination(), store, identity);
        MembershipService membership = new MembershipService(store, identity);
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
