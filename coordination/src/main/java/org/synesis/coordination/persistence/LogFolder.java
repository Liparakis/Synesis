package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;

/**
 * One computer's signed log in one folder: immutable {@code %020d.sce} files, each chained to its predecessor by
 * sequence and digest and signed by its writer (ADR-0098 step 4). This computer's own log is {@code events/}; the
 * copy of another computer's log is {@code peers/<node>/events/}, byte-identical to the writer's and signed only by
 * that node. A file is never removed or replaced once it has its name.
 */
final class LogFolder {

    /**
     * Age beyond which a temporary event file can only have been abandoned by a writer that died.
     */
    private static final Duration ABANDONED_TEMPORARY_AGE = Duration.ofMinutes(1);

    private final Path directory;
    private final UUID projectId;
    private final String signer;

    /**
     * Binds a folder.
     *
     * @param directory the folder of the log's files
     * @param projectId the project every entry must belong to
     * @param signer    the node that must have signed every entry; null for this computer's own log, which may
     *                  hold entries of a log written before step 4
     */
    LogFolder(Path directory, UUID projectId, String signer) {
        this.directory = directory;
        this.projectId = projectId;
        this.signer = signer;
    }

    Path directory() {
        return directory;
    }

    /**
     * Returns every entry of the log, verified: each belongs to this project, follows its predecessor's sequence
     * and digest, is correctly signed, and, in a copy, is signed by the node whose log it is. A prefix this process
     * verified before is not read again while the folder still holds those files.
     *
     * @return the entries in sequence order
     * @throws IOException              when an entry is undecodable or out of chain
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    List<PredictionEvent> verified() throws IOException, GeneralSecurityException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".sce"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        Path real = directory.toRealPath();
        List<PredictionEvent> verified = new ArrayList<>(VerifiedEventPrefixes.recall(real, projectId));
        if (!stillHolds(verified, files)) {
            VerifiedEventPrefixes.forget(real, projectId);
            verified.clear();
        }
        long sequence = verified.isEmpty() ? 0L : verified.getLast().sequence();
        byte[] digest = verified.isEmpty() ? new byte[32] : verified.getLast().digest();
        int known = verified.size();
        try {
            for (Path file : files.subList(known, files.size())) {
                PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
                if (!follows(event, sequence, digest)) {
                    throw new CoordinationException("invalid coordination event log");
                }
                verified.add(event);
                sequence = event.sequence();
                digest = event.digest();
            }
        } finally {
            if (verified.size() > known) {
                VerifiedEventPrefixes.remember(real, projectId, verified);
            }
        }
        return verified;
    }

    /**
     * Tells whether an entry may follow a head in this log: this project's, the next sequence, chained to the
     * head's digest, correctly signed, and by this log's node when it is a copy.
     *
     * @throws GeneralSecurityException when the signature cannot be checked
     */
    boolean follows(PredictionEvent event, long headSequence, byte[] headDigest) throws GeneralSecurityException {
        return event.projectId().equals(projectId) && event.sequence() == headSequence + 1L
                && Arrays.equals(event.previousDigest(), headDigest) && (signer == null
                || signer.equals(event.actorNodeId())) && event.verify();
    }

    /**
     * Places one encoded entry under its sequence through a forced temporary file that is then given its durable
     * name. The name is only ever given to a sequence nobody holds: a hard link fails when the name exists, where a
     * move would replace another writer's entry.
     *
     * @param event a verified entry
     * @throws FileAlreadyExistsException when the sequence is held already
     * @throws IOException                when the write or the placement fails
     */
    void place(PredictionEvent event) throws IOException {
        Files.createDirectories(directory);
        Path target = file(event.sequence());
        Path temporary = directory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer encoded = ByteBuffer.wrap(event.encoded());
                while (encoded.hasRemaining()) {
                    if (channel.write(encoded) <= 0) {
                        throw new CoordinationException("event append write made no progress");
                    }
                }
                channel.force(true);
            }
            PredictionEventStore.faultAt(PredictionEventStore.AppendBoundary.BEFORE_DURABLE_MOVE);
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException taken) {
                throw taken;
            } catch (UnsupportedOperationException | IOException noHardLinks) {
                // A file system without hard links. A move without options fails as well when
                // the name exists.
                Files.move(temporary, target);
            }
            PredictionEventStore.faultAt(PredictionEventStore.AppendBoundary.AFTER_DURABLE_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Places a copied entry, or accepts it when the same bytes are already there.
     *
     * @param event a verified entry of this copy
     * @throws CoordinationException {@code LOG_COPY_DIVERGED} when other bytes hold the sequence
     * @throws IOException           when the write fails
     */
    void placeCopy(PredictionEvent event) throws IOException {
        try {
            place(event);
        } catch (FileAlreadyExistsException taken) {
            if (!Arrays.equals(Files.readAllBytes(file(event.sequence())), event.encoded())) {
                throw new CoordinationException("LOG_COPY_DIVERGED");
            }
        }
    }

    /**
     * Removes temporary event files that a writer left behind before its durable move.
     *
     * <p>Every open runs this, readers included, and holds no lock, so a temporary file may belong to another
     * process that is appending right now. Such a file exists for milliseconds. Only one older than
     * {@link #ABANDONED_TEMPORARY_AGE} is removed, and one that cannot be inspected or deleted is left alone: it is
     * being written or has just been moved, and reading the log never looks at it.</p>
     *
     * @throws IOException when the folder cannot be listed
     */
    void cleanupTemporaryFiles() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        FileTime abandonedBefore = FileTime.from(Instant.now().minus(ABANDONED_TEMPORARY_AGE));
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().contains(".sce.tmp-")).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).compareTo(abandonedBefore) < 0) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException inFlight) {
                    // Not ours to remove.
                }
            }
        }
    }

    /**
     * Counts the log's files without reading them.
     *
     * @return how many entries the folder holds; zero when it cannot be read
     */
    long count() {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sce")).count();
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    Path file(long sequence) {
        return directory.resolve(String.format("%020d.sce", sequence));
    }

    /**
     * Checks that the folder still holds the files of a remembered verified prefix: the first files are exactly
     * sequences one to the prefix length, and the last of them still has the bytes that were verified. Anything
     * else (a file missing, renamed, added in the middle, or replaced at the head) makes the prefix untrusted and
     * the caller verifies the whole log again.
     */
    private static boolean stillHolds(List<PredictionEvent> prefix, List<Path> files) throws IOException {
        if (prefix.isEmpty()) {
            return true;
        }
        if (files.size() < prefix.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!files.get(index).getFileName().toString().equals(String.format("%020d.sce", index + 1L))) {
                return false;
            }
        }
        PredictionEvent head = prefix.get(prefix.size() - 1);
        try {
            return Arrays.equals(Files.readAllBytes(files.get(prefix.size() - 1)), head.encoded());
        } catch (NoSuchFileException vanished) {
            return false;
        }
    }
}
