package org.synesis.cli.command.coordination;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.MemberReplication;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.session.PeerSession;

/**
 * The replication side a coordination server currently plays on its Link sessions (ADR-0098): the leader's, which
 * serves the members the role file names, or a member's, which follows one leader.
 *
 * <p>The Link owner is built once and asks this object per frame and per session, so a computer that follows a
 * project link becomes a member of the inviter without restarting: the member side takes over from the leader side,
 * which a computer without members never used. The change is one way here; going back to leader needs the role file
 * and a restart, as before. The role file stays the authority for every other process, and is written before
 * {@link #becomeMember} is called.
 */
final class ReplicationSide implements AutoCloseable {

    private final Path coordination;
    private final UUID projectId;
    private volatile MemberReplication member;
    private volatile String leaderNodeId;
    private volatile LeaderReplication leader;
    private volatile MergeDriver mergeDriver;

    /**
     * @param coordination the project's coordination folder
     * @param projectId    the project
     * @param member       the member side, or {@code null}
     * @param leaderNodeId the leader of that member side
     * @param leader       the leader side, or {@code null} when this is a member
     * @param mergeDriver  the leader's merge pass, or {@code null}
     */
    ReplicationSide(Path coordination, UUID projectId, MemberReplication member, String leaderNodeId, LeaderReplication leader, MergeDriver mergeDriver) {
        this.coordination = coordination;
        this.projectId = projectId;
        this.member = member;
        this.leaderNodeId = leaderNodeId;
        this.leader = leader;
        this.mergeDriver = mergeDriver;
    }

    /**
     * @return the handler that answers replication frames with the side in play when the frame arrives
     */
    PeerSession.ApplicationStreamHandler handler() {
        return (remoteNodeId, payload) -> {
            MemberReplication asMember = member;
            if (asMember != null) {
                return asMember.handler().handle(remoteNodeId, payload);
            }
            LeaderReplication asLeader = leader;
            if (asLeader != null) {
                return asLeader.handler().handle(remoteNodeId, payload);
            }
            CompletionStage<byte[]> closed = CompletableFuture.failedFuture(new IllegalStateException("the replication side is closed"));
            return closed;
        };
    }

    /**
     * Attaches the session when its peer is the leader (for a member) or a member (for the leader); any other peer is
     * left unserved.
     */
    void attach(PeerSession session) {
        MemberReplication asMember = member;
        if (asMember != null) {
            if (session.remoteNodeId().equals(leaderNodeId)) {
                asMember.attach(session);
            }
            return;
        }
        LeaderReplication asLeader = leader;
        if (asLeader != null && asLeader.isMember(session.remoteNodeId())) {
            asLeader.attach(session);
        }
    }

    /**
     * @return whether the node is one this computer redials or admits again: its leader, or one of its members
     */
    boolean returning(String nodeId) {
        if (member != null) {
            return nodeId.equals(leaderNodeId);
        }
        LeaderReplication asLeader = leader;
        return asLeader != null && asLeader.isMember(nodeId);
    }

    /**
     * Makes this side a member of {@code leaderNode}, and the loopback server its agents' relay.
     *
     * @param leaderNode the inviter
     * @param server     the started loopback server of this computer
     * @throws IOException when the member side cannot be set up, or this computer already follows another leader
     */
    synchronized void becomeMember(String leaderNode, CoordinationHttpServer server) throws IOException {
        if (member != null) {
            if (!leaderNode.equals(leaderNodeId)) {
                throw new IOException("FOLLOWING_ANOTHER_LEADER");
            }
            return;
        }
        MemberReplication created = new MemberReplication(coordination, projectId, leaderNode);
        try {
            created.serve(server);
        } catch (IOException | RuntimeException failed) {
            created.close();
            throw failed;
        }
        LeaderReplication retiredLeader = leader;
        MergeDriver retiredMerge = mergeDriver;
        leaderNodeId = leaderNode;
        member = created;
        leader = null;
        mergeDriver = null;
        if (retiredMerge != null) {
            retiredMerge.close();
        }
        if (retiredLeader != null) {
            retiredLeader.close();
        }
    }

    @Override
    public synchronized void close() {
        MemberReplication asMember = member;
        MergeDriver asMerge = mergeDriver;
        LeaderReplication asLeader = leader;
        if (asMember != null) {
            asMember.close();
        }
        if (asMerge != null) {
            asMerge.close();
        }
        if (asLeader != null) {
            asLeader.close();
        }
    }
}
