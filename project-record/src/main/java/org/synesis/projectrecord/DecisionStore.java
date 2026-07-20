package org.synesis.projectrecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe per-profile immutable decision revision store.
 *
 * <p>Revision files are written and forced before an atomically replaced head
 * file. Startup validates chains and rebuilds a missing or older head from
 * durable revisions. Corrupt bytes fail recovery rather than being guessed.
 */
public final class DecisionStore {
    /** Result of attempting to append one revision. */
    public enum SaveResult {
        /** The revision and head were durably advanced. */
        APPLIED,
        /** The exact revision already exists and the head is unchanged. */
        DUPLICATE,
        /** The caller's expected base no longer equals the local head. */
        STALE_BASE,
        /** The candidate is validly framed but cannot extend the local chain. */
        CONFLICT,
        /** The candidate fails signature, project, or structural trust checks. */
        CORRUPT
    }

    /** Immutable local head pointer. */
    public static final class Head {
        private final long revision;
        private final byte[] digest;

        private Head(long revision, byte[] digest) {
            if (revision <= 0 || digest.length != 32) throw new IllegalArgumentException("invalid head");
            this.revision = revision;
            this.digest = digest.clone();
        }

        /** Returns the positive head revision.
         * @return revision
         */
        public long revision() { return revision; }
        /** Returns a copy of the head digest.
         * @return digest bytes
         */
        public byte[] digest() { return digest.clone(); }
        /** Returns the lowercase hexadecimal head digest.
         * @return digest text
         */
        public String digestHex() { return java.util.HexFormat.of().formatHex(digest); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Head value && revision == value.revision && Arrays.equals(digest, value.digest);
        }

        @Override
        public int hashCode() { return Objects.hash(revision, Arrays.hashCode(digest)); }
    }

    private static final int HEAD_MAGIC = 0x53444831;
    private static final int HEAD_VERSION = 1;
    private final Path root;
    private final UUID projectId;
    private final Path decisions;
    private final Path heads;

    /**
     * Opens or creates one profile-local store and performs crash recovery.
     *
     * @param root profile directory; no private key is stored here
     * @param projectId project namespace enforced for every revision
     * @throws IOException if durable bytes are corrupt or filesystem access fails
     */
    public DecisionStore(Path root, UUID projectId) throws IOException {
        this.root = Objects.requireNonNull(root, "root");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.decisions = root.resolve("decisions");
        this.heads = root.resolve("heads");
        Files.createDirectories(decisions);
        Files.createDirectories(heads);
        recover();
    }

    /**
     * Appends one signed revision after validating the expected local base.
     *
     * @param record candidate immutable revision
     * @param expectedBase caller's observed head, or null for a new record
     * @return deterministic append result
     * @throws IOException if the store cannot be read or atomically written
     */
    public synchronized SaveResult save(DecisionRecord record, Head expectedBase) throws IOException {
        Objects.requireNonNull(record, "record");
        if (!projectId.equals(record.projectId())) return SaveResult.CORRUPT;
        try {
            if (!record.verify()) return SaveResult.CORRUPT;
        } catch (GeneralSecurityException exception) {
            return SaveResult.CORRUPT;
        }
        Path revisionPath = revisionPath(record.recordId(), record.revision());
        if (Files.exists(revisionPath)) {
            byte[] existing = Files.readAllBytes(revisionPath);
            if (Arrays.equals(existing, record.encoded())) return SaveResult.DUPLICATE;
            return SaveResult.CORRUPT;
        }
        Head current = readHead(record.recordId());
        if (current == null) {
            if (expectedBase != null) return SaveResult.STALE_BASE;
            if (record.revision() != 1 || record.previousDigest() != null) return SaveResult.CONFLICT;
        } else {
            if (Arrays.equals(current.digest(), record.digest())) return SaveResult.DUPLICATE;
            if (!current.equals(expectedBase)) return SaveResult.STALE_BASE;
            if (record.revision() != current.revision() + 1
                    || !Arrays.equals(record.previousDigest(), current.digest())) return SaveResult.CONFLICT;
        }
        atomicWrite(revisionPath, record.encoded());
        atomicWrite(headPath(record.recordId()), encodeHead(new Head(record.revision(), record.digest())));
        return SaveResult.APPLIED;
    }

    /**
     * Returns the current local head revision.
     *
     * @param recordId stable decision identity
     * @return current head or empty when no revision exists
     * @throws IOException if the head or revision is corrupt
     */
    public synchronized Optional<DecisionRecord> head(UUID recordId) throws IOException {
        Objects.requireNonNull(recordId, "record ID");
        Head head = readHead(recordId);
        return head == null ? Optional.empty() : Optional.of(readRevision(recordId, head.revision()));
    }

