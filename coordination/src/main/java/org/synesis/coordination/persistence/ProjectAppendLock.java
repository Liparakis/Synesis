package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Bounded project-local lock for serializing event-log appends across processes.
 *
 * <p>The handle protects the complete read, validate, and append sequence, not
 * merely the final file write. Callers must retain it while deriving state from
 * the event log and while appending the event that depends on that state.</p>
 */
public final class ProjectAppendLock implements AutoCloseable {

    /** File channel whose operating-system lock coordinates local processes. */
    private final FileChannel channel;
    /** The exclusive lock held for the lifetime of this handle. */
    private final FileLock lock;

    private ProjectAppendLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the project append lock with bounded retry.
     *
     * @param root project coordination root containing {@code append.lock}
     * @return lock handle
     * @throws IOException when the lock cannot be acquired within two seconds
     *         or the acquisition thread is interrupted
     */
    public static ProjectAppendLock acquire(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Files.createDirectories(root);
        FileChannel channel = FileChannel.open(root.resolve("append.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + 2_000_000_000L;
        try {
            while (true) {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        return new ProjectAppendLock(channel, lock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // Another local thread owns the project lock.
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("event append lock timeout");
                }
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10L));
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IOException("event append lock interrupted");
                }
            }
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    /**
     * Returns whether this handle currently owns the project lock.
     *
     * @return true while held
     */
    public boolean isHeld() {
        return lock.isValid();
    }

    /**
     * Releases the operating-system lock and its channel.
     *
     * <p>Closing the handle ends the caller's read/validate/write critical
     * section; no durable operation should depend on the lock after this call.</p>
     */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
