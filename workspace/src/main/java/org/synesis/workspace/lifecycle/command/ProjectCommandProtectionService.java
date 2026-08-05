package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Acquires command protection in process-local and permanent filesystem order. */
public final class ProjectCommandProtectionService {

    private static final ConcurrentHashMap<Path, ReentrantLock> ADMISSION_LOCKS = new ConcurrentHashMap<>();
    private final Path namespaceRoot;

    /** Creates a protection service for one host-wide command namespace.
     * @param namespaceRoot host-wide command namespace root
     */
    public ProjectCommandProtectionService(Path namespaceRoot) {
        this.namespaceRoot = Objects.requireNonNull(namespaceRoot, "namespaceRoot")
                .toAbsolutePath().normalize();
    }

    /** Acquires namespace and physical-worktree protection with bounded waiting.
     * @param identity verified worktree identity
     * @return held protection permit
     * @throws IOException if protection cannot be acquired
     */
    public ProtectionPermit acquire(PhysicalWorktreeIdentity identity) throws IOException {
        Objects.requireNonNull(identity, "identity");
        ReentrantLock admission = ADMISSION_LOCKS.computeIfAbsent(namespaceRoot, ignored -> new ReentrantLock());
        try {
            if (!admission.tryLock(2L, TimeUnit.SECONDS)) {
                throw new IOException("COMMAND_ADMISSION_MUTEX_TIMEOUT");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("COMMAND_ADMISSION_MUTEX_INTERRUPTED", interrupted);
        }
        ProjectCommandNamespace namespace = null;
        CommandPermanentLock worktreeLock = null;
        try {
            namespace = ProjectCommandNamespace.open(namespaceRoot);
            namespace.reconcileIndex();
            namespace.publishScope(identity);
            Path worktreeLockPath = namespace.worktreeLockPath(identity.locator());
            namespace.close();
            namespace = null;
            worktreeLock = CommandPermanentLock.open(worktreeLockPath);
            return new ProtectionPermit(admission, worktreeLock, identity.locator());
        } catch (IOException | RuntimeException failure) {
            if (worktreeLock != null) {
                worktreeLock.close();
            }
            if (namespace != null) {
                namespace.close();
            }
            admission.unlock();
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw failure;
        }
    }

    /** Owns one command-protection acquisition and releases all resources in reverse order. */
    public static final class ProtectionPermit implements AutoCloseable {
        private final ReentrantLock admission;
        private final CommandPermanentLock worktreeLock;
        private final String worktreeLocator;
        private boolean closed;

        private ProtectionPermit(ReentrantLock admission,
                CommandPermanentLock worktreeLock, String worktreeLocator) {
            this.admission = admission;
            this.worktreeLock = worktreeLock;
            this.worktreeLocator = worktreeLocator;
        }

        /** Returns the protected physical-worktree locator.
         * @return worktree locator
         */
        public String worktreeLocator() {
            return worktreeLocator;
        }

        /** Returns whether the worktree lock remains held.
         * @return true while protection is active
         */
        public boolean isHeld() {
            return !closed && worktreeLock.isHeld();
        }

        /** Releases worktree protection, namespace protection, and the process mutex. */
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                worktreeLock.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
            admission.unlock();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
