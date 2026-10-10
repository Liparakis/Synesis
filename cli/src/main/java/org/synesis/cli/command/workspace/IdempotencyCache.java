package org.synesis.cli.command.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.filesystem.BoundedFileLock;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Persisted response cache of one idempotency key, stored as {@code idempotency-<sha256 of key>.json}
 * in a provider evidence directory.
 *
 * <p>The file is read back from disk that other processes can write, so it is read bounded and
 * without following links, and an unreadable or unsupported file is an error rather than a miss.</p>
 */
final class IdempotencyCache {

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_CACHE_BYTES = 128 * 1024;
    private static final int FORMAT_VERSION = 1;

    private final Path file;

    private IdempotencyCache(Path file) {
        this.file = file;
    }

    /**
     * Addresses the cache entry of a key.
     *
     * @param evidenceDirectory existing provider evidence directory
     * @param key               caller-supplied idempotency key
     * @return the entry, which may not exist yet
     */
    static IdempotencyCache of(Path evidenceDirectory, String key) {
        return new IdempotencyCache(evidenceDirectory.resolve("idempotency-" + Sha256.hex(key) + ".json"));
    }

    /**
     * Takes this entry's lock, so that concurrent requests with the same key are applied one at a
     * time. The caller closes it.
     *
     * @return the held lock
     * @throws IOException when the lock cannot be taken or is not held
     */
    BoundedFileLock lock() throws IOException {
        BoundedFileLock lock = BoundedFileLock.acquire(file.resolveSibling(file.getFileName() + ".lock"), LOCK_TIMEOUT);
        if (!lock.isHeld()) {
            IOException invalid = new IOException("IDEMPOTENCY_LOCK_INVALID");
            try {
                lock.close();
            } catch (IOException closeFailure) {
                invalid.addSuppressed(closeFailure);
            }
            throw invalid;
        }
        return lock;
    }

    /**
     * Reads the stored response, if any.
     *
     * @return the stored response, or empty when the key was not used before
     * @throws IOException when an entry exists but is invalid, oversized, malformed, or of an
     *                     unsupported version
     */
    Optional<Cached> read() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("idempotency cache is invalid");
        }
        String serialized;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > MAX_CACHE_BYTES) {
                throw new IOException("idempotency cache is oversized");
            }
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(size));
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // Read only the bounded size observed while the file was open.
            }
            serialized = new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
        Object parsed;
        try {
            parsed = ProviderJson.parse(serialized);
        } catch (RuntimeException malformed) {
            throw new IOException("idempotency cache is malformed", malformed);
        }
        if (!(parsed instanceof Map<?, ?> cache) || !(cache.get("requestFingerprint") instanceof String fingerprint) || !(cache.get("response") instanceof String response)) {
            throw new IOException("idempotency cache is malformed");
        }
        Object version = cache.get("formatVersion");
        if (!(version instanceof Number number) || number.intValue() != FORMAT_VERSION) {
            throw new IOException("idempotency cache version is unsupported");
        }
        return Optional.of(new Cached(fingerprint, response));
    }

    /**
     * Stores a response with an atomic replace, so a reader never sees a partial entry.
     *
     * @param fingerprint fingerprint of the request that produced the response
     * @param response    response text to replay
     * @throws IOException when the entry cannot be written
     */
    void write(String fingerprint, String response) throws IOException {
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("formatVersion", FORMAT_VERSION);
        cache.put("requestFingerprint", fingerprint);
        cache.put("response", response);
        Path temporary = Files.createTempFile(file.getParent(), "idempotency-", ".tmp");
        try {
            Files.writeString(temporary, ProviderJson.write(cache) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * A stored response and the fingerprint of the request that produced it.
     *
     * @param requestFingerprint fingerprint of the original request
     * @param response           response text to replay
     */
    record Cached(String requestFingerprint, String response) {

    }
}
