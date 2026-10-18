package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.synesis.coordination.exception.CoordinationException;

/**
 * The order in which this computer applied entries of its logs: {@code applied.order}, one line per entry,
 * appended and never rewritten (ADR-0098 step 4, designer item 12). A store opened later replays the same order,
 * so every process of this computer sees the same state, while another computer may apply the same logs in another
 * order. A line's position among the lines that took effect is the entry's local revision.
 *
 * <p>A line is {@code self <sequence>} for this computer's own log, {@code <node> <sequence>} for the copy of
 * another computer's log, and carries a third field, the code, when the entry counted for nothing. A line without
 * its newline was cut short by a writer that died before it finished, and nobody acted on it: readers ignore it, and
 * the next writer, which holds the order lock, removes it before writing.</p>
 */
final class AppliedOrder {

    /**
     * The name the own log goes by in a line.
     */
    static final String SELF = "self";

    /**
     * File name in the coordination folder.
     */
    static final String FILE = "applied.order";

    /**
     * Lock file name beside {@code append.lock}, held while lines are written.
     */
    static final String LOCK = "order.lock";

    /**
     * One line.
     *
     * @param log      {@link #SELF} or the node whose log the entry is in
     * @param sequence the entry's position in that log
     * @param code     empty when the entry applied; the rule it broke when it counted for nothing
     */
    record Line(String log, long sequence, String code) {

        boolean applied() {
            return code.isEmpty();
        }

        String text() {
            return log + " " + sequence + (code.isEmpty() ? "" : " " + code) + "\n";
        }
    }

    private final Path file;
    private long consumed;

    AppliedOrder(Path root) {
        this.file = root.resolve(FILE);
    }

    /**
     * Reads the complete lines written since the last read.
     *
     * @return the new lines, in order
     * @throws IOException when the file cannot be read or a complete line is malformed
     */
    List<Line> readNew() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] fresh;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= consumed) {
                return List.of();
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(size - consumed));
            channel.position(consumed);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Reads until the size seen above; a writer may be appending beyond it.
            }
            fresh = buffer.array();
        }
        int end = lastNewline(fresh, fresh.length);
        if (end < 0) {
            return List.of();
        }
        String text = new String(fresh, 0, end + 1, StandardCharsets.UTF_8);
        consumed += end + 1;
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            lines.add(parse(raw));
        }
        return lines;
    }

    /**
     * Appends lines and forces them to disk. The caller holds the order lock and has read every line before.
     *
     * @param lines the lines, in the order they were applied
     * @throws IOException when the write fails
     */
    void append(List<Line> lines) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            text.append(line.text());
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            if (channel.size() > consumed) {
                ByteBuffer rest = ByteBuffer.allocate(Math.toIntExact(channel.size() - consumed));
                channel.position(consumed);
                while (rest.hasRemaining() && channel.read(rest) > 0) {
                    // Reads the bytes beyond the last line this store read.
                }
                if (lastNewline(rest.array(), rest.position()) >= 0) {
                    // A complete line this store has not read: its caller did not catch up under the lock.
                    throw new CoordinationException("APPLIED_ORDER_NOT_READ");
                }
                // A line cut short by a writer that died; nobody read it.
                channel.truncate(consumed);
            }
            channel.position(consumed);
            ByteBuffer bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                if (channel.write(bytes) <= 0) {
                    throw new CoordinationException("applied order write made no progress");
                }
            }
            channel.force(true);
            consumed = channel.size();
        }
    }

    private static int lastNewline(byte[] bytes, int length) {
        for (int index = length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static Line parse(String raw) throws CoordinationException {
        String[] fields = raw.split(" ");
        try {
            if ((fields.length == 2 || fields.length == 3) && !fields[0].isEmpty()) {
                long sequence = Long.parseLong(fields[1]);
                if (sequence > 0) {
                    return new Line(fields[0], sequence, fields.length == 3 ? fields[2] : "");
                }
            }
        } catch (NumberFormatException malformed) {
            // Reported below.
        }
        throw new CoordinationException("invalid coordination event log");
    }
}
