package org.synesis.workspace.cleanup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.provider.ProviderJson;

/**
 * Service for safely quarantining narrowly defined unregistered orphan resources under external
 * project workspace administration directory using atomic same-volume moves.
 *
 * @since 1.0
 */
public final class LifecycleQuarantineService {

    private final LifecyclePathVerifier pathVerifier;

    /**
     * Creates a quarantine service with default path verifier.
     */
    public LifecycleQuarantineService() {
        this(new LifecyclePathVerifier());
    }

    /**
     * Creates a quarantine service with explicit path verifier.
     *
     * @param pathVerifier path safety verifier
     */
    public LifecycleQuarantineService(LifecyclePathVerifier pathVerifier) {
        this.pathVerifier = Objects.requireNonNull(pathVerifier, "pathVerifier");
    }

    /**
     * Quarantines an unregistered orphan resource using an atomic filesystem move.
     *
     * @param controlRoot control project root path
     * @param entry       plan entry to quarantine
     * @return generated quarantine ID
     * @throws IOException if quarantine is unavailable or atomic move fails
     */
    public String quarantineResource(Path controlRoot, PersistedCleanupPlanEntry entry) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(entry, "entry");

        if (entry.resourcePath().isBlank()) {
            throw new IOException(CleanupReason.CLEANUP_QUARANTINE_NOT_SUPPORTED.code() + ": virtual resource path");
        }

        Path candidatePath = Path.of(entry.resourcePath()).toAbsolutePath().normalize();

        // Safety verification: Path must be verified under workspace root
        LifecyclePathVerifier.PathVerificationResult pathResult = pathVerifier.verifyPath(controlRoot, candidatePath);
        if (!pathResult.safe()) {
            throw new IOException(CleanupReason.PATH_IDENTITY_UNVERIFIED.code() + ": path failed verification");
        }

        // Must NOT quarantine control checkout, git worktrees, snapshots, event logs, keys
        if (candidatePath.equals(controlRoot) || candidatePath.startsWith(controlRoot)) {
            throw new IOException(CleanupReason.CONTROL_CHECKOUT_PROTECTED.code() + ": cannot quarantine control checkout");
        }

        if (Files.isDirectory(candidatePath) && Files.exists(candidatePath.resolve(".git"))) {
            throw new IOException(CleanupReason.CLEANUP_QUARANTINE_NOT_SUPPORTED.code() + ": registered git worktrees cannot be quarantined");
        }

        Path root = controlRoot.toAbsolutePath().normalize();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        String quarantineId = "quarantine-" + UUID.randomUUID().toString().replace("-", "");
        Path quarantineTargetDir = workspaceRoot.resolve("admin").resolve("quarantine").resolve(quarantineId);

        Files.createDirectories(quarantineTargetDir);
        Path destination = quarantineTargetDir.resolve(candidatePath.getFileName().toString());

        // Perform atomic move
        try {
            Files.move(candidatePath, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException(CleanupReason.CLEANUP_ATOMIC_MOVE_UNAVAILABLE.code() + ": atomic filesystem move not supported across volumes", ex);
        } catch (Exception ex) {
            throw new IOException(CleanupReason.CLEANUP_ATOMIC_MOVE_UNAVAILABLE.code() + ": atomic move failed: " + ex.getMessage(), ex);
        }

        // Write quarantine manifest
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("quarantineId", quarantineId);
        manifest.put("originalPath", candidatePath.toString());
        manifest.put("quarantinedAtEpochMillis", System.currentTimeMillis());
        manifest.put("resourceId", entry.resourceId());
        manifest.put("resourceType", entry.resourceType().name());
        manifest.put("estimatedBytes", entry.estimatedBytes());
        manifest.put("fingerprintHash", entry.fingerprint().metadataHash());

        Path manifestFile = quarantineTargetDir.resolve("quarantine-manifest.json");
        Files.writeString(manifestFile, ProviderJson.write(manifest), StandardCharsets.UTF_8);

        return quarantineId;
    }
}
