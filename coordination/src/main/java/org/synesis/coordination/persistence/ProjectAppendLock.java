package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Bounded project-local lock for serializing event-log appends across processes.
 *
 * <p>The handle protects the complete read, validate, and append sequence, not
 * merely the final file write. Callers must retain it while deriving state from the event log and while appending the
 * event that depends on
 * that state.</p>
 */
public final class ProjectAppendLock implements AutoCloseable {

    /**
     * One guard per lock file for the threads of this process, taken before the file is opened.
     *
     * <p>On Linux and macOS, closing any channel on a file releases every lock the process holds
     * on it. A thread that lost to another thread of its own process used to open a channel, fail
     * to lock it, and close it: the holder's operating-system lock was gone while its handle still
     * reported it held, and another process could then take the lock beside it. A thread that
     * waits here never opens a channel. The guard is not reentrant, as the file lock was not.</p>
     */
    private static final ConcurrentHashMap<Path, Semaphore> IN_PROCESS = new ConcurrentHashMap<>();

    /**
     * The in-process guard of the lock file, held for the lifetime of this handle.
     */
    private final Semaphore guard;
    /**
     * Whether this handle still owes the guard its release; closing twice must not give it back twice.
     */
    private final AtomicBoolean guardHeld = new AtomicBoolean(true);
    /**
     * File channel whose operating-system lock coordinates local processes.
     */
    private final FileChannel channel;
    /**
     * The exclusive lock held for the lifetime of this handle.
     */
    private final FileLock lock;

    /**
     * Validates and initializes this project append lock.
     *
     * @param lock already-held project append lock
     */
    private ProjectAppendLock(Semaphore guard, FileChannel channel, FileLock lock) {
        this.guard = guard;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the project append lock with bounded retry.
     *
     * @param root project coordination root containing {@code append.lock}
     * @return lock handle
     * @throws IOException when the lock cannot be acquired within two seconds or the acquisition thread is interrupted
     */
    public static ProjectAppendLock acquire(Path root) throws IOException {
        return acquireFile(root, "append.lock", 2_000_000_000L, false);
    }

    /**
     * Takes another named lock file of a project, waiting up to ten seconds. The event store holds
     * {@code order.lock} this way around the short work of one append or one batch of arriving entries
     * (ADR-0098 step 4); it is always taken after {@code append.lock}, never before.
     *
     * @param root project coordination root
     * @param name lock file name beside {@code append.lock}
     * @return lock handle
     * @throws IOException when the lock cannot be acquired in time or the thread is interrupted
     */
    static ProjectAppendLock acquireNamed(Path root, String name) throws IOException {
        return acquireFile(root, name, 10_000_000_000L, false);
    }

    /**
     * Takes the named lock file of a project once, without waiting.
     *
     * @param root project coordination root
     * @param name lock file name beside {@code append.lock}
     * @return lock handle, or {@code null} when another thread or process holds the file
     * @throws IOException when the file cannot be opened
     */
    static ProjectAppendLock tryAcquireFile(Path root, String name) throws IOException {
        return acquireFile(root, name, 0L, true);
    }

    private static ProjectAppendLock acquireFile(Path root, String name, long waitNanos, boolean nullOnBusy) throws IOException {
        CoordinationNullException.requireNonNull(root, "root");
        Files.createDirectories(root);
        Path file = root.toRealPath().resolve(name);
        Semaphore guard = IN_PROCESS.computeIfAbsent(file, ignored -> new Semaphore(1));
        long deadline = System.nanoTime() + waitNanos;
        while (!guard.tryAcquire()) {
            if (!waitForRetry(deadline, nullOnBusy)) {
                return null;
            }
        }
        try {
            FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
                while (true) {
                    try {
                        FileLock lock = channel.tryLock();
                        if (lock != null) {
                            return new ProjectAppendLock(guard, channel, lock);
                        }
                    } catch (OverlappingFileLockException ignored) {
                        // A lock this process took on the file by another road than this class.
                    }
                    if (!waitForRetry(deadline, nullOnBusy)) {
                        channel.close();
                        guard.release();
                        return null;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            guard.release();
            throw failure;
        }
    }

    /**
     * Waits before the lock is tried again. At the deadline it returns {@code false} when the caller asked not to
     * wait, else it fails; an interrupted thread always fails.
     */
    private static boolean waitForRetry(long deadline, boolean nullOnBusy) throws CoordinationException {
        if (System.nanoTime() >= deadline) {
            if (nullOnBusy) {
                return false;
            }
            throw new CoordinationException("event append lock timeout");
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new CoordinationException("event append lock interrupted");
        }
        return true;
    }

    /**
     * Returns whether this handle currently owns the project lock.
     *
     * @return true while held
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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
            try {
                channel.close();
            } finally {
                if (guardHeld.getAndSet(false)) {
                    guard.release();
                }
            }
        }
    }
}
