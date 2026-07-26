package org.synesis.workspace.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.project.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Creates verified project-metadata backups and restores only the files in a
 * prepared migration manifest.
 */
public final class ProjectMigrationRestorationService {

    /** Restoration outcome. */
    public enum Outcome {
        /** Every planned file and post-restore verification passed. */
        RESTORED,
        /** Restoration could not be proven safe. */
        REQUIRES_HUMAN_REVIEW
    }

    /** One exact mutable metadata file in a backup manifest.
     * @param target exact project metadata target
     * @param backup external backup path
     * @param sourceHash original content hash
     * @param backupHash backup content hash
     */
    public record MutableFile(Path target, Path backup, String sourceHash, String backupHash) {
        /** Validates a manifest entry. */
        public MutableFile {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(backup, "backup");
            Objects.requireNonNull(sourceHash, "source hash");
            Objects.requireNonNull(backupHash, "backup hash");
        }
    }

    /** Immutable verified backup manifest.
     * @param planId migration plan ID
     * @param planHash canonical prepared-plan hash
     * @param projectId project identity
     * @param nodeId node identity
     * @param keyBytesHash cryptographic key-tree hash
     * @param repositoryIdentity repository identity
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @param rollbackCompatibility rollback classification
     * @param creationSequence backup sequence
     * @param files exact mutable files
     * @param manifestHash canonical manifest hash
     */
    public record BackupManifest(String planId, String planHash, String projectId, String nodeId, String keyBytesHash,
                                 String repositoryIdentity,
                                 int sourceSchema, int targetSchema, String rollbackCompatibility,
                                 long creationSequence, List<MutableFile> files, String manifestHash) {
        /** Validates and freezes a manifest. */
        public BackupManifest {
            Objects.requireNonNull(planId, "plan ID");
            Objects.requireNonNull(planHash, "plan hash");
            Objects.requireNonNull(projectId, "project ID");
            Objects.requireNonNull(nodeId, "node ID");
            Objects.requireNonNull(keyBytesHash, "key bytes hash");
            Objects.requireNonNull(repositoryIdentity, "repository identity");
            Objects.requireNonNull(rollbackCompatibility, "rollback compatibility");
            files = List.copyOf(Objects.requireNonNull(files, "files"));
            Objects.requireNonNull(manifestHash, "manifest hash");
        }
    }

