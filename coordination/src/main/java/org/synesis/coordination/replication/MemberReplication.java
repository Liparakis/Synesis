package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.ExchangeLeaderChannel;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.session.PeerSession;

/**
 * A member computer's coordination server side of the replication protocol on Link (ADR-0098 step 2).
 *
 * <p>Only this object holds the Link session to the leader. The agents' processes open the member's log and reach
 * the leader through it: their {@code LeaderChannel} posts to the loopback {@code /replication} route that
 * {@link #serve} installs, and {@link #relay} forwards the request, unread beyond its kind, over the session. A
 * missing or unusable session answers "unreachable" without sending anything; a request lost on the way answers
 * "no answer".</p>
 *
 * <p>It also keeps the member's copy current: when the leader sends a notice, and every
 * {@value #PULL_INTERVAL_SECONDS} seconds regardless (which doubles as the keepalive of the path through a home
 * router), it pulls the entries after its head and applies them after checking the chain. A log that differs from
 * the leader's stops the pulling and the proposals, loudly, and stays stopped; a member never repairs its copy by
 * guessing. The state is also written to {@code replication.status} for {@code synesis coordination status}.</p>
 */
public final class MemberReplication implements AutoCloseable {

    /**
     * Seconds between pulls when no notice arrives.
     */
    public static final int PULL_INTERVAL_SECONDS = 15;

    /**
     * Name of the file holding the last known state, next to the events folder.
     */
    public static final String STATUS_FILE = "replication.status";

    private static final System.Logger LOGGER = System.getLogger(MemberReplication.class.getName());
    // Link gives up on an exchange after five seconds; waiting a little longer only reports it.
    private static final long RELAY_WAIT_SECONDS = 8;

    /**
     * Whether the member is in step with a leader it can reach.
     */
    public enum State {
        /**
         * No usable session to the leader.
         */
        DISCONNECTED,
        /**
         * The last pull succeeded.
         */
        CONNECTED,
        /**
         * The log differs from the leader's. Nothing is proposed or pulled until a person repairs it.
         */
        DIVERGED
    }

    private final Path root;
    private final UUID projectId;
    private final String leaderNodeId;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synesis-replication-member");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean pullQueued = new AtomicBoolean();
    private volatile PeerSession session;
    private volatile State state = State.DISCONNECTED;
    private volatile CoordinationHttpServer served;

