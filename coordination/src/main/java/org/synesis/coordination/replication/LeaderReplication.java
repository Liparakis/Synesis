package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.session.PeerSession;

/**
 * The owner computer's side of the replication protocol on Link (ADR-0098): exchanges logs with every attached
 * member through a {@link LogExchange}, which serves every log this computer holds and pulls each member's.
 *
 * <p>The sender of a request is the authenticated Link session's remote node, handed in by Link, and never a field
 * of the message. A node that is not in the member set gets nothing. Since step 4 nothing is proposed: every computer
 * appends to its own log, and a step-2 proposal is answered {@code REPLICATION_REQUEST_UNSUPPORTED}. The seam for a
 * session is {@link #attach}.</p>
 *
 * <p>Whether each current member has an attached session, and since when, is written to {@value #MEMBERS_FILE} for
 * {@code synesis coordination status}, the leader's counterpart of a member's {@code replication.status}. Only a
 * session attached here counts: a peer Link admitted that the settings do not name is not a member. The file is
 * removed when this object closes, so a file that is present was written by a server that ran or is running.</p>
 */
public final class LeaderReplication implements AutoCloseable {

    /**
     * Name of the file holding each current member's connection, next to the events folder: one line per member,
     * {@code <node> CONNECTED|DISCONNECTED <instant>}.
     */
    public static final String MEMBERS_FILE = "replication.members";

    private static final System.Logger LOGGER = System.getLogger(LeaderReplication.class.getName());

    /**
     * Whether a member has a live session to the leader.
     *
     * @param state whether its session is attached now
     * @param since when that session was attached or ended; for a member with no session yet, when the leader
     *              started serving
     */
    public record MemberConnection(State state, Instant since) {

        /**
         * A member's session, as {@code synesis coordination status} and {@value LeaderReplication#MEMBERS_FILE}
         * name it.
         */
        public enum State {
            /**
             * From the attach of its session until that session ends.
             */
            CONNECTED,
            /**
             * No attached session.
             */
            DISCONNECTED
        }
    }

    private final Path root;
    private final UUID projectId;
    private final LogExchange exchange;
    private volatile CoordinationHttpServer served;
    private final Supplier<Set<String>> members;
    private final java.util.function.Predicate<String> admitted;
    private final MembershipView membership;
    private final Runnable afterMemberEntry;
    private volatile Runnable pulled = () -> {
    };
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, MemberConnection> connections = new ConcurrentHashMap<>();
    private final Instant started = Instant.now();
    private final ScheduledExecutorService notices = Executors.newSingleThreadScheduledExecutor(
            daemon("synesis-replication-notice"));

    /**
     * Serves one project's log.
     *
     * @param root      the leader's coordination folder
     * @param projectId the project
     * @param members   nodes whose logs are exchanged
     */
    public LeaderReplication(Path root, UUID projectId, Set<String> members) {
        this(root, projectId, members, () -> {
        });
    }

    /**
     * Exchanges one project's logs and tells the owner of {@code afterMemberEntry} whenever a member's entries
     * arrived, so the leader can act on it without an agent of its own (the merge driver, ADR-0098).
     *
     * @param root             the leader's coordination folder
     * @param projectId        the project
     * @param members          nodes whose logs are exchanged
     * @param afterMemberEntry called after entries of a member arrived, on the exchange thread; it must not block
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
     * @param members          current nodes whose logs are exchanged; an empty set refuses everyone
     * @param afterMemberEntry called after entries of a member arrived; it must not block
     */
    public LeaderReplication(Path root, UUID projectId, Supplier<Set<String>> members, Runnable afterMemberEntry) {
        this(root, projectId, members, node -> members.get().contains(node), node -> members.get().contains(node), null, afterMemberEntry);
    }

    /**
     * Exchanges one project's logs with the members the membership entries name (ADR-0098 step 6): the side every
     * computer of a shared project runs, the owner's and every member's alike (designer item 13).
     *
     * @param root             this computer's coordination folder
     * @param projectId        the project
     * @param membership       which computers are admitted and whose logs are copied, read afresh
     * @param afterMemberEntry called after entries of another computer arrived; it must not block
     */
    public LeaderReplication(Path root, UUID projectId, MembershipView membership, Runnable afterMemberEntry) {
        this(root, projectId, membership::others, membership::admitted, membership::copied, membership, afterMemberEntry);
    }