    /** Bounded restoration result with proof flags for the operator.
     * @param outcome restoration outcome
     * @param reason stable reason
     * @param metadataRestored metadata restoration result
     * @param eventLogBytesUnchanged event bytes unchanged
     * @param eventHashChainValid event chain valid
     * @param allProjectionsReplayed all projections replayed
     * @param semanticStateEquivalent semantic state equivalent
     * @param snapshotReferencesValid snapshot references valid
     * @param identitiesUnchanged identities unchanged
     */
    public record Result(Outcome outcome, String reason, boolean metadataRestored,
                         boolean eventLogBytesUnchanged, boolean eventHashChainValid,
                         boolean allProjectionsReplayed, boolean semanticStateEquivalent,
                         boolean snapshotReferencesValid, boolean identitiesUnchanged) {
        /** Validates a restoration result. */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Test-only hook used to inject a restoration write failure. */
    @FunctionalInterface
    interface RestoreFailureInjector {
        /** Invokes before replacing one target. @param target target path @throws IOException injected failure */
        void beforeReplace(Path target) throws IOException;
    }

    /** Creates a restoration service. */
    public ProjectMigrationRestorationService() {
    }

    /**
     * Creates and verifies a backup manifest outside the project checkout.
     *
     * @param adminRoot external administrative root
     * @param planId migration plan ID
     * @param location initialized project location
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @param rollbackCompatibility rollback classification
     * @param files exact mutable metadata files
     * @param before pre-migration semantic snapshot
     * @return verified manifest
     * @throws IOException if any target or backup cannot be verified
     */
    public BackupManifest prepare(Path adminRoot, String planId, ProjectApplicationService.ProjectLocation location,
                                  int sourceSchema, int targetSchema, String rollbackCompatibility,
                                  List<Path> files, PostMigrationReplayVerifier.MigrationSemanticSnapshot before)
            throws IOException {
        return prepare(adminRoot, planId, hash(planId), location, sourceSchema, targetSchema, rollbackCompatibility, files, before);
    }

    /** Creates a backup manifest with the caller's canonical plan hash.
     * @param adminRoot external administrative root
     * @param planId migration plan ID
     * @param planHash canonical prepared-plan hash
     * @param location initialized project location
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @param rollbackCompatibility rollback classification
     * @param files exact mutable metadata files
     * @param before pre-migration semantic snapshot
     * @return verified manifest
     * @throws IOException if any target or backup cannot be verified
     */
    public BackupManifest prepare(Path adminRoot, String planId, String planHash,
                                  ProjectApplicationService.ProjectLocation location,
                                  int sourceSchema, int targetSchema, String rollbackCompatibility,
                                  List<Path> files, PostMigrationReplayVerifier.MigrationSemanticSnapshot before)
            throws IOException {
        Objects.requireNonNull(adminRoot, "admin root");
        Objects.requireNonNull(planId, "plan ID");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(before, "before");
        Path backupRoot = adminRoot.toAbsolutePath().normalize().resolve("migration-backups").resolve(planId);
        if (backupRoot.startsWith(location.root().toAbsolutePath().normalize())) {
            throw new IOException("migration backup must be outside project checkout");
        }
        Files.createDirectories(backupRoot);
        List<Path> targets = files.stream().map(path -> exactTarget(location, path)).distinct().sorted().toList();
        List<MutableFile> entries = new ArrayList<>();
        int sequence = 0;
        for (Path target : targets) {
            if (Files.isSymbolicLink(target) || hasSymlinkAncestor(target, location.synesisDirectory())
                    || !target.startsWith(location.synesisDirectory()) || forbiddenMetadataPath(target, location)) {
                throw new IOException("migration target is not an exact local metadata file");
            }
            if (!Files.isRegularFile(target)) {
                throw new IOException("migration target missing");
            }
            byte[] data = Files.readAllBytes(target);
            Path backup = backupRoot.resolve(String.format("%03d-%s.bak", sequence++, target.getFileName()));
            Files.write(backup, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            String sourceHash = hash(data);
            String backupHash = hash(Files.readAllBytes(backup));
            if (!sourceHash.equals(backupHash)) throw new IOException("backup hash mismatch");
            entries.add(new MutableFile(target, backup, sourceHash, backupHash));
        }
        BackupManifest manifest = new BackupManifest(planId, planHash, before.projectId(), before.nodeId(), keyFingerprint(location),
                repositoryIdentity(location.root()), sourceSchema, targetSchema, rollbackCompatibility, sequence,
                entries, "");
        String manifestHash = hash(ProviderJson.write(serializable(manifest, false)));
        manifest = new BackupManifest(manifest.planId(), manifest.planHash(), manifest.projectId(), manifest.nodeId(), manifest.keyBytesHash(),
                manifest.repositoryIdentity(), manifest.sourceSchema(), manifest.targetSchema(),
                manifest.rollbackCompatibility(), manifest.creationSequence(), manifest.files(), manifestHash);
        Path manifestPath = backupRoot.resolve("manifest.json");
        Files.writeString(manifestPath, ProviderJson.write(serializable(manifest, true)), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return manifest;
    }

    /**
     * Restores a verified manifest and proves the original semantic state.
     *
     * @param adminRoot external administrative root
     * @param manifest verified manifest
     * @param location project location
     * @param before pre-migration semantic snapshot
     * @param expectedCurrentHashes hashes written by this migration
     * @return bounded restoration result
     */
    public Result restore(Path adminRoot, BackupManifest manifest, ProjectApplicationService.ProjectLocation location,
                          PostMigrationReplayVerifier.MigrationSemanticSnapshot before,
                          Map<Path, String> expectedCurrentHashes) {
        return restore(adminRoot, manifest, location, before, expectedCurrentHashes, target -> {
        });
    }

    Result restore(Path adminRoot, BackupManifest manifest, ProjectApplicationService.ProjectLocation location,
                   PostMigrationReplayVerifier.MigrationSemanticSnapshot before,
                   Map<Path, String> expectedCurrentHashes, RestoreFailureInjector injector) {
        Path journal = adminRoot.toAbsolutePath().normalize().resolve("migration-executions")
                .resolve(manifest.planId() + ".jsonl");
        try {
            Map<Path, String> expected = new java.util.HashMap<>(expectedCurrentHashes);
            if (expected.isEmpty()) expected.putAll(recoverExpectedHashes(journal));
            for (Map.Entry<Path, String> entry : expected.entrySet()) {
                appendExpectedHash(journal, entry.getKey(), entry.getValue());
            }
            append(journal, "RESTORE_REQUIRED");
            verifyManifest(adminRoot, manifest);
            verifyIdentityAndRepository(manifest, location);
            append(journal, "RESTORE_PRECONDITIONS_VERIFIED");
            append(journal, "RESTORING_METADATA");
            for (MutableFile file : manifest.files()) {
                if (!file.target().toAbsolutePath().normalize().startsWith(location.synesisDirectory())
                        || forbiddenMetadataPath(file.target(), location)
                        || hasSymlinkAncestor(file.target(), location.synesisDirectory())) {
                    throw new IOException("project_restore_target_changed");
                }
                restoreFile(file, expected.get(file.target()), injector);
                append(journal, "METADATA_RESTORED " + file.target());
            }
            append(journal, "RESTORE_REPLAY_VERIFYING");
            ProjectApplicationService.ProjectLocation reopened = new ProjectApplicationService().require(location.root());
            if (!reopened.projectId().equals(location.projectId())) throw new IOException("project_restore_target_changed");
            PostMigrationReplayVerifier.MigrationSemanticSnapshot after = new PostMigrationReplayVerifier().capture(reopened);
            PostMigrationReplayVerifier.ProjectionReplayVerificationResult comparison = new PostMigrationReplayVerifier()
                    .compare(before, after);
            boolean keysUnchanged = keyFingerprint(location).equals(manifest.keyBytesHash());
            boolean identities = comparison.identitiesUnchanged() && keysUnchanged
                    && manifest.projectId().equals(after.projectId()) && manifest.nodeId().equals(after.nodeId());
            if (!comparison.successful() || !identities) {
                append(journal, "RESTORE_FAILED_REQUIRES_REVIEW project_restore_replay_failed");
                return new Result(Outcome.REQUIRES_HUMAN_REVIEW, "project_restore_replay_failed", false,
                        comparison.eventLogBytesUnchanged(), comparison.eventHashChainValid(),
                        comparison.allProjectionsReplayed(), comparison.semanticStateEquivalent(),
                        comparison.snapshotReferencesValid(), identities);
            }
            append(journal, "RESTORE_VERIFIED");
            return new Result(Outcome.RESTORED, "project_restore_verified", true,
                    comparison.eventLogBytesUnchanged(), comparison.eventHashChainValid(),
                    comparison.allProjectionsReplayed(), comparison.semanticStateEquivalent(),
                    comparison.snapshotReferencesValid(), identities);
        } catch (Exception failure) {
            try {
                append(journal, "RESTORE_FAILED_REQUIRES_REVIEW project_restore_requires_human_review");
            } catch (IOException ignored) {
                // Preserve the bounded human-review result even if journaling is unavailable.
            }
            return new Result(Outcome.REQUIRES_HUMAN_REVIEW, "project_restore_requires_human_review", false,
                    false, false, false, false, false, false);
        }
    }

    /** Loads and verifies a persisted backup manifest for restart recovery.
     * @param adminRoot external administrative root
     * @param planId migration plan ID
     * @return verified manifest
     * @throws IOException if the manifest is absent or tampered
     */
    public BackupManifest load(Path adminRoot, String planId) throws IOException {
        Path root = adminRoot.toAbsolutePath().normalize().resolve("migration-backups").resolve(planId);
        Object parsed = ProviderJson.parse(Files.readString(root.resolve("manifest.json"), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> map)) throw new IOException("invalid migration backup manifest");
        String projectId = string(map, "projectId");
        String nodeId = string(map, "nodeId");
        String keyBytesHash = string(map, "keyBytesHash");
        String repository = string(map, "repositoryIdentity");
        List<MutableFile> files = new ArrayList<>();
        Object values = map.get("files");
        if (!(values instanceof List<?> list)) throw new IOException("invalid migration file list");
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> entry)) throw new IOException("invalid migration file entry");
            files.add(new MutableFile(Path.of(string(entry, "target")), Path.of(string(entry, "backup")),
                    string(entry, "sourceHash"), string(entry, "backupHash")));
        }
        BackupManifest manifest = new BackupManifest(planId, string(map, "planHash"), projectId, nodeId, keyBytesHash, repository,
                ((Number) map.get("sourceSchema")).intValue(), ((Number) map.get("targetSchema")).intValue(),
                string(map, "rollbackCompatibility"), ((Number) map.get("creationSequence")).longValue(), files,
                string(map, "manifestHash"));
        verifyManifest(adminRoot, manifest);
        return manifest;
    }

