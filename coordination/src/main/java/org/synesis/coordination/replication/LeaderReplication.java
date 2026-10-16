package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.LeaderEndpoint;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.session.PeerSession;

/**
 * The leader computer's side of the replication protocol on Link (ADR-0098 step 2): answers members'
 * proposals and pulls, and tells every attached member when the log's head moves.
 *
 * <p>The sender of a request is the authenticated Link session's remote node, handed in by Link, and never a field
 * of the message. A node that is not in the member set gets nothing: its proposals are refused by
 * {@link LeaderEndpoint} and its pulls here. The seam for a session is {@link #attach}; the product flow that
 * produces authenticated sessions is a later step.</p>
 *
 * <p>The head is watched by counting event files, because the log is also appended by other processes of the
 * leader computer (agents' MCP servers) that this object never sees. A notice is advisory: a member that misses
 * one pulls anyway at its next interval.</p>
 */
public final class LeaderReplication implements AutoCloseable {

    /**
     * How often the log is checked for a head that moved since the last notice.
     */
    private static final long NOTICE_CHECK_MILLIS = 200;
    /**
     * Largest page of a pull answer, so one answer stays well inside the Link payload bound and one operation
     * deadline on a slow uplink.
     */
    private static final int MAX_PAGE_BYTES = 256 * 1024;

    private final Path root;
    private final UUID projectId;
    private final LeaderEndpoint endpoint;
    private final Set<String> members;
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> noticed = new ConcurrentHashMap<>();
    private final ExecutorService requests = Executors.newCachedThreadPool(daemon("synesis-replication-leader"));
    private final ScheduledExecutorService notices = Executors.newSingleThreadScheduledExecutor(
            daemon("synesis-replication-notice"));

    /**
     * Serves one project's log.
     *
     * @param root      the leader's coordination folder
     * @param projectId the project
     * @param members   nodes allowed to propose and pull
     */
    public LeaderReplication(Path root, UUID projectId, Set<String> members) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.members = Set.copyOf(CoordinationNullException.requireNonNull(members, "members"));
        this.endpoint = new LeaderEndpoint(root, projectId, this.members);
        notices.scheduleWithFixedDelay(this::noticeAll, NOTICE_CHECK_MILLIS, NOTICE_CHECK_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Serves the project of a coordination folder whose settings make it the leader.
     *
     * @param root      the leader's coordination folder
     * @param projectId the project
     * @return the leader side
     * @throws IOException when the settings are missing, invalid, or are a member's
     */
    public static LeaderReplication open(Path root, UUID projectId) throws IOException {
        ReplicationSettings settings = ReplicationSettings.read(root)
                .filter(found -> !found.isMember())
                .orElseThrow(() -> new CoordinationException("NOT_A_LEADER"));
        return new LeaderReplication(root, projectId, settings.members());
    }

    /**
     * The handler to install on every Link session that may carry members.
     *
     * @return handler answering proposals and pulls
     */
    public PeerSession.ApplicationStreamHandler handler() {
        return (remoteNodeId, payload) -> {
            if (!ReplicationCodec.looksLike(payload)) {
                return CompletableFuture.completedFuture(refusal("REPLICATION_REQUEST_UNSUPPORTED"));
            }
            // Blocking work (the append lock, reading the log) must not run on the Link event loop.
            return CompletableFuture.supplyAsync(() -> serve(remoteNodeId, payload), requests);
        };
    }

    /**
     * Attaches the authenticated session of a member, so it is told when the head moves.
     *
     * @param session an authenticated, control-ready session whose remote node is a member
     * @throws IllegalArgumentException when the remote node is not a member
     */
    public void attach(PeerSession session) {
        if (!members.contains(session.remoteNodeId())) {
            throw new IllegalArgumentException("session peer is not a member");
        }
        sessions.put(session.remoteNodeId(), session);
        noticed.remove(session.remoteNodeId());
    }

    private byte[] serve(String sender, byte[] payload) {
        try {
            return switch (ReplicationCodec.decode(payload)) {
                case ReplicationMessage.Propose propose -> propose(sender, propose);
                case ReplicationMessage.Pull pull -> pull(sender, pull);
                default -> refusal("REPLICATION_REQUEST_UNSUPPORTED");
            };
        } catch (CoordinationException malformed) {
            return refusal(malformed.code());
        } catch (IOException | GeneralSecurityException transientFailure) {
            // No answer: the member sends the same bytes again, which the leader recognises.
            throw new java.util.concurrent.CompletionException(transientFailure);
        }
    }

    private byte[] propose(String sender, ReplicationMessage.Propose propose)
            throws IOException, GeneralSecurityException {
        if (!propose.project().equals(projectId)) {
            return ReplicationCodec.encode(new ReplicationMessage.Outcome(
                    new ProposalOutcome.Refused("PROPOSAL_PROJECT_MISMATCH")));
        }
        ProposalOutcome outcome = endpoint.accept(propose.event(), sender);
        if (outcome instanceof ProposalOutcome.Accepted) {
            notices.execute(this::noticeAll);
        }
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(outcome));
    }

    private byte[] pull(String sender, ReplicationMessage.Pull pull) throws IOException, GeneralSecurityException {
        if (!members.contains(sender)) {
            return refusal("PROPOSER_NOT_A_MEMBER");
        }
        if (!pull.project().equals(projectId)) {
            return refusal("PROPOSAL_PROJECT_MISMATCH");
        }
        List<byte[]> entries = endpoint.entriesAfter(pull.after(), pull.maxEntries(),
                Math.min(pull.maxBytes(), MAX_PAGE_BYTES));
        return ReplicationCodec.encode(new ReplicationMessage.Entries(entries));
    }

    private static byte[] refusal(String code) {
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(new ProposalOutcome.Refused(code)));
    }

    private void noticeAll() {
        long head = PredictionEventStore.recordedEvents(root);
        sessions.forEach((node, session) -> {
            if (!Sessions.live(session) || noticed.getOrDefault(node, -1L) == head) {
                return;
            }
            noticed.put(node, head);
            try {
                session.requestApplication(ReplicationCodec.encode(new ReplicationMessage.Notice(projectId, head)));
            } catch (IllegalStateException unusable) {
                noticed.remove(node);
            }
        });
    }

    /**
     * Stops watching the head and answering. Attached sessions belong to their owner and are not closed.
     */
    @Override
    public void close() {
        notices.shutdownNow();
        requests.shutdownNow();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
