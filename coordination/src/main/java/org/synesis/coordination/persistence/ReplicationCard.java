package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Where this computer's member card listens: {@code replication.card} beside {@code replication.peers}, one line
 * {@code <port> <invitation link>} (ADR-0098 step 6, designer item 13). A restart listens again on that port, so the
 * card already published in the log reaches this computer again. Local state of this computer, never sent to a peer.
 * A file that cannot be read restores nothing: a new card is hosted and published.
 *
 * @param port the UDP port the card's invitation is hosted on
 * @param link the card's invitation link
 */
public record ReplicationCard(int port, String link) {

    /** Name of the file in the coordination folder. */
    public static final String FILE = "replication.card";

    private static final int MAX_LINK_CHARS = 4096;

    /**
     * Validates the fields.
     */
    public ReplicationCard {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port out of range");
        }
        CoordinationNullException.requireNonNull(link, "link");
        if (link.isEmpty() || link.length() > MAX_LINK_CHARS || link.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("link is not one token");
        }
    }

    /**
     * Reads this computer's card.
     *
     * @param coordinationRoot the coordination folder
     * @return the card, or empty when there is none or it cannot be read
     */
    public static Optional<ReplicationCard> read(Path coordinationRoot) {
        try {
            String[] parts = Files.readString(coordinationRoot.resolve(FILE), StandardCharsets.UTF_8).strip().split(" ", -1);
            return parts.length == 2 ? Optional.of(new ReplicationCard(Integer.parseInt(parts[0]), parts[1])) : Optional.empty();
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Writes this computer's card, replacing any earlier one in one step.
     *
     * @param coordinationRoot the coordination folder
     * @throws IOException when the file cannot be written
     */
    public void write(Path coordinationRoot) throws IOException {
        ReplicationSettings.replace(coordinationRoot, FILE, port + " " + link + "\n");
    }
}
