package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * What this computer is in a shared project: the owner's computer, which merges every lane until ADR-0098 step 5,
 * or a member that leaves merging to it. Since step 4 both append to their own log alone; the role decides only who
 * merges and whose entries keep the owner's exemption from attribution. A log with no role registered is the
 * owner's, which is every log of a project on one computer.
 *
 * <p>The role of a computer is kept in {@link ReplicationSettings}, so that every process that opens the log
 * resolves the same one. A file that cannot be read is a role that refuses every append ({@link #refusing}); it is
 * never taken for the owner's. Tests may register a role in memory instead; that takes precedence.</p>
 *
 * @param leaderNodeId the owner's computer, for a member; null for the owner's computer itself
 * @param refusal      the code every append fails with, for a role whose settings cannot be read; null otherwise
 */
public record ReplicationRole(String leaderNodeId, String refusal) {

    private static final ReplicationRole LEADER = new ReplicationRole(null, null);

    private static final ConcurrentHashMap<Path, ReplicationRole> ROLES = new ConcurrentHashMap<>();

    /**
     * The role of a log nobody registered: the owner's computer.
     *
     * @return the leader role
     */
    public static ReplicationRole leader() {
        return LEADER;
    }

    /**
     * A member of the project whose owner's computer is another.
     *
     * @param leaderNodeId the owner's computer
     * @return the member role
     */
    public static ReplicationRole memberOf(String leaderNodeId) {
        return new ReplicationRole(CoordinationNullException.requireNonNull(leaderNodeId, "leader node ID"), null);
    }

    /**
     * A role that fails closed: every append is refused with the code. It is what a log resolves to when its
     * settings cannot be read, so it neither merges nor writes.
     *
     * @param code the code every append fails with
     * @return the refusing role
     */
    static ReplicationRole refusing(String code) {
        return new ReplicationRole("unknown", CoordinationNullException.requireNonNull(code, "code"));
    }

    /**
     * Tells whether this computer leaves merging to another.
     *
     * @return true for a member
     */
    public boolean isMember() {
        return leaderNodeId != null;
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
        CoordinationNullException.requireNonNull(projectId, "project ID");
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
            return memberOf(settings.get().leaderNodeId());
        } catch (CoordinationException invalid) {
            return refusing(invalid.code());
        }
    }

    private static Path key(Path coordinationRoot) throws IOException {
        Files.createDirectories(CoordinationNullException.requireNonNull(coordinationRoot, "root"));
        return coordinationRoot.toRealPath();
    }
}
