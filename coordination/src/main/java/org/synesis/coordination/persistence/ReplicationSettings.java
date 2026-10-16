package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Which role this computer's copy of a project's log plays, kept in {@code replication.properties} beside the
 * {@code events} folder of the coordination folder (ADR-0098).
 *
 * <p>It is state of this computer, not of the project: the folder is excluded from Git and the file is never
 * sent to a peer, and it stays outside {@code events}, the only part that is replicated. Every process that opens
 * the log reads it, so a process cannot append locally by not knowing it is a member (invariant 1). A missing file
 * is a leader, which is every log today. A file that cannot be read or parsed is an error, never a leader.</p>
 *
 * <p>The format is strict and line based: {@code role=leader} or {@code role=member} first, then for a member one
 * {@code leader=<node ID>}, for a leader any number of {@code member=<node ID>}, and in either case at most one
 * {@code code.remote=<name>}: the name of the remote of the project's clone through which code moves between
 * computers. It is a name, never a URL, and is never guessed: a computer with a role and no code remote refuses
 * the operations that need one. Nothing else is accepted.</p>
 *
 * @param leaderNodeId the leader's node, for a member; null for the leader
 * @param members      nodes allowed to propose, for the leader; empty for a member
 * @param codeRemote   name of the remote code moves through; null when none is configured
 */
public record ReplicationSettings(String leaderNodeId, Set<String> members, String codeRemote) {

    /**
     * Name of the file in the coordination folder.
     */
    public static final String FILE = "replication.properties";

    /**
     * Code of the error raised for a file that cannot be read or understood.
     */
    public static final String INVALID = "REPLICATION_ROLE_INVALID";

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_NODE_ID_CHARS = 128;
    private static final String CODE_REMOTE_KEY = "code.remote";
    /** A remote name cannot begin with a dash, so it can never be taken for an option of the command it is given to. */
    private static final Pattern REMOTE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * Normalizes the sets.
     */
    public ReplicationSettings {
        members = Set.copyOf(members);
    }

    /**
     * Settings with no code remote, as every file was before {@code code.remote} existed.
     *
     * @param leaderNodeId the leader's node, for a member; null for the leader
     * @param members      nodes allowed to propose, for the leader
     */
    public ReplicationSettings(String leaderNodeId, Set<String> members) {
        this(leaderNodeId, members, null);
    }

    /**
     * Returns these settings with the remote that code moves through.
     *
     * @param remoteName name of a remote of the project's clone
     * @return the settings including the remote
     */
    public ReplicationSettings withCodeRemote(String remoteName) {
        return new ReplicationSettings(leaderNodeId, members, CoordinationNullException.requireNonNull(remoteName, "code remote"));
    }

    /**
     * Settings of the computer that decides every entry.
     *
     * @param members nodes allowed to propose
     * @return leader settings
     */
    public static ReplicationSettings leader(Set<String> members) {
        return new ReplicationSettings(null, Set.copyOf(CoordinationNullException.requireNonNull(members, "members")));
    }

    /**
     * Settings of a computer that proposes to a leader.
     *
     * @param leaderNodeId the leader's node
     * @return member settings
     */
    public static ReplicationSettings member(String leaderNodeId) {
        return new ReplicationSettings(CoordinationNullException.requireNonNull(leaderNodeId, "leader node ID"),
                Set.of());
    }

    /**
     * Tells whether these settings make the log a member's.
     *
     * @return true for a member
     */
    public boolean isMember() {
        return leaderNodeId != null;
    }

    /**
     * Reads the settings of a coordination folder.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @return the settings, or empty when there is no file
     * @throws CoordinationException {@link #INVALID} when the file cannot be read or does not follow the format
     */
    public static Optional<ReplicationSettings> read(Path coordinationRoot) throws CoordinationException {
        Path file = CoordinationNullException.requireNonNull(coordinationRoot, "root").resolve(FILE);
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new CoordinationException(INVALID);
            }
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException unreadable) {
            if (unreadable instanceof CoordinationException coded) {
                throw coded;
            }
            throw new CoordinationException(INVALID, unreadable);
        }
        return Optional.of(parse(new String(bytes, StandardCharsets.UTF_8)));
    }

    private static ReplicationSettings parse(String text) throws CoordinationException {
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty()) {
            throw new CoordinationException(INVALID);
        }
        String role = value(lines[0], "role");
        String leader = null;
        String codeRemote = null;
        Set<String> members = new LinkedHashSet<>();
        for (int index = 1; index < lines.length - 1; index++) {
            if (lines[index].startsWith(CODE_REMOTE_KEY + "=")) {
                String name = value(lines[index], CODE_REMOTE_KEY);
                if (codeRemote != null || !REMOTE_NAME.matcher(name).matches()) {
                    throw new CoordinationException(INVALID);
                }
                codeRemote = name;
            } else if (role.equals("member") && leader == null) {
                leader = nodeId(value(lines[index], "leader"));
            } else if (role.equals("leader")) {
                if (!members.add(nodeId(value(lines[index], "member")))) {
                    throw new CoordinationException(INVALID);
                }
            } else {
                throw new CoordinationException(INVALID);
            }
        }
        return switch (role) {
            case "member" -> {
                if (leader == null) {
                    throw new CoordinationException(INVALID);
                }
                yield new ReplicationSettings(leader, Set.of(), codeRemote);
            }
            case "leader" -> new ReplicationSettings(null, members, codeRemote);
            default -> throw new CoordinationException(INVALID);
        };
    }

    private static String value(String line, String key) throws CoordinationException {
        if (!line.startsWith(key + "=")) {
            throw new CoordinationException(INVALID);
        }
        return line.substring(key.length() + 1);
    }

    private static String nodeId(String value) throws CoordinationException {
        if (value.isEmpty() || value.length() > MAX_NODE_ID_CHARS || value.chars().anyMatch(Character::isWhitespace)
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new CoordinationException(INVALID);
        }
        return value;
    }

    /**
     * Writes these settings, replacing any earlier file in one step.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @throws IOException when the file cannot be written
     */
    public void write(Path coordinationRoot) throws IOException {
        StringBuilder text = new StringBuilder(isMember() ? "role=member\nleader=" + leaderNodeId + "\n"
                : "role=leader\n");
        members.stream().sorted().forEach(member -> text.append("member=").append(member).append('\n'));
        if (codeRemote != null) {
            text.append(CODE_REMOTE_KEY).append('=').append(codeRemote).append('\n');
        }
        parse(text.toString());
        replace(coordinationRoot, FILE, text);
    }

    /**
     * Replaces a file of the coordination folder in one step, so a reader sees a complete old or a complete new file.
     */
    static void replace(Path coordinationRoot, String name, CharSequence text) throws IOException {
        Files.createDirectories(coordinationRoot);
        Path temporary = coordinationRoot.resolve(name + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, coordinationRoot.resolve(name), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, coordinationRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
