package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous bounded JSONL evidence persistence for one connection generation.
 *
 * <p>Protocol readers call {@link #offer(String, Map, boolean)} only after
 * authoritative state has already been applied and waiters completed. The
 * bounded in-memory queue never blocks the reader. Terminal and correlation
 * evidence displaces low-value deltas when possible; authoritative lifecycle
 * state remains in {@link CodexLifecycleStateStore} even when the journal is
 * incomplete. One journal is capped at 8 MiB and closes with a bounded manifest
 * describing omissions.
 *
 * @since 1.0
 */
public final class CodexEvidenceJournal implements AutoCloseable {

    /**
     * Maximum queued evidence events.
     */
    public static final int MAX_QUEUE_ENTRIES = 512;
    /**
     * Maximum persisted bytes for one connection-generation journal.
     */
    public static final int MAX_JOURNAL_BYTES = 8 * 1024 * 1024;
    /**
     * Maximum retained bytes for one serialized evidence entry.
     */
    public static final int MAX_ENTRY_BYTES = 64 * 1024;
    /**
     * Maximum closing summary bytes.
     */
    public static final int MAX_CLOSING_SUMMARY_BYTES = 32 * 1024;
    /**
     * Reserved in-memory slots for terminal and correlation evidence.
     */
    public static final int MAX_CRITICAL_QUEUE_ENTRIES = 64;

    private final Path journal;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private final Deque<Entry> criticalQueue = new ArrayDeque<>();
    private final Object queueLock = new Object();
    private final Object accountingLock = new Object();
    private final ExecutorService writer;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Map<String, Long> dropped = new LinkedHashMap<>();
    private volatile boolean closedRequested;
    private volatile boolean complete = true;
    private long eventsDropped;
    private volatile long persistedBytes;
    private volatile boolean overflowMarker;

    /**
     * Opens an asynchronous generation journal.
     *
     * @param journal JSONL path
     */
    public CodexEvidenceJournal(Path journal) {
        this.journal = Objects.requireNonNull(journal, "journal")
                .toAbsolutePath()
                .normalize();
        try {
            if (Files.isRegularFile(this.journal)) {
                long existing = Files.size(this.journal);
                persistedBytes = Math.min(existing, MAX_JOURNAL_BYTES);
                if (existing >= MAX_JOURNAL_BYTES) {
                    complete = false;
                    overflowMarker = true;
                }
            }
        } catch (IOException failure) {
            complete = false;
            overflowMarker = true;
        }
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "synesis-codex-evidence-writer");
            thread.setDaemon(true);
            return thread;
        });
        writer.submit(this::drain);
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required", impossible);
        }
    }

    private static Object boundedValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= MAX_ENTRY_BYTES) {
                return text;
            }
            return Map.of("truncated", true, "contentDigest", digest(text), "originalBytes", bytes.length);
        }
        if (depth >= 4) {
            String text = String.valueOf(value);
            return text.length() > 512 ? text.substring(0, 512) : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> bounded = new LinkedHashMap<>();
            map.entrySet()
                    .stream()
                    .limit(32)
                    .forEach(entry -> bounded.put(String.valueOf(entry.getKey()),
                            boundedValue(entry.getValue(), depth + 1)));
            return bounded;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .limit(32)
                    .map(item -> boundedValue(item, depth + 1))
                    .toList();
        }
        String text = String.valueOf(value);
        return text.length() > 512 ? text.substring(0, 512) : text;
    }

    /**
     * Attempts to enqueue one event without blocking the protocol reader.
     *
     * @param category stable evidence category
     * @param fields   bounded semantic event fields
     * @param terminal whether the event is authoritative terminal/correlation evidence
     * @return {@code true} when queued or directly reserved
     */
    public boolean offer(String category, Map<String, ?> fields, boolean terminal) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(fields, "fields");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("category", category);
        fields.forEach((key, item) -> value.put(String.valueOf(key), boundedValue(item, 0)));
        String encoded;
        try {
            encoded = org.synesis.workspace.infrastructure.json.ProviderJson.write(value);
        } catch (RuntimeException failure) {
            return omit(category);
        }
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        if (persistedBytes >= MAX_JOURNAL_BYTES) {
            countDrop(category);
            return false;
        }
        if (bytes.length > MAX_ENTRY_BYTES) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("category", category);
            summary.put("truncated", true);
            summary.put("contentDigest", digest(encoded));
            summary.put("originalBytes", bytes.length);
            encoded = org.synesis.workspace.infrastructure.json.ProviderJson.write(summary);
        }
        Entry entry = new Entry(category, encoded + System.lineSeparator(), terminal);
        synchronized (queueLock) {
            if (closedRequested) {
                return omit(category);
            }
            if (terminal && criticalQueue.size() < MAX_CRITICAL_QUEUE_ENTRIES) {
                if (queue.size() + criticalQueue.size() >= MAX_QUEUE_ENTRIES) {
                    Entry low = queue.stream()
                            .filter(item -> !item.terminal())
                            .findFirst()
                            .orElse(null);
                    if (low == null) {
                        return omit(category);
                    }
                    queue.remove(low);
                    countDrop(low.category());
                }
                criticalQueue.addLast(entry);
                queueLock.notifyAll();
                return true;
            }
            if (queue.size() + criticalQueue.size() >= MAX_QUEUE_ENTRIES) {
                Entry low = queue.stream()
                        .filter(item -> !item.terminal())
                        .findFirst()
                        .orElse(null);
                if (low != null) {
                    queue.remove(low);
                    countDrop(low.category());
                } else {
                    return omit(category);
                }
            }
            queue.addLast(entry);
            queueLock.notifyAll();
            return true;
        }
    }

    /**
     * Returns whether no journal omission has occurred.
     *
     * @return completeness state
     */
    public boolean evidenceComplete() {
        return complete;
    }

    /**
     * Returns the total omitted event count.
     *
     * @return omitted count
     */
    public long eventsDropped() {
        synchronized (accountingLock) {
            return eventsDropped;
        }
    }

    /**
     * Returns omitted event counts grouped by category.
     *
     * @return immutable counts
     */
    @SuppressWarnings("unused")
    public Map<String, Long> droppedCategories() {
        synchronized (accountingLock) {
            return Map.copyOf(dropped);
        }
    }

    /**
     * Returns bytes durably written to this generation journal.
     *
     * @return persisted journal bytes
     */
    public long persistedBytes() {
        return persistedBytes;
    }

    /**
     * Returns whether an overflow marker has been recorded.
     *
     * @return overflow marker state
     */
    public boolean overflowMarker() {
        return overflowMarker;
    }

    /**
     * Marks this generation incomplete when closing metadata cannot be
     * persisted or an external owner observes a journal failure.
     */
    public void markIncomplete() {
        synchronized (accountingLock) {
            complete = false;
            overflowMarker = true;
        }
    }

    /**
     * Returns the generation journal path.
     *
     * @return journal path
     */
    public Path journal() {
        return journal;
    }

    /**
     * Closes the writer and attempts a bounded omission manifest.
     *
     * @throws IOException when the closing manifest cannot be written
     */
    @Override
    public void close() throws IOException {
        closedRequested = true;
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
            if (!closed.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                markIncomplete();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
        }
        writeManifest();
    }

    private void drain() {
        try {
            while (true) {
                Entry entry;
                synchronized (queueLock) {
                    while (queue.isEmpty() && criticalQueue.isEmpty() && !closedRequested) {
                        queueLock.wait(100L);
                    }
                    if (queue.isEmpty() && criticalQueue.isEmpty() && closedRequested) {
                        return;
                    }
                    entry = criticalQueue.isEmpty() ? queue.pollFirst() : criticalQueue.pollFirst();
                }
                persist(Objects.requireNonNull(entry, "journal entry"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                    .interrupt();
        } finally {
            closed.countDown();
        }
    }

    private void persist(Entry entry) {
        byte[] bytes = entry.encoded()
                .getBytes(StandardCharsets.UTF_8);
        synchronized (accountingLock) {
            if (persistedBytes + bytes.length > MAX_JOURNAL_BYTES) {
                complete = false;
                overflowMarker = true;
                countDrop(entry.category());
                return;
            }
            // Reserve bytes before disk I/O. The writer never holds the
            // accounting lock while the filesystem may block, so protocol
            // readers continue draining stdout independently of persistence.
            persistedBytes += bytes.length;
        }
        try {
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, entry.encoded(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            markIncomplete();
            countDrop(entry.category());
        }
    }

    private boolean omit(String category) {
        synchronized (accountingLock) {
            complete = false;
            overflowMarker = true;
            eventsDropped++;
            dropped.merge(category, 1L, Long::sum);
            return false;
        }
    }

    private void countDrop(String category) {
        synchronized (accountingLock) {
            complete = false;
            overflowMarker = true;
            eventsDropped++;
            dropped.merge(category, 1L, Long::sum);
        }
    }

    private void writeManifest() throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        synchronized (accountingLock) {
            manifest.put("evidenceComplete", complete);
            manifest.put("evidenceOverflow", overflowMarker);
            manifest.put("overflowMarker", overflowMarker ? "evidence_overflow" : null);
            manifest.put("eventsDropped", eventsDropped);
            manifest.put("droppedCategories", new LinkedHashMap<>(dropped));
            manifest.put("persistedBytes", persistedBytes);
        }
        manifest.put("journal",
                journal.getFileName()
                        .toString());
        String encoded = org.synesis.workspace.infrastructure.json.ProviderJson.write(manifest);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_CLOSING_SUMMARY_BYTES) {
            encoded = "{\"evidenceComplete\":" + complete + ",\"eventsDropped\":" + eventsDropped
                    + ",\"manifestTruncated\":true}";
        }
        Path manifestPath = journal.resolveSibling(journal.getFileName() + ".manifest.json");
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, encoded + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private record Entry(String category, String encoded, boolean terminal) {

    }
}
