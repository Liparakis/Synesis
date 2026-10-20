package org.synesis.coordination.persistence;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;

/**
 * A read-only listing of every log one computer holds, in the order it applied them, for {@code synesis log dump}
 * (ADR-0102 item 2). It reads {@code events/}, {@code peers/<node>/events/} and {@code applied.order} directly and
 * takes no lock, so it works while the project's runtime is stopped and never holds up a running one. It writes
 * nothing, not even the temporary-file cleanup an ordinary open does.
 *
 * <p>The listing is for a person finding a defect, so it never stops at one: an entry it cannot read, an entry of a
 * type it does not know, and a line of the applied order it cannot parse each become a row or a problem line, and the
 * rest still prints. What the projections derive is read from the same replay a running computer does
 * ({@link PredictionEventStore}); when that replay fails, the reason is the summary.</p>
 */
public final class LogDump {

    private static final String UNKNOWN = "UNKNOWN";
    private static final String UNREADABLE = "UNREADABLE";
    private static final int MAGIC = 0x53434531;
    private static final int MAX_ACTOR_BYTES = 8192;

    private LogDump() {
    }

    /**
     * One fact of the summary, printed as {@code KEY=VALUE}.
     *
     * @param key   the fact's name
     * @param value its value, on one line
     */
    public record Fact(String key, String value) {
    }

    /**
     * One entry of one log.
     *
     * @param position the entry's local revision; 0 when it took none (counted for nothing, or not applied here)
     * @param log      {@code self} for this computer's own log, otherwise the node whose copy holds the entry
     * @param writer   the node that wrote the entry; {@code ?} when it cannot be read
     * @param sequence the entry's position in its log
     * @param type     the entry type, {@link #UNKNOWN} for a type this build does not know, {@link #UNREADABLE}
     *                 for an entry that cannot be read
     * @param raw      the wire code of an unknown type; empty otherwise
     * @param note     why the entry has no position, or empty
     * @param fields   the fields that identify what the entry is about, in print order
     */
    public record Entry(long position, String log, String writer, long sequence, String type, String raw, String note,
                        Map<String, String> fields) {

        /**
         * Renders the entry as {@code key=value} words: {@code pos log writer seq type}, then the note and the
         * fields. An unknown type renders as {@code UNKNOWN type=<raw>}.
         */
        @Override
        public String toString() {
            StringBuilder line = new StringBuilder("pos=").append(position > 0 ? String.valueOf(position) : "-")
                    .append(" log=").append(log).append(" writer=").append(writer).append(" seq=").append(sequence)
                    .append(type.equals(UNKNOWN) ? " UNKNOWN type=" + raw : " type=" + type);
            if (!note.isEmpty()) {
                line.append(' ').append(note);
            }
            fields.forEach((key, value) -> line.append(' ').append(key).append('=').append(value));
            return line.toString();
        }
    }

    /**
     * Everything the dump found.
     *
     * @param entries  every entry in the order this computer applied them, then entries that took no place
     * @param problems one line per thing that could not be read or does not hold: {@code <log> <sequence> <CODE>}
     * @param summary  what the projections derive, or why they could not be read
     */
    public record Dump(List<Entry> entries, List<String> problems, List<Fact> summary) {
    }

    /**
     * Keeps the entries of one type.
     *
     * @param entries a dump's entries
     * @param type    a type name, any case; {@code UNKNOWN} matches the entries of unknown types
     * @return the matching entries, in order
     */
    public static List<Entry> only(List<Entry> entries, String type) {
        String wanted = type.toUpperCase(Locale.ROOT);
        return entries.stream().filter(entry -> entry.type().equals(wanted)).toList();
    }

