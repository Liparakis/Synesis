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
import java.util.function.Supplier;
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
    private final Supplier<Set<String>> members;
    private final Runnable afterMemberEntry;
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
        this(root, projectId, members, () -> {
        });
    }

    /**
     * Serves one project's log and tells the owner of {@code afterMemberEntry} whenever a member's entry went on
     * the log, so the leader can act on it without an agent of its own (the merge driver, ADR-0098).
     *
     * @param root             the leader's coordination folder
     * @param projectId        the project
     * @param members          nodes allowed to propose and pull
     * @param afterMemberEntry called after each accepted proposal, on the thread that answered it; it must not
     *                         block
     */
    public LeaderReplication(Path root, UUID projectId, Set<String> members, Runnable afterMemberEntry) {
        this(root, projectId, fixed(CoordinationNullException.requireNonNull(members, "members")), afterMemberEntry);
    }

    /**
     * As above, with the member set read afresh for every request, so that a member added to or removed from the
     * leader's settings is served or refused from its next request on, with no restart.
     *
     * @param root             the leader's coordination folder
     * @param projectId        the project
     * @param members          current nodes allowed to propose and pull; an empty set refuses everyone
     * @param afterMemberEntry called after each accepted proposal; it must not block
     */
    public LeaderReplication(Path root, UUID projectId, Supplier<Set<String>> members, Runnable afterMemberEntry) {
        this.afterMemberEntry = CoordinationNullException.requireNonNull(afterMemberEntry, "afterMemberEntry");
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.members = CoordinationNullException.requireNonNull(members, "members");
        this.endpoint = new LeaderEndpoint(root, projectId, members);
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
        return open(root, projectId, () -> {
        });
    }

    /**
     * As {@link #open(Path, UUID)}, calling {@code afterMemberEntry} after each accepted member proposal.
     *
     * @param root             the leader's coordination folder
     * @param projectId        the project
     * @param afterMemberEntry called after each accepted proposal; it must not block
     * @return the leader side
     * @throws IOException when the settings are missing, invalid, or are a member's
     */
    public static LeaderReplication open(Path root, UUID projectId, Runnable afterMemberEntry) throws IOException {
        ReplicationSettings.read(root)
                .filter(found -> !found.isMember())
                .orElseThrow(() -> new CoordinationException("NOT_A_LEADER"));
        return new LeaderReplication(root, projectId, () -> currentMembers(root), afterMemberEntry);
    }

    /**
     * Serves the project of a coordination folder that has no role yet, as a leader with no members. A log with no
     * role file is a leader (ADR-0098), and the member list is read afresh for every request, so a computer that
     * later accepts a joiner serves it without a restart. Until then everyone is refused.
     *
     * @param root             the leader's coordination folder
     * @param projectId        the project
     * @param afterMemberEntry called after each accepted proposal; it must not block
     * @return the leader side
     */
    public static LeaderReplication serving(Path root, UUID projectId, Runnable afterMemberEntry) {
        return new LeaderReplication(root, projectId, () -> currentMembers(root), afterMemberEntry);
    }

    /**
     * The members the settings file names now. A file that is gone, unreadable, or has become a member's names
     * nobody: an unreadable role never admits a proposer (fail closed).
     */
    private static Set<String> currentMembers(Path root) {
        try {
            return ReplicationSettings.read(root).filter(found -> !found.isMember())
                    .map(ReplicationSettings::members).orElse(Set.of());
        } catch (CoordinationException unreadable) {
            return Set.of();
        }
    }

    private static Supplier<Set<String>> fixed(Set<String> members) {
        Set<String> copy = Set.copyOf(members);
        return () -> copy;
    }

    /**
     * Tells whether a node may propose and pull now.
     *
     * @param nodeId a node ID
     * @return true when the leader's settings name it as a member
     */
    public boolean isMember(String nodeId) {
        return members.get().contains(nodeId);
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
        if (!isMember(session.remoteNodeId())) {
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
            try {
                afterMemberEntry.run();
            } catch (RuntimeException hookFailed) {
                // The entry is on the log and the member must be told so; the hook's owner retries on its own.
            }
        }
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(outcome));
    }

    private byte[] pull(String sender, ReplicationMessage.Pull pull) throws IOException, GeneralSecurityException {
        if (!isMember(sender)) {
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
        if (sessions.isEmpty()) {
            return;
        }
        long head = PredictionEventStore.recordedEvents(root);
        sessions.forEach((node, session) -> {
            if (!isMember(node) || !Sessions.live(session) || noticed.getOrDefault(node, -1L) == head) {
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
