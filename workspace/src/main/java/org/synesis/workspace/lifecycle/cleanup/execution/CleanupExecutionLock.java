package org.synesis.workspace.lifecycle.cleanup.execution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Project-scoped execution lock ensuring strictly single-executor execution over an external
 * project workspace root.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
final class CleanupExecutionLock implements AutoCloseable {

    private final Path lockFilePath;
    private final String nonce;
    private boolean acquired;

    private CleanupExecutionLock(Path lockFilePath, String nonce) {
        this.lockFilePath = lockFilePath;
        this.nonce = nonce;
        this.acquired = true;
    }

    /**
     * Attempts to acquire the project cleanup execution lock.
     *
     * @param controlRoot control project root path
     * @param planId      ID of plan being executed
     * @return acquired lock guard instance
     * @throws IOException if lock acquisition fails due to concurrent execution or IO error
     */
    public static CleanupExecutionLock acquire(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        Path lockFile = ProjectAdministrativeLayout.cleanupExecutionLock(root);
        Files.createDirectories(lockFile.getParent());

        String nonce = UUID.randomUUID().toString();
        ProcessHandle self = ProcessHandle.current();
        long now = System.currentTimeMillis();

        Map<String, Object> lockData = new LinkedHashMap<>();
        lockData.put("pid", self.pid());
        lockData.put("processName", "java");
        // Falls back to the acquisition time, which readers treat as "start time unknown".
        lockData.put("processStartTime", self.info().startInstant().map(Instant::toEpochMilli).orElse(now));
        lockData.put("nonce", nonce);
        lockData.put("acquiredAtEpochMillis", now);
        lockData.put("planId", planId);

        String json = ProviderJson.write(lockData);

        if (Files.exists(lockFile)) {
            takeOverFromDeadHolder(lockFile, json);
            return new CleanupExecutionLock(lockFile, nonce);
        }

        try {
            Files.writeString(lockFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ex) {
            throw new IOException("Cleanup execution is busy: concurrent lock acquisition detected.", ex);
        }

        return new CleanupExecutionLock(lockFile, nonce);
    }

    /**
     * Overwrites an existing lock file in place when its holder is provably dead. An OS lock on the
     * file serializes competing takeovers, and rewriting in place (never delete then create) keeps a
     * loser of that race from removing the winner's fresh lock. Any holder that is alive, or whose
     * liveness cannot be determined, keeps the lock.
     */
    private static void takeOverFromDeadHolder(Path lockFile, String json) throws IOException {
        IOException busy = new IOException("Cleanup execution is busy: lock file exists at " + lockFile);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            try (FileLock _ = tryLockOrBusy(channel, busy)) {
                String holder = new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8);
                if (!holderIsDead(holder)) {
                    throw busy;
                }
                byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                channel.write(ByteBuffer.wrap(payload), 0);
                channel.truncate(payload.length);
                channel.force(true);
            }
        } catch (NoSuchFileException vanished) {
            throw new IOException("Cleanup execution is busy: concurrent lock acquisition detected.", vanished);
        }
    }

    private static FileLock tryLockOrBusy(FileChannel channel, IOException busy) throws IOException {
        try {
            FileLock guard = channel.tryLock();
            if (guard == null) {
                throw busy;
            }
            return guard;
        } catch (OverlappingFileLockException heldInThisProcess) {
            throw busy;
        }
    }

    private static boolean holderIsDead(String lockContent) {
        try {
            Map<String, Object> holder = ProviderJson.stringKeyedObject((Map<?, ?>) ProviderJson.parse(lockContent));
            long pid = ((Number) holder.get("pid")).longValue();
            if (pid <= 0) {
                return false;
            }
            long startTime = holder.get("processStartTime") instanceof Number number ? number.longValue() : -1L;
            // Locks written before the start time was recorded carry the acquisition time there, which
            // is always later than the real start, so it cannot be used to fence PID reuse.
            if (holder.get("acquiredAtEpochMillis") instanceof Number acquired && acquired.longValue() == startTime) {
                startTime = -1L;
            }
            ProcessEvidenceState state = ProcessInspector.system().evaluateEvidence(pid, null, null, startTime);
            return state == ProcessEvidenceState.NOT_OBSERVED || state == ProcessEvidenceState.PID_REUSED_OR_MISMATCHED;
        } catch (RuntimeException unreadable) {
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
                // Verify nonce before deleting lock file
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
