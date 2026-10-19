package org.synesis.workspace.lifecycle.lease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which session of a connection replaced which: the lineage that proves two sessions are one agent's. A connection has
 * one binding and one lease at a time, so a fresh session overwrites both and nothing else says whose predecessor it
 * was. Written by the binding service at the moment it replaces a connection's session, one line per replacement, and
 * read when a decision must carry an obligation or a bar from one session of an agent to its next: a review owed to the
 * old session goes to the new one, and the new session of a snapshot's author is still its author.
 *
 * <p>Local to this computer, like the leases: the log knows sessions only as participants, and a session no entry of
 * this computer introduced has no other record. A line is written before the binding it describes, so a crash between
 * the two leaves a line naming a session that never ran, which proves nothing about anyone.</p>
 */
public final class SessionSuccessions {

    private static final String FILE = "successions.txt";

    private SessionSuccessions() {
    }

    /**
     * Records that {@code successor} replaced {@code predecessor} on the same connection.
     *
     * @param controlRoot control project root path
     * @param predecessor the session ID that was replaced
     * @param successor   the session ID that replaced it
     * @throws IOException when the line cannot be written
     */
    public static void record(Path controlRoot, String predecessor, String successor) throws IOException {
        Objects.requireNonNull(predecessor, "predecessor");
        Objects.requireNonNull(successor, "successor");
        Path directory = SessionLeaseStore.resolveLeasesDirectory(controlRoot.toAbsolutePath().normalize());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(FILE), successor + " " + predecessor + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads every recorded replacement.
     *
     * @param controlRoot control project root path
     * @return each successor session ID with the session ID it replaced; none recorded gives an empty map, and a file that
     *         cannot be read, or holds a line that is not a pair, gives empty, since a lineage that may be missing
     *         proves nothing
     */
    public static Optional<Map<String, String>> predecessors(Path controlRoot) {
        Path file = SessionLeaseStore.resolveLeasesDirectory(controlRoot.toAbsolutePath().normalize()).resolve(FILE);
        if (!Files.exists(file)) {
            return Optional.of(Map.of());
        }
        Map<String, String> predecessors = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] pair = line.trim().split(" ");
                if (pair.length != 2) {
                    return Optional.empty();
                }
                predecessors.put(pair[0], pair[1]);
            }
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        return Optional.of(Map.copyOf(predecessors));
    }
}
