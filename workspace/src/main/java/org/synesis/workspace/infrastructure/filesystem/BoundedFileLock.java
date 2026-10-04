package org.synesis.workspace.infrastructure.filesystem;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns one exclusive operating-system file lock with bounded acquisition.
 */
public final class BoundedFileLock implements AutoCloseable {

    private static final Duration RETRY_INTERVAL = Duration.ofMillis(10);

    private final FileChannel channel;
    private final FileLock lock;

    private BoundedFileLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires an exclusive lock on a file, retrying until the timeout expires.
     *
     * @param path    lock-file path
     * @param timeout maximum wait for another process or thread to release the lock
     * @return held lock handle
     * @throws IOException when the lock cannot be opened or acquired
     */
    public static BoundedFileLock acquire(Path path, Duration timeout) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Duration wait = Objects.requireNonNull(timeout, "timeout");
        if (wait.isNegative() || wait.isZero()) {
            throw new IllegalArgumentException("lock timeout must be positive");
        }
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                try {
                    FileLock acquired = channel.tryLock();
                    if (acquired != null) {
                        return new BoundedFileLock(channel, acquired);
                    }
                } catch (OverlappingFileLockException heldInThisProcess) {
                    // A second thread in this JVM must wait for the same OS-level lock.
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("file lock acquisition timed out");
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(remaining, RETRY_INTERVAL.toNanos()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("file lock acquisition interrupted", interrupted);
                }
            }
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Reports whether this handle still owns its lock.
     *
     * @return whether the operating-system lock is valid
     */
    public boolean isHeld() {
        return lock.isValid();
    }

    /**
     * Releases the operating-system lock and its channel.
     *
     * @throws IOException when the lock or channel cannot be closed
     */
    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
