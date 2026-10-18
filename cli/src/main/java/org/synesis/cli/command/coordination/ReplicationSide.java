package org.synesis.cli.command.coordination;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.MemberReplication;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.session.PeerSession;

import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeOverLink;

/**
 * The replication side a coordination server currently plays on its Link sessions (ADR-0098): the leader's, which
 * serves the members the role file names, or a member's, which follows one leader.
 *
 * <p>The Link owner is built once and asks this object per frame and per session, so a computer that follows a
 * project link becomes a member of the inviter without restarting: the member side takes over from the leader side,
 * which a computer without members never used. The change is one way here; going back to leader needs the role file
 * and a restart, as before. The role file stays the authority for every other process, and is written before
 * {@link #becomeMember} is called.
 *
 * <p>Code over Link ({@code SCX1}, ADR-0098 step 3) rides on the same sessions and the same peers: the code side
 * serves exactly the nodes this side serves, a member's leader or a leader's current members, and asks them.</p>
 */
final class ReplicationSide implements AutoCloseable, CodeOverLink.Peers {

    // A code exchange is one Link exchange, which Link gives up after five seconds; waiting a little longer only reports it.
    private static final long CODE_WAIT_SECONDS = 8;

    private final Path coordination;
    private final UUID projectId;
    private final CodeOverLink code;
    private volatile MemberReplication member;
    private volatile String leaderNodeId;
    private volatile LeaderReplication leader;
    private volatile MergeDriver mergeDriver;

    /**
     * @param coordination the project's coordination folder
     * @param projectId    the project
     * @param repository   the project's control checkout, whose code moves over Link, or {@code null} for none
     * @param member       the member side, or {@code null}
     * @param leaderNodeId the leader of that member side
     * @param leader       the leader side, or {@code null} when this is a member
     * @param mergeDriver  the leader's merge pass, or {@code null}
     */
    ReplicationSide(Path coordination, UUID projectId, Path repository, MemberReplication member, String leaderNodeId, LeaderReplication leader,
            MergeDriver mergeDriver) {
        this.coordination = coordination;
        this.projectId = projectId;
        this.code = repository == null ? null : new CodeOverLink(repository, coordination, projectId, this);
        this.member = member;
        this.leaderNodeId = leaderNodeId;
        this.leader = leader;
        this.mergeDriver = mergeDriver;
        if (member != null && code != null) {
            member.whenPulled(code::mainMayHaveMoved);
        }
    }

    /**
     * @return the handler that answers replication and code frames with the side in play when the frame arrives
     */
    PeerSession.ApplicationStreamHandler handler() {
        return (remoteNodeId, payload) -> {
            if (CodeTransferCodec.looksLike(payload)) {
                CodeOverLink serving = code;
                return serving != null && returning(remoteNodeId) ? serving.serve(payload)
                        : CompletableFuture.completedFuture(CodeTransferCodec.encode(new CodeTransferMessage.Unavailable(CodeTransferMessage.NOT_SERVED)));
            }
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

    @Override
    public Map<String, ReplicationExchange> live() {
        MemberReplication asMember = member;
        LeaderReplication asLeader = leader;
        Map<String, PeerSession> sessions = asMember != null ? asMember.liveSessions() : asLeader != null ? asLeader.liveSessions() : Map.of();
        Map<String, ReplicationExchange> live = new HashMap<>();
        sessions.forEach((node, session) -> live.put(node, exchange(session)));
        return live;
    }

    @Override
    public boolean member() {
        return member != null;
    }

    /** One request and one answer on a session, with the failures of {@link ReplicationExchange}. */
    private static ReplicationExchange exchange(PeerSession session) {
        return request -> {
            CompletableFuture<byte[]> answer;
            try {
                answer = session.requestApplication(request).toCompletableFuture();
            } catch (IllegalStateException notUsable) {
                throw new ConnectException("the session is not usable");
            }
            try {
                return answer.get(CODE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for the other computer", interrupted);
            } catch (ExecutionException | TimeoutException lost) {
                throw new IOException("the other computer did not answer", lost);
            }
        };
    }

    /**
     * Makes the loopback server answer this computer's processes when they ask for code, and records where it
     * listens, on the leader as on a member. The merge pass, which runs in this process, asks directly.
     *
     * @param server the started loopback server of this computer
     * @throws IOException when the endpoint cannot be recorded
     */
    void serveCode(CoordinationHttpServer server) throws IOException {
        if (code != null) {
            server.code(code::fetch);
            LoopbackExchange.recordEndpoint(coordination, server.address());
            CodeExchange.register(coordination, code::fetch);
        }
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
        if (code != null) {
            created.whenPulled(code::mainMayHaveMoved);
        }
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
        if (code != null) {
            try {
                CodeExchange.register(coordination, null);
            } catch (IOException unresolvable) {
                // The folder is gone; so is anything that could ask through it.
            }
            code.close();
        }
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
