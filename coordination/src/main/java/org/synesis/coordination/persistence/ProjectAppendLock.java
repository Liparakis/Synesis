package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Bounded project-local lock for serializing event-log appends across processes. */
public final class ProjectAppendLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private ProjectAppendLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Acquires the project append lock with bounded retry. */
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
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("event append lock interrupted", interrupted);
                }
            }
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    /** Returns whether this handle currently owns the project lock. */
    public boolean isHeld() {
        return lock.isValid();
    }

    /** Releases the lock and channel. */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
