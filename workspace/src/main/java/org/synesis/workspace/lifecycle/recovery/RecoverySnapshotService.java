package org.synesis.workspace.lifecycle.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Materializes a suspended lane into an immutable Synesis-owned recovery snapshot.
 */
public final class RecoverySnapshotService {

    /**
     * Creates a recovery snapshot materializer.
     */
    public RecoverySnapshotService() {
    }

    private static Snapshot readExisting(Path root) throws IOException {
        Object parsed = ProviderJson.parse(Files.readString(root.resolve("manifest.json")));
        if (!(parsed instanceof java.util.Map<?, ?> map)) {
            throw new IOException("RECOVERY_MANIFEST_INVALID");
        }
        List<String> paths = map.get("paths") instanceof List<?> values
                ? values.stream()
                  .map(String::valueOf)
                  .toList() : List.of();
        return new Snapshot(String.valueOf(map.get("snapshotId")), root,
                String.valueOf(map.get("contentHash")), paths);
    }

    private static String hash(Path root, List<String> paths) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String relative : paths) {
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(root.resolve(relative)));
            }
            return HexFormat.of()
                    .formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IOException("RECOVERY_SNAPSHOT_HASH_FAILED", failure);
        }
    }

    /**
     * Materializes a complete immutable snapshot for a suspended session.
     *
     * @param location  project location
     * @param sessionId exact session identifier
     * @return immutable snapshot metadata
     * @throws IOException when the binding or snapshot cannot be read
     */
    public Snapshot materialize(ProjectApplicationService.ProjectLocation location, String sessionId)
            throws IOException {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(sessionId, "sessionId");
        ProviderSessionBindingService.Binding binding;
        try {
            binding = new ProviderSessionBindingService().list(location, "codex")
                    .stream()
                    .filter(candidate -> sessionId.equals(candidate.sessionId()))
                    .findFirst()
                    .orElse(null);
            if (binding == null) {
                for (String provider : List.of("antigravity", "claude")) {
                    binding = new ProviderSessionBindingService().list(location, provider)
                            .stream()
                            .filter(candidate -> sessionId.equals(candidate.sessionId()))
                            .findFirst()
                            .orElse(null);
                    if (binding != null) {
                        break;
                    }
                }
            }
        } catch (ProviderSessionBindingService.BindingException failure) {
            throw new IOException("RECOVERY_BINDING_UNAVAILABLE", failure);
        }
        if (binding == null || binding.worktreePath() == null) {
            throw new IOException("RECOVERY_WORKTREE_NOT_FOUND");
        }
        Path source = Path.of(binding.worktreePath())
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("RECOVERY_WORKTREE_NOT_FOUND");
        }
        Path root = location.synesisDirectory()
                .resolve("local/recovery-snapshots")
                .resolve(sessionId);
        if (Files.exists(root)) {
            return readExisting(root);
        }
        Path staging = root.resolveSibling(root.getFileName() + ".staging-" + Long.toUnsignedString(System.nanoTime()));
        Files.createDirectories(staging);
        List<String> paths = new ArrayList<>();
        try (var stream = Files.walk(source)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .toList()) {
                if (path.toString()
                        .contains(java.io.File.separator + ".git" + java.io.File.separator)
                        || path.toString()
                        .contains(java.io.File.separator + ".synesis" + java.io.File.separator)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path target = staging.resolve(relative)
                        .normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("RECOVERY_PATH_INVALID");
                }
                Files.createDirectories(target.getParent());
                Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                paths.add(relative.toString()
                        .replace('\\', '/'));
            }
        }
        paths.sort(String::compareTo);
        String hash = hash(staging, paths);
        Files.writeString(staging.resolve("manifest.json"), ProviderJson.write(java.util.Map.of(
                        "snapshotId", sessionId, "contentHash", hash, "paths", paths)) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
        return new Snapshot(sessionId, root, hash, paths);
    }

    /**
     * Restores a verified immutable snapshot into a new isolated lane.
     *
     * @param snapshotReference snapshot root followed by {@code #contentHash}
     * @param targetWorktree    new lane worktree
     * @return restored snapshot metadata
     * @throws IOException invalid reference, hash mismatch, or unsafe target
     */
    public Snapshot restoreToLane(String snapshotReference, Path targetWorktree) throws IOException {
        Objects.requireNonNull(snapshotReference, "snapshotReference");
        Objects.requireNonNull(targetWorktree, "targetWorktree");
        int separator = snapshotReference.lastIndexOf('#');
        if (separator <= 0 || separator == snapshotReference.length() - 1) {
            throw new IOException("RECOVERY_REFERENCE_INVALID");
        }
        Path root = Path.of(snapshotReference.substring(0, separator))
                .toAbsolutePath()
                .normalize();
        String expectedHash = snapshotReference.substring(separator + 1);
        Snapshot snapshot = readExisting(root);
        if (!expectedHash.equals(snapshot.contentHash()) || !expectedHash.equals(hash(root, snapshot.paths()))) {
            throw new IOException("RECOVERY_SNAPSHOT_HASH_MISMATCH");
        }
        Path target = targetWorktree.toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(target)) {
            throw new IOException("RECOVERY_TARGET_NOT_FOUND");
        }
        for (String relative : snapshot.paths()) {
            if (relative.equals("manifest.json")) {
                continue;
            }
            Path source = root.resolve(relative)
                    .normalize();
            Path destination = target.resolve(relative)
                    .normalize();
            if (!destination.startsWith(target) || relative.startsWith(".git/") || relative.startsWith(".synesis/")) {
                throw new IOException("RECOVERY_PATH_INVALID");
            }
            if (!Files.isRegularFile(source)) {
                throw new IOException("RECOVERY_SOURCE_MISSING");
            }
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return snapshot;
    }

    /**
     * Immutable recovery snapshot result.
     *
     * @param snapshotId  snapshot identifier
     * @param root        snapshot root
     * @param contentHash snapshot content hash
     * @param paths       repository-relative paths included
     */
    public record Snapshot(String snapshotId, Path root, String contentHash, List<String> paths) {

        /**
         * Validates and freezes snapshot metadata.
         */
        public Snapshot {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(contentHash, "contentHash");
            paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        }
    }
}
