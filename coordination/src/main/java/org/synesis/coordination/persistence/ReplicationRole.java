package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * What a project's coordination log is on this computer: the leader that decides every entry, or a member that
 * proposes entries to the leader and keeps a copy (ADR-0098). A log with no role registered is a leader, which is
 * every log today.
 *
 * <p>The role is looked up by coordination folder, not passed to each store, because the services open a fresh
 * {@link PredictionEventStore} for every decision and know only the folder. It lives in memory for the process
 * because a member's {@link LeaderChannel} is a live connection; the setup that makes a computer a member
 * registers it again at start. A persisted member list and leader identity belong to the Link slice that has a
 * connection to restore.</p>
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
     * Returns the role of the log in a coordination folder.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @return the registered role, or the leader role
     * @throws IOException when the folder cannot be resolved
     */
    static ReplicationRole of(Path coordinationRoot) throws IOException {
        return ROLES.getOrDefault(key(coordinationRoot), LEADER);
    }

    private static Path key(Path coordinationRoot) throws IOException {
        Files.createDirectories(CoordinationNullException.requireNonNull(coordinationRoot, "root"));
        return coordinationRoot.toRealPath();
    }
}