    private LeaderReplication(Path root, UUID projectId, Supplier<Set<String>> members, java.util.function.Predicate<String> admitted,
            java.util.function.Predicate<String> copied, MembershipView membership, Runnable afterMemberEntry) {
        this.membership = membership;
        this.afterMemberEntry = CoordinationNullException.requireNonNull(afterMemberEntry, "afterMemberEntry");
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.members = CoordinationNullException.requireNonNull(members, "members");
        this.admitted = CoordinationNullException.requireNonNull(admitted, "admitted");
        this.exchange = new LogExchange(root, projectId, admitted, copied, this::afterArrivals);
        notices.execute(this::writeConnections);
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
     * @param afterMemberEntry called after entries of a member arrived; it must not block
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
     * @param afterMemberEntry called after entries of a member arrived; it must not block
     * @return the leader side
     */
    public static LeaderReplication serving(Path root, UUID projectId, Runnable afterMemberEntry) {
        return new LeaderReplication(root, projectId, () -> currentMembers(root), afterMemberEntry);
    }

    /**
     * Serves the project of a coordination folder as the membership entries say, or before sharing as the computers
     * it trusts say (ADR-0098 step 6, designer items 13 and 15): every computer's side.
     *
     * @param root             this computer's coordination folder
     * @param projectId        the project
     * @param self             this computer's node
     * @param afterMemberEntry called after entries of another computer arrived; it must not block
     * @return the side
     */
    public static LeaderReplication shared(Path root, UUID projectId, String self, Runnable afterMemberEntry) {
        return new LeaderReplication(root, projectId, new MembershipView(root, projectId, self), afterMemberEntry);
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
     * Reads the connections a leader server on this computer last wrote. Lines that cannot be read are skipped; a
     * member the file does not name has had no session since that write.
     *
     * @param root the leader's coordination folder
     * @return each recorded member's connection by node, or empty when no leader server recorded any
     */
    public static Optional<Map<String, MemberConnection>> recordedMembers(Path root) {
        List<String> lines;
        try {
            lines = Files.readAllLines(root.resolve(MEMBERS_FILE), StandardCharsets.UTF_8);
        } catch (IOException none) {
            return Optional.empty();
        }
        Map<String, MemberConnection> recorded = new HashMap<>();
        for (String line : lines) {
            String[] parts = line.strip().split(" ");
            if (parts.length != 3) {
                continue;
            }
            try {
                recorded.put(parts[0], new MemberConnection(MemberConnection.State.valueOf(parts[1]), Instant.parse(parts[2])));
            } catch (IllegalArgumentException | DateTimeParseException unreadable) {
                // Skipped like any other line that does not follow the format.
            }
        }
        return Optional.of(Map.copyOf(recorded));
    }

    /**
     * Tells whether a node's log is exchanged now.
     *
     * @param nodeId a node ID
     * @return true when the leader's settings name it as a member
     */
    public boolean isMember(String nodeId) {
        return admitted.test(nodeId);
    }

    /**
     * The membership this side goes by, when it goes by the membership entries (ADR-0098 step 6).
     *
     * @return the view, or empty for a side built on a fixed or settings-file member list
     */
    public Optional<MembershipView> membership() {
        return Optional.ofNullable(membership);
    }

    /**
     * The live sessions of the members the settings name now, for other protocols that ride on them (Code over Link).
     *
     * @return each such member's node and its session
     */
    public Map<String, PeerSession> liveSessions() {
        Map<String, PeerSession> live = new HashMap<>();
        sessions.forEach((node, session) -> {
            if (isMember(node) && Sessions.live(session)) {
                live.put(node, session);
            }
        });
        return Map.copyOf(live);
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
            try {
                ReplicationMessage message = ReplicationCodec.decode(payload);
                if (LogExchange.serves(message)) {
                    return exchange.serve(remoteNodeId, message);
                }
            } catch (CoordinationException malformed) {
                return CompletableFuture.completedFuture(refusal(malformed.code()));
            }
            // A proposal of a step-2 member: since step 4 every computer appends to its own log.
            return CompletableFuture.completedFuture(refusal("REPLICATION_REQUEST_UNSUPPORTED"));
        };
    }

    /**
     * Attaches an authenticated session, so logs are exchanged over it.
     *
     * <p>A session of a node the settings do not name yet is kept by the exchange, which serves it nothing and pulls
     * nothing from it until the settings name it: a joiner the owner accepts later, over the session it already
     * holds, needs no redial (ADR-0098 step 4). Only a member's session is recorded as a member's connection.</p>
     *
     * @param session an authenticated, control-ready session
     */
    public void attach(PeerSession session) {
        if (!isMember(session.remoteNodeId())) {
            exchange.attach(session);
            return;
        }
        String node = session.remoteNodeId();
        // One lock over the session map and the record, so the end of an old session cannot be recorded after the
        // attach of its successor.
        synchronized (connections) {
            sessions.put(node, session);
            recordConnection(node, MemberConnection.State.CONNECTED);
        }
        exchange.attach(session);
        session.terminalCompletion().whenComplete((ignored, failure) -> {
            synchronized (connections) {
                // A session replaced by a newer one to the same member does not make that member disconnected.
                if (sessions.remove(node, session)) {
                    recordConnection(node, MemberConnection.State.DISCONNECTED);
                }
            }
        });
    }

    private void recordConnection(String node, MemberConnection.State state) {
        connections.put(node, new MemberConnection(state, Instant.now()));
        try {
            // On the notice thread, so writes are in order and file work stays off the Link event loop.
            notices.execute(this::writeConnections);
        } catch (RejectedExecutionException closed) {
            // Closed: the file is gone, and a session that ends while the server stops is not news.
        }
    }

    /**
     * Writes one line for every member the settings name now, replacing the file whole so a reader never sees half
     * of it. With no members there is nothing to say, and no file.
     */
    private void writeConnections() {
        Path file = root.resolve(MEMBERS_FILE);
        List<String> lines = members.get().stream().sorted().map(node -> {
            MemberConnection connection = connections.getOrDefault(node, new MemberConnection(MemberConnection.State.DISCONNECTED, started));
            return node + " " + connection.state() + " " + connection.since();
        }).toList();
        try {
            if (lines.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Path temporary = root.resolve(MEMBERS_FILE + ".tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            for (int attempt = 1; ; attempt++) {
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    break;
                } catch (java.nio.file.AccessDeniedException beingRead) {
                    // Windows refuses to replace a file another process has open for a moment; the next state must
                    // still be recorded, or a reader keeps an old one.
                    if (attempt >= 20) {
                        throw beingRead;
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25L));
                }
            }
        } catch (IOException | RuntimeException unwritable) {
            // The file explains the replication; it must not stop it.
            LOGGER.log(System.Logger.Level.WARNING, "Could not record the members' connections.", unwritable);
        }
    }

