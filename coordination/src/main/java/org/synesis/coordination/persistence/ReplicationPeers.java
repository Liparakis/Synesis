package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Which computers this computer's Link sessions were made with, kept in {@code replication.peers} beside
 * {@code replication.properties}, so that a restart of the coordination server does not end the project's
 * session (ADR-0098 step 2: nobody should create a new invitation because a process restarted).
 *
 * <p>It is state of this computer, like the role file: excluded from Git, never sent to a peer. It records only
 * what a restart needs and grants nothing by itself: a recorded node is dialed or admitted again only while the
 * role still names it (the leader's current members, a member's leader), and the Link handshake still verifies
 * both identities. Two kinds of line, in any order, each at most once:</p>
 *
 * <ul>
 * <li>{@code dial=<node ID> <invitation link>}: this computer dialed that host through that invitation, whose
 * endpoints it dials again; the invitation's expiry no longer matters, its signature still does.</li>
 * <li>{@code host=<port> <node ID>}: this computer hosted an invitation on that UDP port and admitted that node
 * through it; after a restart it listens on the same port and admits exactly the nodes recorded for it.</li>
 * </ul>
 *
 * <p>A file that cannot be read or does not follow the format is an error to {@link #read}, and the caller restores
 * nothing from it (fails closed: no automatic admission); a write replaces it.</p>
 *
 * @param dialed node ID of the host to the invitation link that reaches it
 * @param hosted UDP port to the nodes admitted through the invitation hosted on it
 */
public record ReplicationPeers(Map<String, String> dialed, Map<Integer, Set<String>> hosted) {

    /**
     * Name of the file in the coordination folder.
     */
    public static final String FILE = "replication.peers";

    /**
     * Code of the error raised for a file that cannot be read or understood.
     */
    public static final String INVALID = "REPLICATION_PEERS_INVALID";

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_NODE_ID_CHARS = 128;
    private static final int MAX_LINK_CHARS = 4096;
    private static final String DIAL = "dial=";
    private static final String HOST = "host=";

    /**
     * Copies the maps so that a record never changes under its reader.
     */
    public ReplicationPeers {
        Map<String, String> dials = new TreeMap<>(dialed);
        Map<Integer, Set<String>> hosts = new TreeMap<>();
        hosted.forEach((port, nodes) -> hosts.put(port, Collections.unmodifiableSet(new LinkedHashSet<>(nodes))));
        dialed = Collections.unmodifiableMap(dials);
        hosted = Collections.unmodifiableMap(hosts);
    }

    /**
     * Records nothing.
     *
     * @return the empty record
     */
    public static ReplicationPeers none() {
        return new ReplicationPeers(Map.of(), Map.of());
    }

    /**
     * Reads the record of a coordination folder.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @return what the file records; empty when there is no file
     * @throws CoordinationException {@link #INVALID} when the file cannot be read or does not follow the format
     */
    public static ReplicationPeers read(Path coordinationRoot) throws CoordinationException {
        Path file = CoordinationNullException.requireNonNull(coordinationRoot, "root").resolve(FILE);
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new CoordinationException(INVALID);
            }
            return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return none();
        } catch (CoordinationException invalid) {
            throw invalid;
        } catch (IOException unreadable) {
            throw new CoordinationException(INVALID, unreadable);
        }
    }

    private static ReplicationPeers parse(String text) throws CoordinationException {
        if (!text.isEmpty() && !text.endsWith("\n")) {
            throw new CoordinationException(INVALID);
        }
        Map<String, String> dialed = new LinkedHashMap<>();
        Map<Integer, Set<String>> hosted = new LinkedHashMap<>();
        for (String line : text.isEmpty() ? new String[0] : text.substring(0, text.length() - 1).split("\n", -1)) {
            if (line.startsWith(DIAL)) {
                String[] parts = fields(line.substring(DIAL.length()));
                if (!isToken(parts[1], MAX_LINK_CHARS) || dialed.put(nodeId(parts[0]), parts[1]) != null) {
                    throw new CoordinationException(INVALID);
                }
            } else if (line.startsWith(HOST)) {
                String[] parts = fields(line.substring(HOST.length()));
                if (!hosted.computeIfAbsent(port(parts[0]), ignored -> new LinkedHashSet<>()).add(nodeId(parts[1]))) {
                    throw new CoordinationException(INVALID);
                }
            } else {
                throw new CoordinationException(INVALID);
            }
        }
        return new ReplicationPeers(dialed, hosted);
    }

    private static String[] fields(String rest) throws CoordinationException {
        String[] parts = rest.split(" ", -1);
        if (parts.length != 2) {
            throw new CoordinationException(INVALID);
        }
        return parts;
    }

    private static boolean isToken(String value, int maxChars) {
        return !value.isEmpty() && value.length() <= maxChars && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private static String nodeId(String value) throws CoordinationException {
        if (!isToken(value, MAX_NODE_ID_CHARS)) {
            throw new CoordinationException(INVALID);
        }
        return value;
    }

    private static int port(String value) throws CoordinationException {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new CoordinationException(INVALID);
            }
            return port;
        } catch (NumberFormatException notANumber) {
            throw new CoordinationException(INVALID, notANumber);
        }
    }

    /**
     * Records that this computer dialed a host through an invitation.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param hostNodeId       the host's node ID
     * @param invitationLink   the invitation link, which has no whitespace
     * @throws IOException when the file cannot be written
     */
    public static void rememberDialed(Path coordinationRoot, String hostNodeId, String invitationLink) throws IOException {
        if (!isToken(hostNodeId, MAX_NODE_ID_CHARS) || !isToken(invitationLink, MAX_LINK_CHARS)) {
            throw new CoordinationException(INVALID);
        }
        update(coordinationRoot, current -> {
            Map<String, String> dialed = new LinkedHashMap<>(current.dialed());
            dialed.put(hostNodeId, invitationLink);
            return new ReplicationPeers(dialed, current.hosted());
        });
    }

    /**
     * Records that this computer admitted a node through the invitation it hosts on a port.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param port             the UDP port the invitation is hosted on
     * @param nodeId           the node admitted
     * @throws IOException when the file cannot be written
     */
    public static void rememberHosted(Path coordinationRoot, int port, String nodeId) throws IOException {
        if (port < 1 || port > 65_535 || !isToken(nodeId, MAX_NODE_ID_CHARS)) {
            throw new CoordinationException(INVALID);
        }
        update(coordinationRoot, current -> {
            Map<Integer, Set<String>> hosted = new LinkedHashMap<>();
            current.hosted().forEach((known, nodes) -> hosted.put(known, new LinkedHashSet<>(nodes)));
            hosted.computeIfAbsent(port, ignored -> new LinkedHashSet<>()).add(nodeId);
            return new ReplicationPeers(current.dialed(), hosted);
        });
    }

    /**
     * Drops every record of a node: it is neither dialed nor admitted again after a restart.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param nodeId           the node
     * @throws IOException when the file cannot be written
     */
    public static void forget(Path coordinationRoot, String nodeId) throws IOException {
        update(coordinationRoot, current -> current.without(node -> node.equals(nodeId)));
    }

    /**
     * Drops every record of an invitation this computer hosted, keeping what it dialed: a restart hosts nothing.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @throws IOException when the file cannot be written
     */
    public static void forgetHosted(Path coordinationRoot) throws IOException {
        update(coordinationRoot, current -> new ReplicationPeers(current.dialed(), Map.of()));
    }

    /**
     * Drops the records of every node but those given.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param kept             the nodes whose records stay
     * @throws IOException when the file cannot be written
     */
    public static void retainOnly(Path coordinationRoot, Set<String> kept) throws IOException {
        update(coordinationRoot, current -> current.without(node -> !kept.contains(node)));
    }

    private ReplicationPeers without(java.util.function.Predicate<String> dropped) {
        Map<String, String> dials = new LinkedHashMap<>(dialed);
        dials.keySet().removeIf(dropped);
        Map<Integer, Set<String>> hosts = new LinkedHashMap<>();
        hosted.forEach((port, nodes) -> {
            Set<String> remaining = new LinkedHashSet<>(nodes);
            remaining.removeIf(dropped);
            if (!remaining.isEmpty()) {
                hosts.put(port, remaining);
            }
        });
        return new ReplicationPeers(dials, hosts);
    }

    /** Serialized: the coordination server's threads write it as sessions are admitted. */
    private static synchronized void update(Path coordinationRoot, UnaryOperator<ReplicationPeers> change) throws IOException {
        ReplicationPeers current;
        try {
            current = read(coordinationRoot);
        } catch (CoordinationException unusable) {
            current = none();
        }
        ReplicationPeers changed = change.apply(current);
        if (changed.equals(current) && Files.exists(coordinationRoot.resolve(FILE))) {
            return;
        }
        if (changed.dialed().isEmpty() && changed.hosted().isEmpty()) {
            Files.deleteIfExists(coordinationRoot.resolve(FILE));
            return;
        }
        StringBuilder text = new StringBuilder();
        changed.dialed().forEach((node, link) -> text.append(DIAL).append(node).append(' ').append(link).append('\n'));
        changed.hosted().forEach((port, nodes) -> nodes.stream().sorted()
                .forEach(node -> text.append(HOST).append(port).append(' ').append(node).append('\n')));
        ReplicationSettings.replace(coordinationRoot, FILE, text);
    }
}
