package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The last {@value #MAX_LINES} things that happened to a computer's Link sessions to its peers, kept in
 * {@value #FILE} next to the events folder, so that a person who finds replication stopped can see why without a
 * debugger and without the log of a process that has since been restarted (2026-10-16: a link dropped about fifteen
 * minutes into a real run and nothing recorded the cause).
 *
 * <p>One line per event, {@code <instant> <event> key=value ...}. Writers are the coordination server's own threads,
 * which this class serializes; the file is rewritten whole through a temporary file and moved into place, so a
 * reader sees a complete old or a complete new file. A line that cannot be written is lost, never thrown: the journal
 * explains the replication, it must not stop it. It decides no session; the one reader that acts on it is a run of
 * agents deciding whether to keep waiting for another computer ({@link #connectedPeers}) and whom to resume when one
 * comes back ({@link #connectedSince}).
 */
public final class ReplicationJournal {

    /**
     * Name of the journal, next to the events folder.
     */
    public static final String FILE = "replication.log";

    /**
     * Lines kept; older ones are dropped when a new one is added.
     */
    public static final int MAX_LINES = 100;

    private static final System.Logger LOGGER = System.getLogger(ReplicationJournal.class.getName());
    private static final String CONNECTED = "CONNECTED";
    private static final String DISCONNECT = "DISCONNECT";

    private final Path root;

    /**
     * Opens the journal of one coordination folder.
     *
     * @param root the coordination folder
     */
    public ReplicationJournal(Path root) {
        this.root = root;
    }

    /**
     * Adds one event, stamped with the current time.
     *
     * @param event the event name and its {@code key=value} details, on one line
     */
    public void record(String event) {
        record(Instant.now(), event);
    }

    synchronized void record(Instant at, String event) {
        try {
            List<String> lines = new ArrayList<>(read(root));
            lines.add(at + " " + event.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip());
            List<String> kept = lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size());
            Path temporary = root.resolve(FILE + ".tmp");
            Files.write(temporary, kept, StandardCharsets.UTF_8);
            Files.move(temporary, root.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException unwritable) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not record a replication event.", unwritable);
        }
    }

    /**
     * Reads the kept lines, oldest first.
     *
     * @param root the coordination folder
     * @return the lines; empty when there is no journal
     */
    public static List<String> read(Path root) {
        try {
            return Files.readAllLines(root.resolve(FILE), StandardCharsets.UTF_8);
        } catch (IOException none) {
            return List.of();
        }
    }

    /**
     * The peers whose newest session line is a start. The end of a session that a newer one to the same peer replaced
     * says nothing about the peer. A process that dies leaves its last start in place, so a reader that waits on this
     * must bound the wait itself.
     *
     * @param root the coordination folder
     * @return node IDs of the peers connected as far as the journal says; empty when there is no journal
     */
    public static Set<String> connectedPeers(Path root) {
        return connectedSince(root).keySet();
    }

    /**
     * When the current session with each connected peer began, by the rule of {@link #connectedPeers}. Nothing in
     * the event log records that a computer came back, yet what it holds (a snapshot to review, the commits
     * {@code main} moved to) is refused as unreachable while it is away; a run of agents reads this to resume an
     * agent that stopped before the computer came back (ADR-0098 step 4, item 23). A start whose time cannot be read
     * counts as the oldest possible, so it resumes nobody.
     *
     * @param root the coordination folder
     * @return each connected peer's node ID and the start of its current session; empty when there is no journal
     */
    public static Map<String, Instant> connectedSince(Path root) {
        Map<String, Instant> connected = new HashMap<>();
        for (String line : read(root)) {
            String[] parts = line.split(" ");
            if (parts.length < 3 || !parts[2].startsWith("peer=") || line.endsWith(" replaced=true")) {
                continue;
            }
            String peer = parts[2].substring("peer=".length());
            if (parts[1].equals(CONNECTED)) {
                connected.put(peer, startOf(parts[0]));
            } else if (parts[1].equals(DISCONNECT)) {
                connected.remove(peer);
            }
        }
        return Map.copyOf(connected);
    }

    private static Instant startOf(String stamp) {
        try {
            return Instant.parse(stamp);
        } catch (DateTimeParseException unreadable) {
            return Instant.EPOCH;
        }
    }

    /**
     * The most recent end of a session, as {@code <reason> <instant>} followed by the rest of the line.
     *
     * @param root the coordination folder
     * @return for example {@code LIVENESS_EXPIRED 2026-10-16T14:02:11Z peer=... liveness=EXPIRED lasted=912s}, or
     * empty when no session has ended
     */
    public static Optional<String> lastDisconnect(Path root) {
        List<String> lines = read(root);
        for (int index = lines.size() - 1; index >= 0; index--) {
            String[] parts = lines.get(index).split(" ", 3);
            if (parts.length == 3 && parts[1].equals(DISCONNECT)) {
                String detail = parts[2];
                String reason = detail.contains("reason=")
                        ? detail.substring(detail.indexOf("reason=") + "reason=".length()).split(" ", 2)[0] : "UNKNOWN";
                return Optional.of(reason + " " + parts[0] + " " + detail);
            }
        }
        return Optional.empty();
    }
}
