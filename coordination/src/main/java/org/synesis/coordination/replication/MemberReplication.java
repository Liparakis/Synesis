package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * A member computer's coordination server side of the replication protocol on Link (ADR-0098).
 *
 * <p>Only this object holds the Link session to the owner's computer. Since step 4 the member appends to its own log,
 * connected or not, and exchanges logs with the owner's computer through a {@link LogExchange}: it pulls every log
 * the owner's computer holds (its own and the copies of the other members' logs), when told and every
 * {@value #PULL_INTERVAL_SECONDS} seconds, and serves its own. A copy that differs from the one served stops being
 * pulled, loudly, and stays stopped; a member never repairs its copy by guessing. The state is also written to
 * {@code replication.status} for {@code synesis coordination status}.</p>
 *
 * <p>The loopback {@code /replication} route that {@link #serve} installs is how the agents' processes reach this
 * server, to have a claim checked with the connected computers ({@link LogExchange#checkClaim}). Nothing is proposed
 * through it any more; it answers other requests with {@code REPLICATION_REQUEST_UNSUPPORTED}.</p>
 */
public final class MemberReplication implements AutoCloseable {

    /**
     * Seconds between pulls when no notice arrives.
     */
    public static final int PULL_INTERVAL_SECONDS = LogExchange.PULL_INTERVAL_SECONDS;

    /**
     * Name of the file holding the last known state, next to the events folder.
     */
    public static final String STATUS_FILE = "replication.status";

    private static final System.Logger LOGGER = System.getLogger(MemberReplication.class.getName());

    /**
     * Whether the member is in step with an owner's computer it can reach.
     */
    public enum State {
        /**
         * No usable session to the owner's computer.
         */
        DISCONNECTED,
        /**
         * The last pull succeeded.
         */
        CONNECTED,
        /**
         * A log copy differs from the one the owner's computer serves. It is not pulled until a person repairs it.
         */
        DIVERGED
    }

    private final Path root;
    private final String leaderNodeId;
    private final LogExchange exchange;
    private volatile State state = State.DISCONNECTED;
    private volatile CoordinationHttpServer served;

    /**
     * Builds the member side for one project.
     *
     * @param root         the member's coordination folder
     * @param projectId    the project
     * @param leaderNodeId the owner's computer; only its session is accepted
     */
    public MemberReplication(Path root, UUID projectId, String leaderNodeId) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
        CoordinationNullException.requireNonNull(projectId, "project ID");
        this.leaderNodeId = CoordinationNullException.requireNonNull(leaderNodeId, "leader node ID");
        this.exchange = new LogExchange(root, projectId, leaderNodeId::equals, () -> {
        });
        exchange.watch((node, next) -> setState(switch (next) {
            case CONNECTED -> State.CONNECTED;
            case DISCONNECTED -> State.DISCONNECTED;
            case DIVERGED -> State.DIVERGED;
        }));
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
     * The handler to install on the Link session to the owner's computer: notices, heads and pulls.
     *
     * @return handler answering the owner's computer
     */
    public PeerSession.ApplicationStreamHandler handler() {
        return (remoteNodeId, payload) -> {
            if (!remoteNodeId.equals(leaderNodeId) || !ReplicationCodec.looksLike(payload)) {
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
            return CompletableFuture.completedFuture(refusal("REPLICATION_REQUEST_UNSUPPORTED"));
        };
    }

    /**
     * Attaches the authenticated session to the owner's computer.
     *
     * @param leader an authenticated, control-ready session
     * @throws IllegalArgumentException when its remote node is not the owner's computer
     */
    public void attach(PeerSession leader) {
        if (!leader.remoteNodeId().equals(leaderNodeId)) {
            throw new IllegalArgumentException("session peer is not the leader");
        }
        exchange.attach(leader);
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
     * Answers one request of an agent's process.
     *
     * @param request an encoded request
     * @return the encoded answer; never throws
     */
    public byte[] relay(byte[] request) {
        try {
            if (ReplicationCodec.decode(request) instanceof ReplicationMessage.ClaimSeen seen) {
                return exchange.checkClaim(seen);
            }
        } catch (CoordinationException malformed) {
            return refusal(malformed.code());
        }
        // Since step 4 a process appends to this computer's own log and never proposes or pulls.
        return refusal("REPLICATION_REQUEST_UNSUPPORTED");
    }

    /**
     * The state as of the last pull.
     *
     * @return the state
     */
    public State state() {
        return state;
    }

    /**
     * The exchange this side runs, for the claim lock between connected computers.
     *
     * @return the exchange
     */
    public LogExchange exchange() {
        return exchange;
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

    private static byte[] refusal(String code) {
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(new ProposalOutcome.Refused(code)));
    }

    /**
     * Stops exchanging and stops relaying. The session belongs to its owner and is not closed.
     */
    @Override
    public void close() {
        exchange.close();
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
