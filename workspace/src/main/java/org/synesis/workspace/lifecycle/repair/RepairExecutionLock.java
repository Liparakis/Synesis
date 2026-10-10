package org.synesis.workspace.lifecycle.repair;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * File lock ensuring single repair executor per project admin root outside control checkout under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-execution.lock}.
 *
 * @since 1.0
 */
final class RepairExecutionLock implements AutoCloseable {

    private static final Set<Path> HELD_IN_THIS_JVM = ConcurrentHashMap.newKeySet();

    private final Path lockFilePath;
    private final FileChannel channel;
    private final FileLock fileLock;

    private RepairExecutionLock(Path lockFilePath, FileChannel channel, FileLock fileLock) {
        this.lockFilePath = lockFilePath;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    /**
     * Attempts to acquire exclusive repair execution lock.
     *
     * @param controlRoot control project root path
     * @param planId      repair plan ID
     * @return acquired lock instance
     * @throws IOException if lock is held by another process or acquisition fails
     */
    public static RepairExecutionLock acquire(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path lockPath = ProjectAdministrativeLayout.repairExecutionLock(controlRoot);
        Files.createDirectories(lockPath.getParent());

        // A second channel on a file this JVM already locks must never be opened: on some platforms
        // closing it would release the first holder's lock.
        Path heldKey = lockPath.toAbsolutePath().normalize();
        if (!HELD_IN_THIS_JVM.add(heldKey)) {
            throw new IOException("Repair execution lock already held for project: " + lockPath);
        }

        FileChannel fc;
        try {
            fc = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException failure) {
            HELD_IN_THIS_JVM.remove(heldKey);
            throw failure;
        }

        try {
            FileLock fl = fc.tryLock();
            if (fl == null) {
                throw new IOException("Repair execution lock already held for project: " + lockPath);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("acquiredAtEpochMillis", System.currentTimeMillis());
            metadata.put("pid", ProcessHandle.current().pid());
            metadata.put("planId", planId);
            metadata.put("controlRepositoryPath", controlRoot.toAbsolutePath().normalize().toString());

            byte[] payload = ProviderJson.write(metadata).getBytes(StandardCharsets.UTF_8);
            fc.truncate(0);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(payload);
            while (buffer.hasRemaining()) {
                if (fc.write(buffer) == 0) {
                    throw new IOException("repair lock metadata write made no progress");
                }
            }
            fc.force(true);

            return new RepairExecutionLock(lockPath, fc, fl);
        } catch (IOException | RuntimeException failure) {
            try {
                fc.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                HELD_IN_THIS_JVM.remove(heldKey);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } finally {
            try {
                Files.deleteIfExists(lockFilePath);
            } catch (IOException ignored) {
            } finally {
                HELD_IN_THIS_JVM.remove(lockFilePath.toAbsolutePath().normalize());
            }
        }
    }
}
