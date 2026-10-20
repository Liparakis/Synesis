package org.synesis.workspace.transport.control;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.persistence.LogDump;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;

/**
 * Who belongs to a project and which of those computers this one reaches, by the one rule both {@code synesis status}
 * and the browser UI follow, so the two never disagree: a shared project's members come from its logs, an older
 * leader's from its role file, and an older member's computer is its leader.
 *
 * @param shared  whether the project is shared with other computers
 * @param owner   the owner's node, or empty when the project has none
 * @param self    this computer's node, or empty when it has no readable identity
 * @param members every computer of a shared project, this one included; empty for a project on one computer
 */
record ProjectMembers(boolean shared, String owner, String self, List<Member> members) {

    /**
     * State of this computer in the member list, which has no connection to itself.
     */
    static final String SELF = "SELF";

    /**
     * One computer of a shared project.
     *
     * @param node         the computer's node
     * @param self         whether it is this computer
     * @param owner        whether it is the owner
     * @param state        {@code SELF}; or for another computer {@code CONNECTED} or {@code DISCONNECTED} as the project's
     *                     runtime recorded, or {@code RUNTIME_STOPPED} when nothing says, because no runtime runs to record it
     * @param since        when the runtime recorded that state, or empty
     * @param lastReceived when entries of that computer's log last arrived on this one, or empty when none has
     */
    record Member(String node, boolean self, boolean owner, String state, Optional<Instant> since, Optional<Instant> lastReceived) {
    }

    /**
     * Reads the members from the logs and the role file.
     *
     * @param data       the project's coordination folder
     * @param membership the project's membership as its logs say
     * @param settings   the role file's content, or empty when none exists
     * @param self       this computer's node, or empty
     * @param running    whether the project's runtime runs and so records connections
     * @return the project's members
     */
    static ProjectMembers read(Path data, MembershipProjection membership, Optional<ReplicationSettings> settings, String self, boolean running) {
        boolean shared = membership.shared() || settings.isPresent();
        // An earlier role file names its leader as the one all others follow; the leader's own computer is the owner.
        String owner = membership.owner().orElse(settings.map(role -> role.isMember() ? role.leaderNodeId() : self).orElse(""));
        TreeSet<String> nodes = new TreeSet<>();
        if (membership.shared()) {
            nodes.addAll(membership.members());
        } else if (settings.isPresent()) {
            ReplicationSettings role = settings.get();
            nodes.addAll(role.isMember() ? Set.of(role.leaderNodeId()) : role.members());
        }
        if (shared && !self.isEmpty()) {
            nodes.add(self);
        }
        var recorded = LeaderReplication.recordedMembers(data);
        List<Member> members = new ArrayList<>();
        for (String node : nodes) {
            if (node.equals(self)) {
                members.add(new Member(node, true, node.equals(owner), SELF, Optional.empty(), Optional.empty()));
                continue;
            }
            var connection = recorded.map(connections -> connections.get(node));
            String state = !running ? "RUNTIME_STOPPED" : connection.isPresent() ? connection.get().state().name() : "DISCONNECTED";
            members.add(new Member(node, false, node.equals(owner), state, running ? connection.map(LeaderReplication.MemberConnection::since) : Optional.empty(), LogDump.lastArrival(data, node)));
        }
        return new ProjectMembers(shared, owner, self, List.copyOf(members));
    }
}
