package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Holds an OS file lock while preserving the permanent lock object on disk. */
public final class CommandPermanentLock implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;

    private CommandPermanentLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    /** Creates or opens one permanent regular lock file and acquires it.
     * @param path permanent lock path
     * @return held lock handle
     * @throws IOException if the object is invalid or cannot be locked
     */
    public static CommandPermanentLock open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(Objects.requireNonNull(normalized.getParent(), "lock parent"));
        if (Files.exists(normalized)
                && (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized))) {
            throw new IOException("COMMAND_LOCK_OBJECT_INVALID");
        }
        try {
            Files.createFile(normalized, new java.nio.file.attribute.FileAttribute<?>[0]);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            // The object may have been published by another process between
            // the identity check and CREATE_FILE; the common bounded retry
            // path still validates the permanent object before locking it.
        }
        return acquire(normalized);
    }

    private static CommandPermanentLock acquire(Path normalized) throws IOException {
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IOException("COMMAND_LOCK_OBJECT_INVALID");
        }
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + 2_000_000_000L;
        try {
            while (true) {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        return new CommandPermanentLock(normalized, channel, lock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // Another local thread/process owns the object.
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("COMMAND_LOCK_TIMEOUT");
                }
                Thread.sleep(10L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            channel.close();
            throw new IOException("COMMAND_LOCK_INTERRUPTED", interrupted);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    /** Returns the permanent lock object's normalized path.
     * @return permanent lock path
     */
    public Path path() {
        return path;
    }

    /** Returns whether this process still holds the lock.
     * @return true while the OS lock is valid
     */
    public boolean isHeld() {
        return lock.isValid();
    }

    /** Releases the OS lock but intentionally leaves the lock file in place. */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

}