    /**
     * Builds the member side for one project.
     *
     * @param root         the member's coordination folder
     * @param projectId    the project
     * @param leaderNodeId the leader's node; only its session is accepted
     */
    public MemberReplication(Path root, UUID projectId, String leaderNodeId) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.leaderNodeId = CoordinationNullException.requireNonNull(leaderNodeId, "leader node ID");
        worker.scheduleWithFixedDelay(this::pull, PULL_INTERVAL_SECONDS, PULL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Builds the member side of a coordination folder whose settings make it a member.
     *
     * @param root      the member's coordination folder
     * @param projectId the project
     * @return the member side
     * @throws IOException when the settings are missing, invalid, or are a leader's
     */
    public static MemberReplication open(Path root, UUID projectId) throws IOException {
        ReplicationSettings settings = ReplicationSettings.read(root)
                .filter(ReplicationSettings::isMember)
                .orElseThrow(() -> new CoordinationException("NOT_A_MEMBER"));
        return new MemberReplication(root, projectId, settings.leaderNodeId());
    }

    /**
     * Reads the last state a member server on this computer wrote.
     *
     * @param root the member's coordination folder
     * @return the state, or empty when none is recorded
     */
    public static Optional<String> recordedState(Path root) {
        try {
            return Optional.of(Files.readString(root.resolve(STATUS_FILE), StandardCharsets.UTF_8).trim());
        } catch (IOException none) {
            return Optional.empty();
        }
    }

    /**
     * The handler to install on the Link session to the leader: it accepts the leader's notices.
     *
     * @return handler answering notices
     */
    public PeerSession.ApplicationStreamHandler handler() {
        return (remoteNodeId, payload) -> {
            if (!remoteNodeId.equals(leaderNodeId) || !ReplicationCodec.looksLike(payload)) {
                return CompletableFuture.completedFuture(refusal("REPLICATION_REQUEST_UNSUPPORTED"));
            }
            try {
                if (ReplicationCodec.decode(payload) instanceof ReplicationMessage.Notice notice
                        && notice.project().equals(projectId)) {
                    requestPull();
                    return CompletableFuture.completedFuture(ReplicationCodec.encode(new ReplicationMessage.Noticed()));
                }
            } catch (CoordinationException malformed) {
                return CompletableFuture.completedFuture(refusal(malformed.code()));
            }
            return CompletableFuture.completedFuture(refusal("REPLICATION_REQUEST_UNSUPPORTED"));
        };
    }

    /**
     * Attaches the authenticated session to the leader.
     *
     * @param leader an authenticated, control-ready session
     * @throws IllegalArgumentException when its remote node is not the leader
     */
    public void attach(PeerSession leader) {
        if (!leader.remoteNodeId().equals(leaderNodeId)) {
            throw new IllegalArgumentException("session peer is not the leader");
        }
        session = leader;
        requestPull();
    }

    /**
     * Makes this object the relay of a loopback coordination server and records where that server listens, so
     * that processes opening the member's log can find it.
     *
     * @param server a started loopback server of this computer
     * @throws IOException when the endpoint cannot be recorded
     */
    public void serve(CoordinationHttpServer server) throws IOException {
        served = server;
        server.relay(this::relay);
        String host = server.address().getAddress().getHostAddress();
        Files.writeString(root.resolve(LoopbackExchange.ENDPOINT_FILE),
                (host.contains(":") ? "[" + host + "]" : host) + ":" + server.address().getPort() + "\n",
                StandardCharsets.UTF_8);
        setState(state);
    }

    /**
     * Forwards one request of an agent's process to the leader.
     *
     * @param request an encoded proposal or pull
     * @return the encoded answer; never throws
     */
    public byte[] relay(byte[] request) {
        if (state == State.DIVERGED) {
            return refusal("LEADER_LOG_DIVERGED");
        }
        try {
            ReplicationMessage message = ReplicationCodec.decode(request);
            if (!(message instanceof ReplicationMessage.Propose || message instanceof ReplicationMessage.Pull)) {
                return refusal("REPLICATION_REQUEST_UNSUPPORTED");
            }
        } catch (CoordinationException malformed) {
            return refusal(malformed.code());
        }
        PeerSession leader = session;
        CompletableFuture<byte[]> answer;
        try {
            if (!Sessions.live(leader)) {
                return outcome(new ProposalOutcome.Unreachable());
            }
            answer = leader.requestApplication(request).toCompletableFuture();
        } catch (IllegalStateException notUsable) {
            return outcome(new ProposalOutcome.Unreachable());
        }
        try {
            return answer.get(RELAY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return outcome(new ProposalOutcome.NoAnswer());
        } catch (ExecutionException | TimeoutException lost) {
            return outcome(new ProposalOutcome.NoAnswer());
        }
    }

    /**
     * The state as of the last pull.
     *
     * @return the state
     */
    public State state() {
        return state;
    }

    private void requestPull() {
        if (pullQueued.compareAndSet(false, true)) {
            worker.execute(this::pull);
        }
    }

    private void pull() {
        pullQueued.set(false);
        if (state == State.DIVERGED) {
            return;
        }
        PeerSession leader = session;
        if (!Sessions.live(leader)) {
            setState(State.DISCONNECTED);
            return;
        }
        try {
            new PredictionEventStore(root, projectId).pullFrom(new ExchangeLeaderChannel(projectId, request -> relay(request)));
            setState(State.CONNECTED);
        } catch (CoordinationException failure) {
            if (failure.code().equals("LEADER_LOG_DIVERGED")) {
                LOGGER.log(System.Logger.Level.ERROR,
                        "The log on this computer differs from the leader's. Nothing will be pulled or proposed "
                                + "until it is repaired.", failure);
                setState(State.DIVERGED);
            } else {
                setState(State.DISCONNECTED);
            }
        } catch (IOException | GeneralSecurityException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Pulling the leader's log failed; it will be tried again.", failure);
            setState(State.DISCONNECTED);
        }
    }

    private void setState(State next) {
        state = next;
        if (served != null) {
            try {
                Files.writeString(root.resolve(STATUS_FILE), next.name() + "\n", StandardCharsets.UTF_8);
            } catch (IOException unwritable) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not record the replication state.", unwritable);
            }
        }
    }

    private static byte[] outcome(ProposalOutcome outcome) {
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(outcome));
    }

    private static byte[] refusal(String code) {
        return outcome(new ProposalOutcome.Refused(code));
    }

    /**
     * Stops pulling and stops relaying. The session belongs to its owner and is not closed.
     */
    @Override
    public void close() {
        worker.shutdownNow();
        CoordinationHttpServer server = served;
        if (server != null) {
            server.relay(null);
            try {
                Files.deleteIfExists(root.resolve(LoopbackExchange.ENDPOINT_FILE));
                Files.deleteIfExists(root.resolve(STATUS_FILE));
            } catch (IOException stuck) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not remove the replication endpoint.", stuck);
            }
        }
    }
}