    private static void restoreFile(MutableFile file, String expectedCurrentHash, RestoreFailureInjector injector)
            throws IOException {
        Path target = file.target().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target)) throw new IOException("project_restore_target_changed");
        byte[] backup = Files.readAllBytes(file.backup());
        if (!hash(backup).equals(file.backupHash())) throw new IOException("project_restore_backup_invalid");
        if (Files.isRegularFile(target)) {
            String currentHash = hash(Files.readAllBytes(target));
            if (currentHash.equals(file.backupHash())) return;
            if (expectedCurrentHash == null || !currentHash.equals(expectedCurrentHash)) {
                throw new IOException("project_restore_target_changed");
            }
        } else if (expectedCurrentHash == null || !"MISSING".equals(expectedCurrentHash)) {
            throw new IOException("project_restore_target_changed");
        }
        injector.beforeReplace(target);
        Path temporary = target.resolveSibling(target.getFileName() + ".restore-" + UUID.randomUUID());
        Files.write(temporary, backup, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (!hash(Files.readAllBytes(temporary)).equals(file.backupHash())) {
            Files.deleteIfExists(temporary);
            throw new IOException("project_restore_hash_mismatch");
        }
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        if (!Files.isRegularFile(target) || !hash(Files.readAllBytes(target)).equals(file.backupHash())) {
            throw new IOException("project_restore_hash_mismatch");
        }
    }

    private static void verifyManifest(Path adminRoot, BackupManifest manifest) throws IOException {
        String expected = hash(ProviderJson.write(serializable(manifest, false)));
        if (!expected.equals(manifest.manifestHash())) throw new IOException("project_restore_backup_invalid");
        for (MutableFile file : manifest.files()) {
            if (!Files.isRegularFile(file.backup()) || Files.isSymbolicLink(file.backup())
                    || !hash(Files.readAllBytes(file.backup())).equals(file.backupHash())) {
                throw new IOException("project_restore_backup_invalid");
            }
        }
        Path expectedRoot = adminRoot.toAbsolutePath().normalize().resolve("migration-backups")
                .resolve(manifest.planId());
        for (MutableFile file : manifest.files()) {
            if (!file.backup().toAbsolutePath().normalize().startsWith(expectedRoot)) {
                throw new IOException("project_restore_backup_invalid");
            }
        }
    }

    private static void verifyIdentityAndRepository(BackupManifest manifest,
                                                      ProjectApplicationService.ProjectLocation location)
            throws IOException {
        if (!manifest.projectId().equals(location.projectId().toString())
                || !manifest.repositoryIdentity().equals(repositoryIdentity(location.root()))
                || !manifest.keyBytesHash().equals(keyFingerprint(location))) {
            throw new IOException("project_restore_target_changed");
        }
        try {
            String node = new IdentityBootstrap(location.profile().resolve("link")).inspect().nodeId();
            if (!manifest.nodeId().equals(node)) throw new IOException("project_restore_target_changed");
        } catch (Exception failure) {
            throw new IOException("project_restore_target_changed", failure);
        }
    }

    private static Path exactTarget(ProjectApplicationService.ProjectLocation location, Path input) {
        Path target = input.isAbsolute() ? input : location.root().resolve(input);
        return target.toAbsolutePath().normalize();
    }

    private static boolean forbiddenMetadataPath(Path target, ProjectApplicationService.ProjectLocation location) {
        Path synesis = location.synesisDirectory();
        return target.startsWith(synesis.resolve("coordination"))
                || target.startsWith(synesis.resolve("local/snapshots"))
                || target.startsWith(synesis.resolve("local/profile/link"));
    }

    private static boolean hasSymlinkAncestor(Path target, Path root) {
        Path current = target;
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) return true;
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return false;
    }

    private static String repositoryIdentity(Path root) {
        try {
            Path git = root.resolve(".git");
            return git.toRealPath().toString();
        } catch (IOException unavailable) {
            return "NO_GIT:" + root.toAbsolutePath().normalize();
        }
    }

    private static String keyFingerprint(ProjectApplicationService.ProjectLocation location) throws IOException {
        Path keys = location.profile().resolve("link");
        if (!Files.isDirectory(keys)) return "missing";
        StringBuilder value = new StringBuilder();
        try (var stream = Files.walk(keys)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                value.append(keys.relativize(file)).append(':').append(hash(Files.readAllBytes(file))).append('\n');
            }
        }
        return hash(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(Path journal, String state) throws IOException {
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "state=" + state + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Map<Path, String> recoverExpectedHashes(Path journal) {
        Map<Path, String> expected = new java.util.HashMap<>();
        if (!Files.isRegularFile(journal)) return expected;
        try {
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                Object parsed;
                try {
                    parsed = ProviderJson.parse(line);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (parsed instanceof Map<?, ?> map && "MIGRATION_TARGET".equals(map.get("state"))
                        && map.get("target") instanceof String target && map.get("hash") instanceof String hash) {
                    expected.put(Path.of(target).toAbsolutePath().normalize(), hash);
                }
            }
        } catch (Exception ignored) {
            // The caller will fail closed when an expected hash remains unavailable.
        }
        return expected;
    }

    private static void appendExpectedHash(Path journal, Path target, String hash) throws IOException {
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, ProviderJson.write(Map.of("state", "MIGRATION_TARGET", "target", target.toString(),
                "hash", hash)) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String string(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String string)) throw new IOException("invalid migration manifest field");
        return string;
    }

    private static Map<String, Object> serializable(BackupManifest manifest, boolean includeHash) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("planId", manifest.planId());
        value.put("planHash", manifest.planHash());
        value.put("projectId", manifest.projectId());
        value.put("nodeId", manifest.nodeId());
        value.put("keyBytesHash", manifest.keyBytesHash());
        value.put("repositoryIdentity", manifest.repositoryIdentity());
        value.put("sourceSchema", manifest.sourceSchema());
        value.put("targetSchema", manifest.targetSchema());
        value.put("rollbackCompatibility", manifest.rollbackCompatibility());
        value.put("creationSequence", manifest.creationSequence());
        List<Map<String, Object>> files = new ArrayList<>();
        for (MutableFile file : manifest.files()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("target", file.target().toString());
            entry.put("backup", file.backup().toString());
            entry.put("sourceHash", file.sourceHash());
            entry.put("backupHash", file.backupHash());
            files.add(entry);
        }
        value.put("files", files);
        if (includeHash) value.put("manifestHash", manifest.manifestHash());
        return value;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hash(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }
}