    /**
     * When entries of another computer's log last arrived on this one: the newest write into this computer's copy of
     * that log. A pull that brought nothing new writes nothing, so this is the last time something came, not the last
     * time the computers talked.
     *
     * @param root the project's coordination folder
     * @param node the other computer's node
     * @return the time, or empty when this computer holds no entry of that log
     */
    public static java.util.Optional<java.time.Instant> lastArrival(Path root, String node) {
        if (!node.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
            return java.util.Optional.empty();
        }
        Path folder = root.resolve("peers").resolve(node).resolve("events");
        if (!Files.isDirectory(folder)) {
            return java.util.Optional.empty();
        }
        try (var listing = Files.list(folder)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".sce")).map(path -> {
                try {
                    return Files.getLastModifiedTime(path).toInstant();
                } catch (IOException unreadable) {
                    return java.time.Instant.EPOCH;
                }
            }).max(java.util.Comparator.naturalOrder()).filter(time -> !time.equals(java.time.Instant.EPOCH));
        } catch (IOException unreadable) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Reads the logs of a coordination folder.
     *
     * @param root      the project's coordination folder
     * @param projectId the project; an entry of another project is a problem
     * @return what the folder holds; empty when the folder does not exist
     * @throws IOException when a folder cannot be listed
     */
    public static Dump read(Path root, UUID projectId) throws IOException {
        if (!Files.isDirectory(root)) {
            return new Dump(List.of(), List.of(), List.of());
        }
        List<String> problems = new ArrayList<>();
        Map<String, Map<Long, Held>> logs = new LinkedHashMap<>();
        logs.put(PredictionEventStore.OWN_LOG, readLog(root.resolve("events"), PredictionEventStore.OWN_LOG, null,
                projectId, problems));
        Path peers = root.resolve("peers");
        if (Files.isDirectory(peers)) {
            List<Path> copies;
            try (var listing = Files.list(peers)) {
                copies = listing.filter(Files::isDirectory).sorted().toList();
            }
            for (Path copy : copies) {
                String node = copy.getFileName().toString();
                if (node.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
                    logs.put(node, readLog(copy.resolve("events"), node, node, projectId, problems));
                }
            }
        }
        List<Entry> entries = order(root, logs, problems);
        return new Dump(entries, List.copyOf(problems), LogDumpSummary.of(root, projectId, entries));
    }

    /** One file of a log: the entry it decodes to, or the header of one that does not. */
    private record Held(PredictionEvent event, String unknownType, String unknownWriter) {
    }

    private static Map<Long, Held> readLog(Path folder, String log, String signer, UUID projectId,
            List<String> problems) throws IOException {
        Map<Long, Held> held = new TreeMap<>();
        if (!Files.isDirectory(folder)) {
            return held;
        }
        List<Path> files;
        try (var listing = Files.list(folder)) {
            files = listing.filter(path -> path.getFileName().toString().endsWith(".sce")).sorted().toList();
        }
        PredictionEvent previous = null;
        for (Path file : files) {
            String name = file.getFileName().toString();
            long sequence;
            try {
                sequence = Long.parseLong(name.substring(0, name.length() - ".sce".length()));
            } catch (NumberFormatException notASequence) {
                problems.add(log + " " + name + " FILE_NAME_INVALID");
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException unreadable) {
                problems.add(log + " " + sequence + " FILE_UNREADABLE");
                continue;
            }
            try {
                PredictionEvent event = PredictionEvent.decode(bytes);
                held.put(sequence, new Held(event, "", ""));
                check(event, sequence, previous, signer, projectId).forEach(code -> problems.add(log + " " + sequence
                        + " " + code));
                previous = event;
            } catch (IOException | RuntimeException malformed) {
                Held unknown = unknownType(bytes);
                if (unknown == null) {
                    problems.add(log + " " + sequence + " ENTRY_MALFORMED");
                } else {
                    held.put(sequence, unknown);
                }
                previous = null;
            }
        }
        return held;
    }

    /** What is wrong with a decoded entry; empty when it holds. */
    private static List<String> check(PredictionEvent event, long sequence, PredictionEvent previous, String signer,
            UUID projectId) {
        List<String> codes = new ArrayList<>();
        if (event.sequence() != sequence) {
            codes.add("SEQUENCE_MISMATCH");
        }
        if (!event.projectId().equals(projectId)) {
            codes.add("OTHER_PROJECT");
        }
        if (signer != null && !signer.equals(event.actorNodeId())) {
            codes.add("WRONG_SIGNER");
        }
        if (previous != null && previous.sequence() + 1 == event.sequence()
                && !Arrays.equals(previous.digest(), event.previousDigest())) {
            codes.add("CHAIN_BROKEN");
        }
        try {
            if (!event.verify()) {
                codes.add("SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException | RuntimeException unverifiable) {
            codes.add("SIGNATURE_UNCHECKABLE");
        }
        return codes;
    }

    /**
     * Reads the header of an entry whose type this build does not know. The wire code sits after the magic, the
     * version, three UUIDs and the sequence; the writer follows as length-prefixed text.
     *
     * @return the unknown entry, or null when the bytes are not an entry with an unknown type
     */
    private static Held unknownType(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != 1 || in.skipBytes(48) != 48) {
                return null;
            }
            long sequence = in.readLong();
            int wireCode = in.readInt();
            int length = in.readInt();
            if (sequence <= 0 || length < 0 || length > MAX_ACTOR_BYTES || length > in.available()) {
                return null;
            }
            String writer = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            try {
                PredictionEventType.fromWireCode(wireCode);
                return null;
            } catch (RuntimeException unknown) {
                return new Held(null, String.valueOf(wireCode), LogDumpFields.clean(writer));
            }
        } catch (IOException truncated) {
            return null;
        }
    }

    /**
     * Lists the entries in the order this computer applied them. A line of {@code applied.order} names an entry;
     * an entry of the own log past the last line applies after every line (a log written before step 4, or an
     * append cut short); an entry of a copy with no line has not been applied here.
     */
    private static List<Entry> order(Path root, Map<String, Map<Long, Held>> logs, List<String> problems)
            throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> named = new HashSet<>();
        long revision = 0;
        long lastOwn = 0;
        for (AppliedLine line : appliedLines(root, problems)) {
            if (!named.add(line.log() + " " + line.sequence())) {
                problems.add(line.log() + " " + line.sequence() + " APPLIED_ORDER_DUPLICATE");
                continue;
            }
            if (line.log().equals(PredictionEventStore.OWN_LOG)) {
                lastOwn = Math.max(lastOwn, line.sequence());
            }
            boolean applied = line.code().isEmpty();
            entries.add(entry(applied ? ++revision : 0, line.log(), line.sequence(),
                    logs.getOrDefault(line.log(), Map.of()).get(line.sequence()),
                    applied ? "" : "COUNTED_FOR_NOTHING code=" + line.code()));
        }
        for (Map.Entry<Long, Held> own : logs.get(PredictionEventStore.OWN_LOG).entrySet()) {
            if (own.getKey() > lastOwn && named.add(PredictionEventStore.OWN_LOG + " " + own.getKey())) {
                entries.add(entry(++revision, PredictionEventStore.OWN_LOG, own.getKey(), own.getValue(), ""));
            }
        }
        for (Map.Entry<String, Map<Long, Held>> log : logs.entrySet()) {
            for (Map.Entry<Long, Held> held : log.getValue().entrySet()) {
                if (named.add(log.getKey() + " " + held.getKey())) {
                    entries.add(entry(0, log.getKey(), held.getKey(), held.getValue(), "NOT_APPLIED"));
                }
            }
        }
        return entries;
    }

    private record AppliedLine(String log, long sequence, String code) {
    }

    /** The complete lines of {@code applied.order}; a line a writer cut short is ignored, as every reader does. */
    private static List<AppliedLine> appliedLines(Path root, List<String> problems) throws IOException {
        Path file = root.resolve(AppliedOrder.FILE);
        List<AppliedLine> lines = new ArrayList<>();
        if (!Files.exists(file)) {
            return lines;
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int complete = text.lastIndexOf('\n');
        if (complete < 0) {
            return lines;
        }
        int number = 0;
        for (String raw : text.substring(0, complete).split("\n", -1)) {
            number++;
            AppliedOrder.Line line = AppliedOrder.tryParse(raw);
            if (line == null) {
                problems.add(AppliedOrder.FILE + " " + number + " LINE_MALFORMED");
            } else {
                lines.add(new AppliedLine(line.log(), line.sequence(), line.code()));
            }
        }
        return lines;
    }

    private static Entry entry(long position, String log, long sequence, Held held, String note) {
        if (held == null) {
            return new Entry(position, log, "?", sequence, UNREADABLE, "", note.isEmpty() ? "NOT_READ" : note + " NOT_READ",
                    Map.of());
        }
        if (held.event() == null) {
            return new Entry(position, log, held.unknownWriter(), sequence, UNKNOWN, held.unknownType(), note,
                    Map.of());
        }
        PredictionEvent event = held.event();
        return new Entry(position, log, LogDumpFields.clean(event.actorNodeId()), sequence, event.type().name(), "",
                note, LogDumpFields.of(event));
    }
}
