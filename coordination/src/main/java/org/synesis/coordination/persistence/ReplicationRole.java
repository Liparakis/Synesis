package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * What a project's coordination log is on this computer: the leader that decides every entry, or a member that
 * proposes entries to the leader and keeps a copy (ADR-0098). A log with no role registered is a leader, which is
 * every log today.
 *
 * <p>The role of a computer is kept in {@link ReplicationSettings}, so that every process that opens the log
 * resolves the same one: a member's log, opened by an agent's own process, reaches the leader through its
 * computer's coordination server ({@link LoopbackExchange}) and never appends on its own. A file that cannot be
 * read is a role that refuses every append and every pull ({@link #refusing}); it is never taken for a leader.
 * Tests may register a role in memory instead; that takes precedence.</p>
 *
 * @param leaderNodeId the leader's node, for a member; null for the leader
 * @param channel      the connection to the leader, for a member; null for the leader
 */
public record ReplicationRole(String leaderNodeId, LeaderChannel channel) {

    private static final ReplicationRole LEADER = new ReplicationRole(null, null);

    private static final ConcurrentHashMap<Path, ReplicationRole> ROLES = new ConcurrentHashMap<>();

    /**
     * The role of a log nobody registered: it decides its own entries.
     *
     * @return the leader role
     */
    public static ReplicationRole leader() {
        return LEADER;
    }

    /**
     * A member of the project led by another computer.
     *
     * @param leaderNodeId the leader's node
     * @param channel      the connection to the leader
     * @return the member role
     */
    public static ReplicationRole memberOf(String leaderNodeId, LeaderChannel channel) {
        return new ReplicationRole(CoordinationNullException.requireNonNull(leaderNodeId, "leader node ID"),
                CoordinationNullException.requireNonNull(channel, "leader channel"));
    }

    /**
     * A role that fails closed: every proposal is refused with the code and every pull fails with it. It is what a
     * log resolves to when its settings cannot be read, so it can neither decide nor propose.
     *
     * @param code the code every append and pull fails with
     * @return the refusing role
     */
    static ReplicationRole refusing(String code) {
        return memberOf("unknown", new LeaderChannel() {
            @Override
            public ProposalOutcome propose(byte[] encodedEvent) {
                return new ProposalOutcome.Refused(code);
            }

            @Override
            public List<byte[]> pull(long after, int maxEntries, int maxBytes) throws IOException {
                throw new CoordinationException(code);
            }
        });
    }

    /**
     * Tells whether this log proposes to a leader instead of deciding.
     *
     * @return true for a member
     */
    public boolean isMember() {
        return channel != null;
    }

    /**
     * Makes the log in a coordination folder a member's, for this process.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param role             the role; the leader role removes a registration
     * @throws IOException when the folder cannot be created or resolved
     */
    public static void register(Path coordinationRoot, ReplicationRole role) throws IOException {
        Path key = key(coordinationRoot);
        if (role.isMember()) {
            ROLES.put(key, role);
        } else {
            ROLES.remove(key);
        }
    }

    /**
     * Returns the role of the log in a coordination folder: the registered one, else the one in its settings file,
     * else the leader.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param projectId        the project the log belongs to
     * @return the role; a refusing role when the settings file cannot be read
     * @throws IOException when the folder cannot be resolved
     */
    static ReplicationRole of(Path coordinationRoot, UUID projectId) throws IOException {
        Path key = key(coordinationRoot);
        ReplicationRole registered = ROLES.get(key);
        if (registered != null) {
            return registered;
        }
        try {
            Optional<ReplicationSettings> settings = ReplicationSettings.read(key);
            if (settings.isEmpty() || !settings.get().isMember()) {
                return LEADER;
            }
            return memberOf(settings.get().leaderNodeId(),
                    new ExchangeLeaderChannel(projectId, new LoopbackExchange(key)));
        } catch (CoordinationException invalid) {
            return refusing(invalid.code());
        }
    }

    private static Path key(Path coordinationRoot) throws IOException {
        Files.createDirectories(CoordinationNullException.requireNonNull(coordinationRoot, "root"));
        return coordinationRoot.toRealPath();
    }
}