    private static byte[] refusal(String code) {
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(new ProposalOutcome.Refused(code)));
    }

    /**
     * Makes this object the relay of a loopback coordination server and records where it listens, so that the
     * agents' processes of this computer can have their claims checked with the connected members.
     *
     * @param server a started loopback server of this computer
     * @throws IOException when the endpoint cannot be recorded
     */
    public void serve(CoordinationHttpServer server) throws IOException {
        served = server;
        server.relay(this::relay);
        LoopbackExchange.recordEndpoint(root, server.address());
    }

    /**
     * Answers one request of an agent's process: the check of a claim it appended.
     *
     * @param request an encoded request
     * @return the encoded answer; never throws
     */
    public byte[] relay(byte[] request) {
        try {
            ReplicationMessage message = ReplicationCodec.decode(request);
            if (message instanceof ReplicationMessage.ClaimIntent intent) {
                return exchange.registerClaim(intent);
            }
            if (message instanceof ReplicationMessage.ClaimSeen seen) {
                return exchange.checkClaim(seen);
            }
        } catch (CoordinationException malformed) {
            return refusal(malformed.code());
        }
        return refusal("REPLICATION_REQUEST_UNSUPPORTED");
    }

    /**
     * Tells {@code listener} after each pull that brought a member's entries, so this computer's code can take a
     * newer copy of {@code main} that a member advanced (ADR-0098 step 5). It runs on the exchange thread and must
     * return at once.
     *
     * @param listener told after such a pull
     */
    public void whenPulled(Runnable listener) {
        pulled = CoordinationNullException.requireNonNull(listener, "listener");
    }

    private void afterArrivals() {
        try {
            afterMemberEntry.run();
        } finally {
            pulled.run();
        }
    }

    /**
     * The exchange this side runs, for the claim lock between connected computers.
     *
     * @return the exchange
     */
    public LogExchange exchange() {
        return exchange;
    }

    /**
     * Stops watching the head and answering, and removes the record of the members' connections. Attached sessions
     * belong to their owner and are not closed.
     */
    @Override
    public void close() {
        CoordinationHttpServer server = served;
        if (server != null) {
            server.relay(null);
            try {
                Files.deleteIfExists(root.resolve(LoopbackExchange.ENDPOINT_FILE));
            } catch (IOException stuck) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not remove the replication endpoint.", stuck);
            }
        }
        exchange.close();
        notices.shutdownNow();
        try {
            // A write still running must not put the file back after it is removed.
            notices.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            Files.deleteIfExists(root.resolve(MEMBERS_FILE));
        } catch (IOException stuck) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not remove the record of the members' connections.", stuck);
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
