package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;

/**
 * Project-scoped reconciliation execution lock ensuring strictly single-executor execution over an external
 * project workspace root.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ReconciliationExecutionLock implements AutoCloseable {

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

        Path root = controlRoot.toAbsolutePath()
                .normalize();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        Path adminDir = workspaceRoot.resolve("admin");
        Files.createDirectories(adminDir);

        Path lockFile = adminDir.resolve("reconciliation-execution.lock");

        if (Files.exists(lockFile)) {
            throw new IOException("Reconciliation execution is busy: lock file exists at " + lockFile);
        }

        String nonce = UUID.randomUUID()
                .toString();
        long pid = ProcessHandle.current()
                .pid();
        long now = System.currentTimeMillis();

        Map<String, Object> lockData = new LinkedHashMap<>();
        lockData.put("pid", pid);
        lockData.put("processName", "java");
        lockData.put("processStartTime", now);
        lockData.put("nonce", nonce);
        lockData.put("acquiredAtEpochMillis", now);
        lockData.put("planId", planId);

        String json = ProviderJson.write(lockData);

        try {
            Files.writeString(lockFile,
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ex) {
            throw new IOException("Reconciliation execution is busy: concurrent lock acquisition detected.", ex);
        }

        return new ReconciliationExecutionLock(lockFile, nonce);
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
