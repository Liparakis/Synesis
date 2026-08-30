package org.synesis.workspace.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * Detects and plans identity-preserving project schema migrations.
 *
 * <p>Version 1 metadata remains readable as a legacy project without a
 * configured validation command. Newer or malformed schemas fail closed and
 * are never rewritten by this service.
 */
public final class ProjectMigrationService {

    /**
     * Current writable project metadata schema.
     */
    public static final int CURRENT_SCHEMA = ProjectApplicationService.PROJECT_SCHEMA_VERSION;
    private final Path adminRoot;

    /**
     * Creates a service using the global Synesis administrative root.
     */
    public ProjectMigrationService() {
        this(defaultAdminRoot());
    }

    /**
     * Creates a service with an explicit administrative root.
     *
     * @param adminRoot administrative root
     */
    public ProjectMigrationService(Path adminRoot) {
        this.adminRoot = Objects.requireNonNull(adminRoot, "adminRoot")
                .toAbsolutePath()
                .normalize();
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String planJson(Plan plan) {
        Entry e = plan.entry();
        return ProviderJson.write(Map.of("planId",
                plan.planId(),
                "createdAt",
                plan.createdAt()
                        .toString(),
                "projectRoot",
                plan.projectRoot()
                        .toString(),
                "mutableFiles",
                plan.mutableFiles()
                        .stream()
                        .map(Path::toString)
                        .toList(),
                "entry",
                Map.of("metadata",
                        e.metadata()
                                .toString(),
                        "sourceSchema",
                        e.sourceSchema(),
                        "targetSchema",
                        e.targetSchema(),
                        "sourceHash",
                        e.sourceHash(),
                        "outcome",
                        e.outcome()
                                .name(),
                        "projectId",
                        e.projectId())));
    }

    private static Path defaultAdminRoot() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), "AppData", "Local")
                    .toString();
        }
        return Path.of(base, "Synesis", "admin");
    }

    /**
     * Inspects only the initialized project resolved from the supplied directory.
     *
     * @param workingDirectory current working directory
     * @return detected schema entry
     */
    public Entry inspect(Path workingDirectory) {
        try {
            Path current = workingDirectory.toAbsolutePath()
                    .normalize();
            if (!Files.isDirectory(current)) {
                current = current.getParent();
            }
            Path metadata = null;
            while (current != null) {
                Path candidate = current.resolve(".synesis/project.json");
                if (Files.isRegularFile(candidate)) {
                    metadata = candidate;
                    break;
                }
                current = current.getParent();
            }
            if (metadata == null) {
                return new Entry(workingDirectory.toAbsolutePath()
                        .normalize()
                        .resolve(".synesis/project.json"), -1,
                        CURRENT_SCHEMA, "", Outcome.PROJECT_NOT_INITIALIZED, "");
            }
            metadata = metadata.toAbsolutePath()
                    .normalize();
            String raw = Files.readString(metadata, StandardCharsets.UTF_8);
            Object value = ProviderJson.parse(raw);
            if (!(value instanceof Map<?, ?> map) || !(map.get("schemaVersion") instanceof Number number)
                    || !(map.get("projectId") instanceof String projectId)) {
                return new Entry(metadata, -1, CURRENT_SCHEMA, hash(raw), Outcome.REQUIRES_HUMAN_REVIEW, "");
            }
            int schema = number.intValue();
            Outcome outcome = schema == CURRENT_SCHEMA || schema == 1
                    ? Outcome.UP_TO_DATE : Outcome.UNSUPPORTED_SCHEMA;
            return new Entry(metadata, schema, CURRENT_SCHEMA, hash(raw), outcome, projectId);
        } catch (Exception failure) {
            return new Entry(workingDirectory.toAbsolutePath()
                    .normalize()
                    .resolve(".synesis/project.json"), -1,
                    CURRENT_SCHEMA, "", Outcome.PROJECT_NOT_INITIALIZED, "");
        }
    }

    /**
     * Creates and persists an immutable plan for the current project.
     *
     * @param workingDirectory current working directory
     * @return prepared plan
     * @throws IOException if the plan cannot be persisted
     */
    public Plan prepare(Path workingDirectory) throws IOException {
        Path root = workingDirectory.toAbsolutePath()
                .normalize();
        Entry entry = inspect(root);
        Plan plan = new Plan("pmig-project-" + UUID.randomUUID()
                .toString()
                .replace("-", ""), Instant.now(), root, entry,
                List.of(entry.metadata()));
        Path dir = adminRoot.resolve("migration-plans");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(plan.planId() + ".json"), planJson(plan), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return plan;
    }

    /**
     * Loads a previously prepared project plan.
     *
     * @param planId plan identifier
     * @return persisted plan
     * @throws IOException if the plan is missing or invalid
     */
    public Plan load(String planId) throws IOException {
        if (planId == null || !planId.matches("pmig-project-[a-zA-Z0-9]+")) {
            throw new IOException("invalid migration plan");
        }
        Object value = ProviderJson.parse(Files.readString(adminRoot.resolve("migration-plans")
                .resolve(planId + ".json")));
        if (!(value instanceof Map<?, ?> map) || !(map.get("entry") instanceof Map<?, ?> e)) {
            throw new IOException("invalid migration plan");
        }
        Entry entry = new Entry(Path.of(String.valueOf(e.get("metadata"))),
                ((Number) e.get("sourceSchema")).intValue(),
                ((Number) e.get("targetSchema")).intValue(),
                String.valueOf(e.get("sourceHash")),
                Outcome.valueOf(String.valueOf(e.get("outcome"))),
                String.valueOf(e.get("projectId")));
        List<Path> mutableFiles = new ArrayList<>();
        if (map.get("mutableFiles") instanceof List<?> values) {
            values.forEach(entryPath -> mutableFiles.add(Path.of(String.valueOf(entryPath))));
        }
        return mutableFiles.isEmpty()
                ? new Plan(planId,
                Instant.parse(String.valueOf(map.get("createdAt"))),
                Path.of(String.valueOf(map.get("projectRoot"))),
                entry)
                : new Plan(planId,
                        Instant.parse(String.valueOf(map.get("createdAt"))),
                        Path.of(String.valueOf(map.get("projectRoot"))),
                        entry,
                        mutableFiles);
    }

    /**
     * Executes a prepared plan; supported current schema is a verified no-op.
     *
     * @param plan prepared plan
     * @return execution result
     * @throws IOException if the journal cannot be written
     */
    public Result execute(Plan plan) throws IOException {
        return executeInternal(plan, null, _ -> {
        });
    }

    Result execute(Plan plan, MigrationStep step) throws IOException {
        return executeInternal(plan, step, _ -> {
        });
    }

    @SuppressWarnings("unused")
    Result execute(Plan plan, MigrationStep step, ProjectMigrationRestorationService.RestoreFailureInjector injector)
            throws IOException {
        return executeInternal(plan, step, injector);
    }

    private Result executeInternal(Plan plan, MigrationStep step,
            ProjectMigrationRestorationService.RestoreFailureInjector injector) throws IOException {
        Objects.requireNonNull(plan, "plan");
        ProjectMigrationLock migrationLock = ProjectMigrationLock.acquire(adminRoot, plan.projectRoot());
        if (migrationLock == null) {
            return new Result(Outcome.REQUIRES_HUMAN_REVIEW, "migration_lock_held", true, true);
        }
        try (migrationLock) {
            Entry current = inspect(plan.projectRoot());
            if (!current.sourceHash()
                    .equals(plan.entry()
                            .sourceHash())) {
                return new Result(Outcome.STALE, "project_migration_plan_stale", true, true);
            }
            if (current.outcome() == Outcome.UNSUPPORTED_SCHEMA) {
                return new Result(Outcome.UNSUPPORTED_SCHEMA, "project_schema_unsupported", true, true);
            }
            if (current.outcome() == Outcome.PROJECT_NOT_INITIALIZED) {
                return new Result(Outcome.PROJECT_NOT_INITIALIZED, "project_not_initialized", true, true);
            }
            if (plan.entry()
                    .outcome() == Outcome.MIGRATION_REQUIRED) {
                ProjectApplicationService.ProjectLocation location;
                try {
                    location = new ProjectApplicationService().require(plan.projectRoot());
                } catch (ProjectApplicationService.ProjectApplicationException failure) {
                    return new Result(Outcome.REQUIRES_HUMAN_REVIEW, "project_identity_changed", true, true);
                }
                SessionLeasePolicy policy = new SessionLeasePolicy();
                SessionLeaseService leases = new SessionLeaseService();
                for (var lease : new SessionLeaseStore().listAll(location.root())) {
                    if (lease.projectId()
                            .equals(location.projectId()
                                    .toString())) {
                        SessionLeaseState state = leases.evaluateLiveness(lease, policy);
                        if (state == SessionLeaseState.ACTIVE || state == SessionLeaseState.AMBIGUOUS
                                || state == SessionLeaseState.SUSPECTED_STALE) {
                            return new Result(Outcome.REQUIRES_HUMAN_REVIEW,
                                    "active_session_blocks_project_migration",
                                    true,
                                    true);
                        }
                    }
                }
            }
            if (step != null && plan.entry()
                    .outcome() == Outcome.MIGRATION_REQUIRED) {
                return executeInjected(plan, step, injector);
            }
            PostMigrationReplayVerifier replay = new PostMigrationReplayVerifier();
            PostMigrationReplayVerifier.ProjectionReplayVerificationResult replayResult;
            try {
                ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().require(plan.projectRoot());
                PostMigrationReplayVerifier.MigrationSemanticSnapshot before = replay.capture(location);
                PostMigrationReplayVerifier.MigrationSemanticSnapshot after = replay.capture(location);
                replayResult = replay.compare(before, after);
            } catch (Exception failure) {
                return new Result(Outcome.FAILED, "post_migration_replay_failed", true, false);
            }
            if (!replayResult.successful()) {
                return new Result(Outcome.FAILED, replayResult.reason(), true, false);
            }
            Path journal = adminRoot.resolve("migration-executions")
                    .resolve(plan.planId() + ".jsonl");
            Files.createDirectories(journal.getParent());
            Files.writeString(journal,
                    "outcome=UP_TO_DATE projectId=" + current.projectId() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return new Result(Outcome.UP_TO_DATE, "project_migration_not_required", true, true);
        }
    }

    private Result executeInjected(Plan plan, MigrationStep step,
            ProjectMigrationRestorationService.RestoreFailureInjector injector)
            throws IOException {
        ProjectApplicationService.ProjectLocation location;
        try {
            location = new ProjectApplicationService().require(plan.projectRoot());
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            return new Result(Outcome.REQUIRES_HUMAN_REVIEW, "project_identity_changed", true, true);
        }
        PostMigrationReplayVerifier replay = new PostMigrationReplayVerifier();
        PostMigrationReplayVerifier.MigrationSemanticSnapshot before;
        try {
            before = replay.capture(location);
        } catch (Exception failure) {
            return new Result(Outcome.FAILED, "post_migration_replay_failed", true, false);
        }
        ProjectMigrationRestorationService restoration = new ProjectMigrationRestorationService();
        ProjectMigrationRestorationService.BackupManifest manifest;
        try {
            manifest = restoration.prepare(adminRoot,
                    plan.planId(),
                    hash(planJson(plan).getBytes(StandardCharsets.UTF_8)),
                    location,
                    plan.entry()
                            .sourceSchema(),
                    plan.entry()
                            .targetSchema(),
                    "SAFE",
                    plan.mutableFiles(),
                    before);
            appendJournal(plan.planId(), "MIGRATION_PREPARED");
            appendJournal(plan.planId(), "BACKUPS_VERIFIED");
        } catch (Exception failure) {
            return new Result(Outcome.REQUIRES_HUMAN_REVIEW, "project_restore_backup_invalid", true, true);
        }
        Map<Path, String> expected = new HashMap<>();
        String failureReason = "project_migration_failed";
        try {
            appendJournal(plan.planId(), "PROJECT_MIGRATION_EXECUTING");
            step.apply(location);
            for (ProjectMigrationRestorationService.MutableFile file : manifest.files()) {
                expected.put(file.target(), Files.isRegularFile(file.target())
                        ? hash(Files.readAllBytes(file.target())) : "MISSING");
                appendTargetHash(plan.planId(), file.target(), expected.get(file.target()));
            }
            appendJournal(plan.planId(), "PROJECT_MIGRATION_VERIFIED");
            ProjectApplicationService.ProjectLocation reopened = new ProjectApplicationService().require(location.root());
            if (!reopened.projectId()
                    .equals(location.projectId())) {
                throw new MigrationFailure("project_identity_changed");
            }
            PostMigrationReplayVerifier.MigrationSemanticSnapshot after = replay.capture(reopened);
            PostMigrationReplayVerifier.ProjectionReplayVerificationResult comparison = replay.compare(before, after);
            if (!comparison.successful()) {
                failureReason = "post_migration_replay_mismatch";
                throw new MigrationFailure(failureReason);
            }
            appendJournal(plan.planId(), "MIGRATED");
            return new Result(Outcome.MIGRATED, "project_migration_verified", true, true);
        } catch (Exception failure) {
            if (failure instanceof MigrationFailure migrationFailure) {
                failureReason = migrationFailure.getMessage();
            }
            for (ProjectMigrationRestorationService.MutableFile file : manifest.files()) {
                try {
                    expected.put(file.target(), Files.isRegularFile(file.target())
                            ? hash(Files.readAllBytes(file.target())) : "MISSING");
                } catch (IOException ignored) {
                    expected.put(file.target(), "UNKNOWN");
                }
                appendTargetHash(plan.planId(), file.target(), expected.get(file.target()));
            }
            ProjectMigrationRestorationService.Result restored = restoration.restore(adminRoot,
                    manifest,
                    location,
                    before,
                    expected,
                    injector);
            if (restored.outcome() == ProjectMigrationRestorationService.Outcome.RESTORED) {
                appendJournal(plan.planId(), "FAILED_RESTORED " + failureReason);
                return new Result(Outcome.FAILED_RESTORED, "project_migration_failed_restored", true,
                        restored.eventLogBytesUnchanged() && restored.eventHashChainValid());
            }
            appendJournal(plan.planId(), "RESTORE_FAILED_REQUIRES_REVIEW " + restored.reason());
            return new Result(Outcome.REQUIRES_HUMAN_REVIEW, restored.reason(), false, false);
        }
    }

    private void appendJournal(String planId, String state) throws IOException {
        Path journal = adminRoot.resolve("migration-executions")
                .resolve(planId + ".jsonl");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "state=" + state + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void appendTargetHash(String planId, Path target, String hash) throws IOException {
        Path journal = adminRoot.resolve("migration-executions")
                .resolve(planId + ".jsonl");
        Files.writeString(journal, ProviderJson.write(Map.of("state", "MIGRATION_TARGET", "target", target.toString(),
                        "hash", hash)) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Stable project migration outcomes.
     */
    public enum Outcome {
        /**
         * Current schema is readable.
         */
        UP_TO_DATE,
        /**
         * A supported transition is available.
         */
        MIGRATION_REQUIRED,
        /**
         * Migration completed.
         */
        MIGRATED,
        /**
         * Source changed after planning.
         */
        STALE,
        /**
         * Schema is unsupported.
         */
        UNSUPPORTED_SCHEMA,
        /**
         * No initialized project exists.
         */
        PROJECT_NOT_INITIALIZED,
        /**
         * Migration failed.
         */
        FAILED,
        /**
         * Migration failed and verified metadata restoration completed.
         */
        FAILED_RESTORED,
        /**
         * Rollback is unsafe.
         */
        ROLLBACK_UNSAFE,
        /**
         * Human review is required.
         */
        REQUIRES_HUMAN_REVIEW
    }

    /**
     * Test-only migration seam; production schema detection never registers a step.
     */
    @FunctionalInterface
    interface MigrationStep {

        /**
         * Applies the disposable migration.
         */
        void apply(ProjectApplicationService.ProjectLocation location) throws Exception;
    }

    /**
     * Test-only bounded failure used to inject a post-mutation diagnostic.
     */
    static final class MigrationFailure extends Exception {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        MigrationFailure(String reason) {
            super(reason);
        }
    }

    /**
     * Immutable project migration entry.
     *
     * @param metadata     metadata path
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @param sourceHash   source fingerprint
     * @param outcome      observed outcome
     * @param projectId    project identity
     */
    public record Entry(Path metadata, int sourceSchema, int targetSchema, String sourceHash, Outcome outcome,
                        String projectId) {

        /**
         * Validates an entry.
         */
        public Entry {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(sourceHash, "sourceHash");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * Immutable prepared project migration plan.
     *
     * @param planId       plan identifier
     * @param createdAt    creation time
     * @param projectRoot  selected project root
     * @param entry        migration entry
     * @param mutableFiles exact mutable metadata files
     */
    public record Plan(String planId, Instant createdAt, Path projectRoot, Entry entry, List<Path> mutableFiles) {

        /**
         * Validates a plan.
         */
        public Plan {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(entry, "entry");
            mutableFiles = List.copyOf(Objects.requireNonNull(mutableFiles, "mutable files"));
        }

        /**
         * Creates a plan using the canonical project metadata file only.
         *
         * @param planId      plan identifier
         * @param createdAt   creation time
         * @param projectRoot project root
         * @param entry       migration entry
         */
        public Plan(String planId, Instant createdAt, Path projectRoot, Entry entry) {
            this(planId, createdAt, projectRoot, entry, List.of(entry.metadata()));
        }
    }

    /**
     * Project migration execution result.
     *
     * @param outcome           result outcome
     * @param reason            stable reason code
     * @param identityPreserved whether identities were preserved
     * @param historyPreserved  whether signed history was preserved
     */
    public record Result(Outcome outcome, String reason, boolean identityPreserved, boolean historyPreserved) {

        /**
         * Validates a result.
         */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Owns the project migration lock for one transactional migration. */
    private record ProjectMigrationLock(Path path) implements AutoCloseable {

        static ProjectMigrationLock acquire(Path adminRoot, Path projectRoot) throws IOException {
            Path directory = adminRoot.resolve("migration-locks");
            Files.createDirectories(directory);
            Path lock = directory.resolve(hash(projectRoot.toAbsolutePath()
                    .normalize()
                    .toString()) + ".lock");
            try {
                Files.writeString(lock,
                        "project=" + projectRoot.toAbsolutePath()
                                .normalize() + "\nphase=LOCKED\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ProjectMigrationLock(lock);
            } catch (java.nio.file.FileAlreadyExistsException held) {
                return null;
            }
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }
}
