package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lets one integration pass at a time run for a project, across threads and processes (ADR-0098, invariant 8).
 *
 * <p>An integration pass spends minutes outside the log, in a worktree and the project's test gate, so it cannot
 * hold the {@link ProjectAppendLock}: every other writer would give up after two seconds. This is the lock that
 * keeps two passes from running that slow part at once. It lives in its own file, {@code integration.lock}, and is
 * only ever tried without waiting; nothing that appends to the log ever takes it, so no writer waits for a merge.
 * A pass that finds it taken has nothing to do, because the pass that holds it will integrate what is ready.</p>
 *
 * <p>A holder that dies releases the operating-system lock, so an attempt found started by the holder of this lock
 * was left by a process that is gone.</p>
 */
public final class ProjectIntegrationLock implements AutoCloseable {

    private static final String FILE = "integration.lock";

    private final ProjectAppendLock held;

    private ProjectIntegrationLock(ProjectAppendLock held) {
        this.held = held;
    }

    /**
     * Takes the project's integration lock if nobody holds it.
     *
     * @param root project coordination root
     * @return the held lock, or {@code null} when another pass is running
     * @throws IOException when the lock file cannot be opened
     */
    public static ProjectIntegrationLock tryAcquire(Path root) throws IOException {
        ProjectAppendLock file = ProjectAppendLock.tryAcquireFile(root, FILE);
        return file == null ? null : new ProjectIntegrationLock(file);
    }

    /**
     * Releases the lock.
     *
     * @throws IOException when the operating system fails to release it
     */
    @Override
    public void close() throws IOException {
        held.close();
    }
}
