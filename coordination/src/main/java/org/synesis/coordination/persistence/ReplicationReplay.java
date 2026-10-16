package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The handshakes a hosting computer has accepted, kept in {@code replication.replay} beside
 * {@code replication.peers}, so that a host which listens again on its old port after a restart still refuses a
 * handshake it accepted before (ADR-0098 step 2). Local state of this computer, never sent to a peer.
 *
 * <p>One fingerprint per line, each a token of at most 256 characters, none twice, at most {@code capacity} lines:
 * exactly the contents of the in-memory replay guard. A file that cannot be read or does not follow the format is an
 * error to {@link #read}; the caller then resumes no hosting (fail closed). A write replaces the whole file in one
 * step, so a reader sees a complete old or a complete new file.</p>
 */
public final class ReplicationReplay {

    /**
     * Name of the file in the coordination folder.
     */
    public static final String FILE = "replication.replay";

    /**
     * Code of the error raised for a file that cannot be read or understood.
     */
    public static final String INVALID = "REPLICATION_REPLAY_INVALID";

    private static final int MAX_FINGERPRINT_CHARS = 256;

    private ReplicationReplay() {
    }

    /**
     * Reads the accepted fingerprints of a coordination folder.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param capacity         the most fingerprints the guard holds
     * @return the fingerprints in the file, or empty when there is no file
     * @throws CoordinationException {@link #INVALID} when the file cannot be read or does not follow the format
     */
    public static Optional<Set<String>> read(Path coordinationRoot, int capacity) throws CoordinationException {
        Path file = CoordinationNullException.requireNonNull(coordinationRoot, "root").resolve(FILE);
        String text;
        try {
            if (Files.size(file) > (long) capacity * (MAX_FINGERPRINT_CHARS + 1)) {
                throw new CoordinationException(INVALID);
            }
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException unreadable) {
            throw new CoordinationException(INVALID, unreadable);
        }
        if (!text.isEmpty() && !text.endsWith("\n")) {
            throw new CoordinationException(INVALID);
        }
        Set<String> fingerprints = new LinkedHashSet<>();
        for (String line : text.isEmpty() ? new String[0] : text.substring(0, text.length() - 1).split("\n", -1)) {
            boolean token = !line.isEmpty() && line.length() <= MAX_FINGERPRINT_CHARS
                    && line.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
            if (!token || !fingerprints.add(line) || fingerprints.size() > capacity) {
                throw new CoordinationException(INVALID);
            }
        }
        return Optional.of(fingerprints);
    }

    /**
     * Replaces the file with the given fingerprints.
     *
     * @param coordinationRoot the folder holding {@code events}
     * @param fingerprints     every fingerprint accepted so far
     * @throws IOException when the file cannot be written
     */
    public static void write(Path coordinationRoot, Collection<String> fingerprints) throws IOException {
        StringBuilder text = new StringBuilder();
        fingerprints.forEach(fingerprint -> text.append(fingerprint).append('\n'));
        ReplicationSettings.replace(coordinationRoot, FILE, text);
    }
}
