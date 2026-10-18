package org.synesis.cli.command.coordination;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.session.PeerSession;

import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeOverLink;

/**
 * The replication side a coordination server plays on its Link sessions (ADR-0098). Since step 6 every computer of a
 * project plays the same one: it serves the members the membership entries name and pulls each one it is connected
 * to; before the owner's entries arrive, the computers it dialed (designer items 13 and 15).
 *
 * <p>The Link owner is built once and asks this object per frame and per session. A computer that follows a project
 * link needs no change of side: the inviter it dialed is trusted from then on, and the owner's entries decide once they
 * arrive.</p>
 *
 * <p>Code over Link ({@code SCX1}, ADR-0098 step 3) rides on the same sessions and the same peers: the code side
 * serves exactly the nodes this side serves, and asks them.</p>
 */
final class ReplicationSide implements AutoCloseable, CodeOverLink.Peers {

    // A code exchange is one Link exchange, which Link gives up after five seconds; waiting a little longer only reports it.
    private static final long CODE_WAIT_SECONDS = 8;

    private final Path coordination;
    private final UUID projectId;
    private final CodeOverLink code;
    private final String self;
    private final LeaderReplication leader;
    private final MergeDriver mergeDriver;

    /**
     * @param coordination the project's coordination folder
     * @param projectId    the project
     * @param repository   the project's control checkout, whose code moves over Link, or {@code null} for none
     * @param self         this computer's node
     * @param leader       the shared side
     * @param mergeDriver  this computer's merge pass, or {@code null}
     */
    ReplicationSide(Path coordination, UUID projectId, Path repository, String self, LeaderReplication leader, MergeDriver mergeDriver) {
        this.coordination = coordination;
        this.projectId = projectId;
        this.code = repository == null ? null : new CodeOverLink(repository, coordination, projectId, this);
        this.self = self;
        this.leader = leader;
        this.mergeDriver = mergeDriver;
        if (code != null) {
            // Every computer takes another's newer main, the owner's computer too (ADR-0098 step 5).
            leader.whenPulled(code::mainMayHaveMoved);
        }
        if (code != null) {
            // A diverged copy is re-merged by a pass, once its head is here (ADR-0098 step 5, designer item 13).
            code.whenDiverged(head -> {
                MergeDriver asMerge = this.mergeDriver;
                if (asMerge != null) {
                    asMerge.onMemberEntry();
                }
            });
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
            return leader.handler().handle(remoteNodeId, payload);
        };
    }

    /**
     * Attaches every session; a peer is served only while it is admitted, so a node accepted later is served from then
     * on, over the session it holds (ADR-0098 step 4).
     */
    void attach(PeerSession session) {
        leader.attach(session);
    }

    /**
     * @return whether the node is one this computer redials or admits again: a member, or before sharing a computer
     *         it dialed
     */
    boolean returning(String nodeId) {
        return leader.isMember(nodeId);
    }

    @Override
    public Map<String, ReplicationExchange> live() {
        Map<String, ReplicationExchange> live = new HashMap<>();
        leader.liveSessions().forEach((node, session) -> live.put(node, exchange(session)));
        return live;
    }

    /**
     * Whether this computer joined another's project rather than shared its own: the owner is another computer, or,
     * before the owner's entries arrive, it dialed an inviter.
     */
    @Override
    public boolean member() {
        java.util.Optional<String> owner = leader.membership().flatMap(view -> view.owner());
        if (owner.isPresent()) {
            return !owner.get().equals(self);
        }
        try {
            return !org.synesis.coordination.persistence.ReplicationPeers.read(coordination).dialed().isEmpty();
        } catch (org.synesis.coordination.exception.CoordinationException unreadable) {
            return false;
        }
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
     * Called when this computer follows a project link. Since ADR-0098 step 6 nothing changes side: the inviter was
     * recorded as dialed before this call, so the shared side trusts it from now on.
     *
     * @param inviter the inviter
     * @param server  the started loopback server of this computer
     */
    void becomeMember(String inviter, CoordinationHttpServer server) {
        // Nothing to switch; kept for the gateway the join calls.
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
        if (mergeDriver != null) {
            mergeDriver.close();
        }
        leader.close();
    }
}