    /**
     * Returns the current local head pointer.
     *
     * @param recordId stable decision identity
     * @return head pointer or empty when no revision exists
     * @throws IOException if the head is corrupt
     */
    public synchronized Optional<Head> headState(UUID recordId) throws IOException {
        return Optional.ofNullable(readHead(Objects.requireNonNull(recordId, "record ID")));
    }

    private void recover() throws IOException {
        try (var recordDirs = Files.list(decisions)) {
            for (Path recordDir : recordDirs.filter(Files::isDirectory).toList()) {
                UUID recordId;
                try {
                    recordId = UUID.fromString(recordDir.getFileName().toString());
                } catch (IllegalArgumentException exception) {
                    throw new IOException("invalid decision directory", exception);
                }
                Map<Long, DecisionRecord> revisions = new HashMap<>();
                try (var files = Files.list(recordDir)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sdr")).toList()) {
                        DecisionRecord record = readRevisionFile(file);
                        long fileRevision = parseRevisionName(file);
                        if (fileRevision != record.revision()) throw new IOException("revision filename mismatch");
                        if (!recordId.equals(record.recordId()) || !projectId.equals(record.projectId())) {
                            throw new IOException("revision identity mismatch");
                        }
                        if (!record.verify()) throw new IOException("invalid revision signature");
                        if (revisions.put(record.revision(), record) != null) {
                            throw new IOException("duplicate revision number");
                        }
                    }
                } catch (GeneralSecurityException exception) {
                    throw new IOException("invalid revision signature", exception);
                }
                if (revisions.isEmpty()) continue;
                DecisionRecord previous = null;
                for (DecisionRecord record : revisions.values().stream().sorted(Comparator.comparingLong(DecisionRecord::revision)).toList()) {
                    if (previous == null) {
                        if (record.revision() != 1 || record.previousDigest() != null) throw new IOException("broken revision chain");
                    } else if (record.revision() != previous.revision() + 1
                            || !Arrays.equals(record.previousDigest(), previous.digest())) {
                        throw new IOException("broken revision chain");
                    }
                    previous = record;
                }
                Head recovered = new Head(previous.revision(), previous.digest());
                Head existing = readHead(recordId);
                if (!recovered.equals(existing)) atomicWrite(headPath(recordId), encodeHead(recovered));
            }
        }
    }

    private DecisionRecord readRevision(UUID recordId, long revision) throws IOException {
        Path path = revisionPath(recordId, revision);
        DecisionRecord record = readRevisionFile(path);
        if (record.revision() != revision || !projectId.equals(record.projectId())
                || !recordId.equals(record.recordId())) throw new IOException("revision identity mismatch");
        try {
            if (!record.verify()) throw new IOException("invalid revision signature");
        } catch (GeneralSecurityException exception) {
            throw new IOException("invalid revision signature", exception);
        }
        return record;
    }

    private static DecisionRecord readRevisionFile(Path path) throws IOException {
        try {
            return DecisionRecord.decode(Files.readAllBytes(path));
        } catch (IOException exception) {
            throw new IOException("corrupt revision: " + path.getFileName(), exception);
        }
    }

    private static long parseRevisionName(Path path) throws IOException {
        String name = path.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, name.length() - 4));
        } catch (RuntimeException exception) {
            throw new IOException("invalid revision filename", exception);
        }
    }

    private Head readHead(UUID recordId) throws IOException {
        Path path = headPath(recordId);
        if (!Files.exists(path)) return null;
        byte[] bytes = Files.readAllBytes(path);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != HEAD_MAGIC || input.readUnsignedByte() != HEAD_VERSION
                    || input.readLong() <= 0) throw new IOException("corrupt head");
            long revision = bytesToRevision(bytes);
            byte[] digest = input.readNBytes(32);
            if (digest.length != 32 || input.available() != 0) throw new IOException("corrupt head");
            return new Head(revision, digest);
        } catch (RuntimeException exception) {
            throw new IOException("corrupt head", exception);
        }
    }

    private static long bytesToRevision(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            input.readInt();
            input.readUnsignedByte();
            return input.readLong();
        }
    }

    private static byte[] encodeHead(Head head) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(HEAD_MAGIC);
            output.writeByte(HEAD_VERSION);
            output.writeLong(head.revision());
            output.write(head.digest());
        }
        return bytes.toByteArray();
    }

    private Path revisionPath(UUID recordId, long revision) {
        return decisions.resolve(recordId.toString()).resolve(revision + ".sdr");
    }

    private Path headPath(UUID recordId) { return heads.resolve(recordId + ".head"); }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
