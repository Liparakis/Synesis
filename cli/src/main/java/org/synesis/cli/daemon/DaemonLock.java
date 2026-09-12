package org.synesis.cli.daemon;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Operating-system-backed singleton lock for one installation daemon.
 *
 * <p>The lock is held for the lifetime of this object. Closing it is
 * idempotent and releases both the lock and its channel.</p>
 */
final class DaemonLock implements AutoCloseable {

  private final FileChannel channel;
  private final FileLock lock;

  private DaemonLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * Acquires an exclusive lock without deleting or replacing the lock file.
   *
   * @param path lock path
   * @return held singleton lock
   * @throws IOException when another owner holds the lock or it cannot be opened
   */
  static DaemonLock acquire(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Path normalized = path.toAbsolutePath().normalize();
    Path parent = normalized.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    FileChannel channel = FileChannel.open(normalized, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        channel.close();
        throw new IOException("daemon_already_running");
      }
      return new DaemonLock(channel, lock);
    } catch (OverlappingFileLockException failure) {
      channel.close();
      throw new IOException("daemon_already_running", failure);
    } catch (IOException failure) {
      channel.close();
      throw failure;
    }
  }

  /**
   * Reports whether this object still owns its operating-system lock.
   *
   * @return whether the lock is currently valid
   */
  boolean isHeld() {
    return lock.isValid();
  }

  /**
   * Releases the lock and channel.
   *
   * @throws IOException when release fails
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
