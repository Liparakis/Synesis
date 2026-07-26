package org.synesis.workspace.lifecycle.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Service managing pre-mutation administrative file backups and exact rollback operations under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-backups\<execution-id>\}.
 *
 * @since 1.0
 */
public final class RepairBackupService {

    /**
     * Creates a repair backup service.
     */
    public RepairBackupService() {
    }

    /**
     * Resolves the backup directory for a repair execution run.
     *
     * @param controlRoot control project root path
     * @param executionId opaque execution ID
     * @return backup directory path
     */
    public static Path resolveBackupDirectory(Path controlRoot, String executionId) {
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        return workspaceRoot.resolve("admin").resolve("repair-backups").resolve(executionId);
    }

    /**
     * Creates an atomic backup of a target administrative file prior to mutation.
     *
     * @param controlRoot control project root path
     * @param executionId execution ID
     * @param targetFile  target administrative file
     * @return backup file path
     * @throws IOException if backup fails
     */
    public Path createBackup(Path controlRoot, String executionId, Path targetFile) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(targetFile, "targetFile");

        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            throw new IOException("Target is not a regular file for backup: " + targetFile);
        }

        Path backupDir = resolveBackupDirectory(controlRoot, executionId);
        Files.createDirectories(backupDir);

        String fileName = targetFile.getFileName().toString();
        Path backupFile = backupDir.resolve(fileName + ".bak");

        String contentHash = computeFileSha256(targetFile);
        Files.copy(targetFile, backupFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

        Path manifestFile = backupDir.resolve("manifest.json");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("executionId", executionId);
        manifest.put("originalPath", targetFile.toAbsolutePath().normalize().toString());
        manifest.put("backupPath", backupFile.toAbsolutePath().normalize().toString());
        manifest.put("contentHash", contentHash);
        manifest.put("createdAtEpochMillis", System.currentTimeMillis());

        Files.writeString(manifestFile, ProviderJson.write(manifest), StandardCharsets.UTF_8);

        return backupFile;
    }

    /**
     * Rollback a previous repair execution run by restoring backed-up files.
     *
     * @param controlRoot control project root path
     * @param executionId execution ID
     * @throws IOException if rollback fails or manifest is invalid
     */
    public void rollbackExecution(Path controlRoot, String executionId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(executionId, "executionId");

        Path backupDir = resolveBackupDirectory(controlRoot, executionId);
        Path manifestFile = backupDir.resolve("manifest.json");

        if (!Files.exists(manifestFile)) {
            throw new IOException("Backup manifest not found for execution: " + executionId);
        }

        String rawManifest = Files.readString(manifestFile, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) ProviderJson.parse(rawManifest);

        Path originalPath = Path.of((String) manifest.get("originalPath"));
        Path backupPath = Path.of((String) manifest.get("backupPath"));
        String expectedHash = (String) manifest.get("contentHash");

        if (!Files.exists(backupPath)) {
            throw new IOException("Backup file missing for rollback: " + backupPath);
        }

        String actualBackupHash = computeFileSha256(backupPath);
        if (!expectedHash.equals(actualBackupHash)) {
            throw new IOException("Backup file hash mismatch for rollback: expected " + expectedHash + " but found " + actualBackupHash);
        }

        Files.createDirectories(originalPath.getParent());
        Files.copy(backupPath, originalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

        RepairExecutionJournal journal = RepairExecutionJournal.open(controlRoot, executionId);
        journal.append(new RepairExecutionJournal.RepairExecutionRecord(
                executionId, "rollback", "rollback", "ROLLBACK", originalPath.toString(), "ROLLED_BACK", System.currentTimeMillis(), "Successfully restored administrative file from backup"
        ));
    }

    private static String computeFileSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 algorithm unavailable", ex);
        }
    }
}
