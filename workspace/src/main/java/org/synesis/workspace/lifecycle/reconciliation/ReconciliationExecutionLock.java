package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Project-scoped reconciliation execution lock ensuring strictly single-executor execution over an
 * external project workspace root.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
final class ReconciliationExecutionLock implements AutoCloseable {

    private final Path lockFilePath;
    private final String nonce;
    private boolean acquired;

    private ReconciliationExecutionLock(Path lockFilePath, String nonce) {
        this.lockFilePath = lockFilePath;
        this.nonce = nonce;
        this.acquired = true;
    }

    /**
     * Attempts to acquire the project reconciliation execution lock.
     *
     * @param controlRoot control project root path
     * @param planId      ID of plan being executed
     * @return acquired lock guard instance
     * @throws IOException if lock acquisition fails due to concurrent execution or IO error
     */
    public static ReconciliationExecutionLock acquire(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path lockFile = ProjectAdministrativeLayout.reconciliationExecutionLock(root);
        Files.createDirectories(lockFile.getParent());
        String nonce = UUID.randomUUID().toString();
        long pid = ProcessHandle.current().pid();
        long now = System.currentTimeMillis();

        Map<String, Object> lockData = new LinkedHashMap<>();
        lockData.put("pid", pid);
        lockData.put("processName", "java");
        lockData.put("processStartTime", now);
        lockData.put("nonce", nonce);
        lockData.put("acquiredAtEpochMillis", now);
        lockData.put("planId", planId);

        String json = ProviderJson.write(lockData);

        // The decision to reclaim an abandoned lock and the creation of the new one must be one
        // step, or two recoveries could each discard the other's fresh lock. The guard is an
        // operating-system lock, so unlike the lock file it cannot outlive its holder.
        try (FileChannel guard = FileChannel.open(lockFile.resolveSibling("reconciliation-execution.guard"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = guard.lock()) {
            if (Files.exists(lockFile)) {
                if (!abandoned(lockFile, ProcessHandle::of)) {
                    throw new IOException("Reconciliation execution is busy: lock file exists at " + lockFile);
                }
                Files.delete(lockFile);
            }
            Files.writeString(lockFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException | OverlappingFileLockException ex) {
            throw new IOException("Reconciliation execution is busy: concurrent lock acquisition detected.", ex);
        }

        return new ReconciliationExecutionLock(lockFile, nonce);
    }

    /**
     * Decides whether the process that wrote a lock can no longer be running the recovery.
     *
     * <p>Recovery runs inside agent connections, and a provider may end such a process at any
     * moment. A lock that only its dead holder could remove would then block every later recovery
     * of the project, which is the one operation meant to deal with dead processes. The holder is
     * gone when no process has its identifier, or when the process that has it now started after
     * the lock was taken and so merely reuses the number. Anything that cannot be read or decided
     * counts as held: an unreadable lock is left for {@code doctor} and {@code repair}.</p>
     *
     * @param lockFile  existing lock file
     * @param processes lookup from a process identifier to its live handle
     * @return {@code true} only when the recorded holder is provably gone
     */
    static boolean abandoned(Path lockFile, LongFunction<Optional<ProcessHandle>> processes) {
        try {
            if (!(ProviderJson.parse(Files.readString(lockFile, StandardCharsets.UTF_8)) instanceof Map<?, ?> lock)
                    || !(lock.get("pid") instanceof Number holder) || !(lock.get("acquiredAtEpochMillis") instanceof Number acquired)) {
                return false;
            }
            ProcessHandle process = processes.apply(holder.longValue()).orElse(null);
            if (process == null || !process.isAlive()) {
                return true;
            }
            Instant started = process.info().startInstant().orElse(null);
            return started != null && started.toEpochMilli() > acquired.longValue();
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Releases the acquired project execution lock safely.
     */
    @Override
    public synchronized void close() {
        if (acquired && Files.exists(lockFilePath)) {
            try {
                String content = Files.readString(lockFilePath, StandardCharsets.UTF_8);
                if (content.contains(nonce)) {
                    Files.deleteIfExists(lockFilePath);
                }
            } catch (Exception ignored) {
            } finally {
                acquired = false;
            }
        }
    }
}
