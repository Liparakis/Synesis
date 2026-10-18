package org.synesis.cli.command.project;

import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Makes this computer a member that follows another computer's copy of the project (ADR-0098 step 2).
 *
 * <p>A member's log is a copy of the leader's, pulled entry by entry. An entry made here before following would
 * not be on the leader's log and the two would diverge, so a computer with entries of its own is refused instead of
 * left to find out at the first pull. Following the same leader again, to change the code remote, is allowed.</p>
 */
@Command(name = "follow", description = "Make this computer a member that follows the leader's copy of the project.", mixinStandardHelpOptions = true)
public final class ProjectFollowCommand extends ProjectRoleCommand {

    @Option(names = "--leader", required = true, description = "Node ID of the leader (printed by 'synesis project share' on its computer).")
    private String leader;
    @Option(names = "--code-remote", description = "Name of a Git remote of this clone to keep as a mirror of the code. Code moves over Link without one.")
    private String codeRemote;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public ProjectFollowCommand(CliRuntime runtime) {
        this(runtime, DAEMON);
    }

    ProjectFollowCommand(CliRuntime runtime, RuntimeStopper stopper) {
        super(runtime, stopper);
    }

    @Override
    void apply(Role role) throws Exception {
        if (!NodeIdentity.isNodeId(leader)) {
            throw new Refusal("INVALID_NODE_ID");
        }
        String own = role.location().loadOrCreateIdentity().nodeId();
        if (leader.equals(own)) {
            throw new Refusal("LEADER_IS_THIS_COMPUTER");
        }
        ReplicationSettings found = role.settings().orElse(null);
        if (found != null && found.isMember()) {
            if (!leader.equals(found.leaderNodeId())) {
                throw new Refusal("FOLLOWING_ANOTHER_LEADER");
            }
        } else if (found != null && !found.members().isEmpty()) {
            throw new Refusal("ALREADY_A_LEADER");
        } else if (PredictionEventStore.recordedEvents(role.coordination()) > 0) {
            throw new Refusal("LOG_NOT_EMPTY");
        }
        ReplicationSettings member = ReplicationSettings.member(leader);
        if (codeRemote != null) {
            requireRemote(role, codeRemote);
            member = member.withCodeRemote(codeRemote);
        }
        role.write(member);
        // A member reconnects to its leader only; records of any other node (from an earlier role) must not be resumed.
        ReplicationPeers.retainOnly(role.coordination(), Set.of(leader));
        runtime.terminal().stdout("ROLE=member");
        runtime.terminal().stdout("NODE_ID=" + own);
        runtime.terminal().stdout("LEADER_NODE_ID=" + leader);
        runtime.terminal().stdout("CODE_REMOTE=" + (codeRemote == null ? "" : codeRemote));
    }

    @Override
    boolean needsRuntimeRestart() {
        return true;
    }
}
