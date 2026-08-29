package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Performs the local managed-baseline transaction without mutating unrelated
 * user work. The transaction builds commits from a private Git index, then
 * synchronizes the real index only after a semantic concurrency check.
 */
public final class ManagedBaselineTransactionService {


    private final AdministrativeStateLocator locator;
    private final ManagedPathPolicy pathPolicy;
    private final PhaseHook phaseHook;

    /**
     * Creates a service rooted at the host's Synesis state directory.
     */
    public ManagedBaselineTransactionService() {
        this(new AdministrativeStateLocator(), new ManagedPathPolicy(), _ -> {
        });
    }

    /**
     * Creates a service with explicit local-state and path-policy dependencies.
     *
     * @param locator    administrative state locator
     * @param pathPolicy managed path policy
     */
    public ManagedBaselineTransactionService(AdministrativeStateLocator locator, ManagedPathPolicy pathPolicy) {
        this(locator, pathPolicy, _ -> {
        });
    }

    /**
     * Creates a service with an optional deterministic phase observer.
     *
     * <p>The observer is intended for crash-recovery verification and must not
     * mutate the repository. Throwing from it simulates process loss after the
     * corresponding durable journal phase.</p>
     *
     * @param locator    administrative state locator
     * @param pathPolicy managed path policy
     * @param phaseHook  phase observer used by tests or diagnostics
     */
    public ManagedBaselineTransactionService(AdministrativeStateLocator locator, ManagedPathPolicy pathPolicy,
            PhaseHook phaseHook) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
        this.phaseHook = Objects.requireNonNull(phaseHook, "phaseHook");
    }

    private static boolean differsDigest(Path path, String expected) throws IOException {
        return expected == null || !Files.isRegularFile(path) || !expected.equals(hash(Files.readAllBytes(path)));
    }

    private static String createAdministrativeCommit(Path root, String parent, java.util.Set<String> paths,
            String transactionId) throws IOException {
        Path index = Files.createTempFile("synesis-baseline-", ".index");
        Files.deleteIfExists(index);
        try {
            if (parent.equals("UNBORN")) {
                run(root, index, "read-tree", "--empty");
            } else {
                run(root, index, "read-tree", parent);
            }
            List<String> add = new ArrayList<>();
            add.add("add");
            add.add("-f");
            add.add("-A");
            add.add("--");
            add.addAll(paths);
            run(root, index, add.toArray(String[]::new));
            String tree = run(root, index, "write-tree");
            List<String> commit = new ArrayList<>();
            commit.add("commit-tree");
            commit.add(tree);
            if (!parent.equals("UNBORN")) {
                commit.add("-p");
                commit.add(parent);
            }
            commit.add("-m");
            commit.add("Synesis managed baseline " + transactionId);
            return run(root, index, commit.toArray(String[]::new));
        } finally {
            Files.deleteIfExists(index);
        }
    }

    private static void advanceRef(Path root, String ref, String commit, String expected) throws IOException {
        if (expected.equals("UNBORN")) {
            run(root, null, "update-ref", ref, commit);
        } else {
            run(root, null, "update-ref", ref, commit, expected);
        }
    }

    private static void writeManagedFiles(Path root, Map<String, byte[]> files) throws IOException, BaselineFailure {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = root.resolve(normalizeRelative(entry.getKey()));
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".synesis-tmp-" + UUID.randomUUID());
            Files.write(temporary, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String branchRef(Path root) throws IOException {
        String ref = gitOptional(root, "symbolic-ref", "--quiet", "HEAD");
        return ref.isBlank() ? "HEAD" : ref;
    }

    private static String blockingPaths(ManagedPathPolicy.Report report) {
        return report.findings()
                .stream()
                .filter(ManagedPathPolicy.Finding::blocksTransaction)
                .map(ManagedPathPolicy.Finding::path)
                .findFirst()
                .orElse("unknown");
    }

    private static String normalizeRelative(String path) throws BaselineFailure {
        if (path == null || path.isBlank()) {
            throw failure("INVALID_MANAGED_PATH", path);
        }
        Path candidate = Path.of(path);
        if (candidate.isAbsolute()) {
            throw failure("INVALID_MANAGED_PATH", path);
        }
        Path normalized = candidate
                .normalize();
        if (normalized.startsWith("..") || normalized.toString()
                .equals(".")) {
            throw failure("INVALID_MANAGED_PATH", path);
        }
        return normalized.toString()
                .replace('\\', '/');
    }

    private static String hash(byte[] bytes) throws IOException {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(bytes));
        } catch (Exception failure) {
            throw new IOException("hash unavailable", failure);
        }
    }

    private static Path normalize(Path root) {
        return Objects.requireNonNull(root, "repositoryRoot")
                .toAbsolutePath()
                .normalize();
    }

    private static String run(Path root, Path index, String... args) throws IOException {
        return runOutput(root, index, args, true);
    }

    private static String git(Path root, String... args) throws IOException {
        return runOutput(root, null, args, true);
    }

    private static String gitOptional(Path root, String... args) throws IOException {
        return runOutput(root, null, args, false);
    }

    private static String runOutput(Path root, Path index, String[] args, boolean required) throws IOException {
        if (index == null) {
            return required ? GitProcessRunner.run(root, args) : GitProcessRunner.runOptional(root, args);
        }
        return GitProcessRunner.runWithIndex(root, index, args)
                .trim();
    }

    private static BaselineFailure failure(String code, String message) {
        return new BaselineFailure(code, message);
    }

    private static BaselineFailure failure(String code, String message, Throwable cause) {
        return new BaselineFailure(code, message, cause);
    }

    /**
     * Executes a fresh managed-baseline transaction.
     *
     * @param repositoryRoot repository worktree
     * @param managedFiles   managed repository-relative files and their expected bytes
     * @return durable transaction result
     * @throws BaselineFailure when safety validation or synchronization fails
     */
    public Result prepare(Path repositoryRoot, Map<String, byte[]> managedFiles) throws BaselineFailure {
        Path root = normalize(repositoryRoot);
        if (managedFiles == null || managedFiles.isEmpty()) {
            throw failure("MANAGED_FILES_REQUIRED", "At least one managed file is required");
        }
        ProjectLock transactionLock = null;
        try {
            AdministrativeStateLocator.Resolution resolution = locator.resolve(root);
            transactionLock = ProjectLock.acquire(resolution.administrativeRoot());
            ManagedPathPolicy.Report initial = pathPolicy.inspect(root);
            if (initial.blocked()) {
                throw failure("CONTROL_CHECKOUT_DIRTY", blockingPaths(initial));
            }
            SemanticIndexFingerprint.Fingerprint originalIndex = SemanticIndexFingerprint.capture(root);
            String originalHead = gitOptional(root, "rev-parse", "--verify", "HEAD");
            if (originalHead.isBlank()) {
                originalHead = "UNBORN";
            }
            String transactionId = "txn_" + UUID.randomUUID()
                    .toString()
                    .replace("-", "");
            Map<String, byte[]> normalizedFiles = new LinkedHashMap<>();
            Map<String, String> states = new LinkedHashMap<>();
            Map<String, String> expected = new LinkedHashMap<>();
            List<String> absent = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : managedFiles.entrySet()) {
                String path = normalizeRelative(entry.getKey());
                byte[] content = Objects.requireNonNull(entry.getValue(), "managed file bytes")
                        .clone();
                if (normalizedFiles.put(path, content) != null) {
                    throw failure("DUPLICATE_MANAGED_PATH", path);
                }
                Path target = root.resolve(path);
                if (Files.isSymbolicLink(target)) {
                    throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", path);
                }
                ManagedPathPolicy.StartState state = pathPolicy.classify(root, path);
                states.put(path, state.name());
                if (state == ManagedPathPolicy.StartState.UNTRACKED
                        || state == ManagedPathPolicy.StartState.IGNORED) {
                    throw failure("MANAGED_PATH_PREEXISTS", path);
                }
                if (state == ManagedPathPolicy.StartState.ABSENT) {
                    absent.add(path);
                }
                expected.put(path, hash(content));
            }
            String conflict = conflictingJournal(resolution, states.keySet());
            if (conflict != null) {
                throw failure("BASELINE_TRANSACTION_CONFLICT", conflict);
            }
            Journal journal = new Journal(transactionId, resolution.repositoryIdentity(), originalHead,
                    branchRef(root), Phase.PREPARED, originalIndex, originalIndex.rawIndexDigest(), "", "", "");
            journal = journal.withManagedPaths(states, expected);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.PREPARED);
            writeManagedFiles(root, normalizedFiles);
            journal = journal.withPhase(Phase.FILES_WRITTEN);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.FILES_WRITTEN);
            ManagedPathPolicy.TransactionOwnership ownership = new ManagedPathPolicy.TransactionOwnership(
                    resolution.repositoryIdentity(), transactionId, absent, expected, states);
            ManagedPathPolicy.Report afterWrite = pathPolicy.inspect(root, Optional.of(ownership));
            if (afterWrite.blocked()) {
                throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", blockingPaths(afterWrite));
            }

            ManagedPathPolicy.Report beforeCommit = pathPolicy.inspect(root, Optional.of(ownership));
            if (beforeCommit.blocked()) {
                throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", blockingPaths(beforeCommit));
            }
            String commit = createAdministrativeCommit(root, originalHead, normalizedFiles.keySet(), transactionId);
            journal = journal.withPhase(Phase.COMMIT_CREATED)
                    .withCommit(commit);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.COMMIT_CREATED);
            ManagedPathPolicy.Report beforeRef = pathPolicy.inspect(root, Optional.of(ownership));
            if (beforeRef.blocked()) {
                throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", blockingPaths(beforeRef));
            }
            advanceRef(root, journal.refName(), commit, originalHead);
            journal = journal.withPhase(Phase.REF_ADVANCED);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.REF_ADVANCED);
            synchronizeRealIndex(root, resolution, journal, originalIndex, commit);
            journal = journal.withPhase(Phase.CONTROL_INDEX_SYNCHRONIZED);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.CONTROL_INDEX_SYNCHRONIZED);
            journal = journal.withPhase(Phase.LOCAL_STATE_INITIALIZED);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.LOCAL_STATE_INITIALIZED);
            journal = journal.withPhase(Phase.PROVIDERS_REFRESHED);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.PROVIDERS_REFRESHED);
            journal = journal.withPhase(Phase.COMPLETE);
            writeJournal(resolution, journal);
            phaseHook.after(Phase.COMPLETE);
            return new Result(transactionId, journal.phase(), originalHead, commit,
                    resolution.administrativeRoot(), List.of());
        } catch (BaselineFailure failure) {
            throw failure;
        } catch (Exception failure) {
            if ("BASELINE_TRANSACTION_BUSY".equals(failure.getMessage())) {
                throw failure("BASELINE_TRANSACTION_BUSY",
                        "Another baseline transaction owns the repository lock",
                        failure);
            }
            throw failure("BASELINE_TRANSACTION_FAILED",
                    failure.getMessage() == null ? failure.getClass()
                                                   .getSimpleName() : failure.getMessage(),
                    failure);
        } finally {
            if (transactionLock != null) {
                transactionLock.close();
            }
        }
    }

    /**
     * Recovers a transaction after process loss without creating another commit.
     *
     * @param repositoryRoot repository worktree
     * @param transactionId  transaction identity
     * @return recovered result
     * @throws BaselineFailure when recovery cannot prove safe synchronization
     */
    public Result recover(Path repositoryRoot, String transactionId) throws BaselineFailure {
        ProjectLock transactionLock = null;
        try {
            Path root = normalize(repositoryRoot);
            AdministrativeStateLocator.Resolution resolution = locator.resolve(root);
            transactionLock = ProjectLock.acquire(resolution.administrativeRoot());
            Journal journal = readJournal(resolution, transactionId);
            if (journal.phase() == Phase.COMPLETE || journal.phase() == Phase.ROLLED_BACK) {
                return new Result(journal.transactionId(), journal.phase(), journal.originalHead(), journal.commit(),
                        resolution.administrativeRoot(), List.of());
            }
            if (journal.phase() == Phase.CONTROL_INDEX_RECOVERY_REQUIRED) {
                throw failure("CONTROL_INDEX_RECOVERY_REQUIRED", journal.realIndexSyncFailure());
            }
            if (!resolution.repositoryIdentity()
                    .equals(journal.repositoryIdentity())) {
                throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", "journal repository identity does not match");
            }
            if (journal.phase()
                    .ordinal() < Phase.REF_ADVANCED.ordinal()) {
                Journal rolledBack = rollbackPrepared(root, resolution, journal);
                return new Result(rolledBack.transactionId(), rolledBack.phase(), rolledBack.originalHead(),
                        rolledBack.commit(), resolution.administrativeRoot(), List.of("ROLLED_BACK"));
            }
            ManagedPathPolicy.Report ownershipState = pathPolicy.inspect(root,
                    Optional.of(journal.ownership()));
            if (ownershipState.blocked()) {
                throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", blockingPaths(ownershipState));
            }
            String actualHead = git(root, "rev-parse", "--verify", "HEAD");
            if (!actualHead.equals(journal.commit())) {
                throw failure("CONTROL_REF_UNEXPECTED", "HEAD does not match the journaled administrative commit");
            }
            if (synchronizedState(root, journal.commit())) {
                Journal complete = completeJournal(journal, resolution);
                return new Result(complete.transactionId(), complete.phase(), complete.originalHead(),
                        complete.commit(), resolution.administrativeRoot(), List.of("RECOVERED"));
            }
            SemanticIndexFingerprint.Fingerprint current = SemanticIndexFingerprint.capture(root);
            SemanticIndexFingerprint.Comparison comparison = SemanticIndexFingerprint.compare(
                    journal.originalIndex(), current);
            if (comparison == SemanticIndexFingerprint.Comparison.SEMANTIC_STATE_CHANGED
                    || comparison == SemanticIndexFingerprint.Comparison.INDEX_EXTENSION_UNSUPPORTED) {
                Journal failed = journal.withPhase(Phase.CONTROL_INDEX_RECOVERY_REQUIRED)
                        .withIndexFailure(comparison.name());
                writeJournal(resolution, failed);
                throw failure("CONTROL_INDEX_RECOVERY_REQUIRED", comparison.name());
            }
            synchronizeRealIndex(root, resolution, journal, journal.originalIndex(), journal.commit());
            Journal complete = completeJournal(journal, resolution);
            return new Result(complete.transactionId(), complete.phase(), complete.originalHead(), complete.commit(),
                    resolution.administrativeRoot(), List.of("RECOVERED"));
        } catch (BaselineFailure failure) {
            throw failure;
        } catch (Exception failure) {
            if ("BASELINE_TRANSACTION_BUSY".equals(failure.getMessage())) {
                throw failure("BASELINE_TRANSACTION_BUSY",
                        "Another baseline transaction owns the repository lock",
                        failure);
            }
            throw failure("BASELINE_RECOVERY_FAILED", failure.getMessage(), failure);
        } finally {
            if (transactionLock != null) {
                transactionLock.close();
            }
        }
    }

    /**
     * Loads a durable transaction journal.
     *
     * @param repositoryRoot repository worktree
     * @param transactionId  transaction identity
     * @return durable journal projection
     * @throws BaselineFailure when the journal is absent or malformed
     */
    public Journal journal(Path repositoryRoot, String transactionId) throws BaselineFailure {
        try {
            return readJournal(locator.resolve(normalize(repositoryRoot)), transactionId);
        } catch (BaselineFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("BASELINE_JOURNAL_UNAVAILABLE", failure.getMessage(), failure);
        }
    }

    private Journal rollbackPrepared(Path root, AdministrativeStateLocator.Resolution resolution,
            Journal journal) throws IOException, BaselineFailure {
        for (Map.Entry<String, String> entry : journal.managedPathStates()
                .entrySet()) {
            String path = entry.getKey();
            ManagedPathPolicy.StartState state;
            try {
                state = ManagedPathPolicy.StartState.valueOf(entry.getValue());
            } catch (IllegalArgumentException invalid) {
                throw failure("BASELINE_JOURNAL_INVALID", "unknown managed path state for " + path);
            }
            Path target = root.resolve(path)
                    .normalize();
            if (Files.isSymbolicLink(target)) {
                throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", path);
            }
            if (state == ManagedPathPolicy.StartState.ABSENT) {
                if (Files.exists(target) && differsDigest(target,
                        journal.expectedManagedDigests()
                                .get(path))) {
                    throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", path);
                }
                Files.deleteIfExists(target);
            } else if (state == ManagedPathPolicy.StartState.TRACKED) {
                if (!Files.isRegularFile(target)
                        || differsDigest(target,
                        journal.expectedManagedDigests()
                                .get(path))) {
                    throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", path);
                }
                if ("UNBORN".equals(journal.originalHead())) {
                    throw failure("BASELINE_JOURNAL_INVALID", "tracked path in unborn transaction");
                }
                git(root, "restore", "--source", journal.originalHead(), "--staged", "--worktree", "--", path);
            }
        }
        if (!git(root, "status", "--porcelain").isBlank()) {
            throw failure("CONTROL_CHECKOUT_NOT_CLEAN", "rollback left unrelated control content");
        }
        Journal rolledBack = journal.withPhase(Phase.ROLLED_BACK);
        writeJournal(resolution, rolledBack);
        return rolledBack;
    }

    private Journal completeJournal(Journal journal, AdministrativeStateLocator.Resolution resolution)
            throws IOException {
        Journal complete = journal;
        if (complete.phase()
                .ordinal() < Phase.CONTROL_INDEX_SYNCHRONIZED.ordinal()) {
            complete = complete.withPhase(Phase.CONTROL_INDEX_SYNCHRONIZED);
            writeJournal(resolution, complete);
        }
        if (complete.phase()
                .ordinal() < Phase.LOCAL_STATE_INITIALIZED.ordinal()) {
            complete = complete.withPhase(Phase.LOCAL_STATE_INITIALIZED);
            writeJournal(resolution, complete);
        }
        if (complete.phase()
                .ordinal() < Phase.PROVIDERS_REFRESHED.ordinal()) {
            complete = complete.withPhase(Phase.PROVIDERS_REFRESHED);
            writeJournal(resolution, complete);
        }
        if (complete.phase() != Phase.COMPLETE) {
            complete = complete.withPhase(Phase.COMPLETE);
            writeJournal(resolution, complete);
        }
        return complete;
    }

    private boolean synchronizedState(Path root, String commit) throws IOException {
        String expectedTree = git(root, "rev-parse", commit + "^{tree}");
        SemanticIndexFingerprint.Fingerprint current = SemanticIndexFingerprint.capture(root);
        return expectedTree.equals(current.indexTreeId()) && git(root, "status", "--porcelain").isBlank();
    }

    private void synchronizeRealIndex(Path root, AdministrativeStateLocator.Resolution resolution,
            Journal journal, SemanticIndexFingerprint.Fingerprint original,
            String commit) throws IOException, BaselineFailure {
        if (!resolution.repositoryIdentity()
                .equals(journal.repositoryIdentity())) {
            throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", "journal repository identity does not match");
        }
        ManagedPathPolicy.Report ownershipState = pathPolicy.inspect(root,
                Optional.of(journal.ownership()));
        if (ownershipState.blocked()) {
            throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", blockingPaths(ownershipState));
        }
        SemanticIndexFingerprint.Fingerprint current = SemanticIndexFingerprint.capture(root);
        SemanticIndexFingerprint.Comparison comparison = SemanticIndexFingerprint.compare(original, current);
        if (comparison == SemanticIndexFingerprint.Comparison.SEMANTIC_STATE_CHANGED
                || comparison == SemanticIndexFingerprint.Comparison.INDEX_EXTENSION_UNSUPPORTED) {
            Journal failed = journal.withPhase(Phase.CONTROL_INDEX_RECOVERY_REQUIRED)
                    .withIndexFailure(comparison.name());
            writeJournal(resolution, failed);
            throw failure("CONTROL_INDEX_RECOVERY_REQUIRED", comparison.name());
        }
        git(root, "reset", "--mixed", commit);
        SemanticIndexFingerprint.Fingerprint synchronizedIndex = SemanticIndexFingerprint.capture(root);
        String expectedTree = git(root, "rev-parse", commit + "^{tree}");
        if (!expectedTree.equals(synchronizedIndex.indexTreeId())) {
            Journal failed = journal.withPhase(Phase.CONTROL_INDEX_RECOVERY_REQUIRED)
                    .withIndexFailure("INDEX_TREE_MISMATCH");
            writeJournal(resolution, failed);
            throw failure("CONTROL_INDEX_RECOVERY_REQUIRED", "INDEX_TREE_MISMATCH");
        }
        for (String managed : pathPolicy.managedPaths()) {
            String expectedBlob = git(root, "ls-tree", "-r", "--full-tree", commit, "--", managed);
            if (!expectedBlob.isBlank() && Files.isRegularFile(root.resolve(managed))) {
                String actualBlob = git(root, "hash-object", "--", managed);
                String blob = expectedBlob.split("\\s+")[2];
                if (!blob.equals(actualBlob)) {
                    throw failure("MANAGED_WORKTREE_BLOB_MISMATCH", managed);
                }
            }
        }
        if (!git(root, "status", "--porcelain").isBlank()) {
            throw failure("CONTROL_CHECKOUT_NOT_CLEAN", "managed baseline left Git status dirty");
        }
    }

    private String conflictingJournal(AdministrativeStateLocator.Resolution resolution,
            java.util.Set<String> paths) throws IOException, BaselineFailure {
        if (!Files.isDirectory(resolution.baselineRoot())) {
            return null;
        }
        try (var journals = Files.list(resolution.baselineRoot())) {
            for (Path path : journals.filter(candidate -> candidate.getFileName()
                            .toString()
                            .endsWith(".json"))
                    .toList()) {
                Journal journal = readJournal(resolution,
                        path.getFileName()
                                .toString()
                                .replaceFirst("\\.json$", ""));
                if (journal.phase() == Phase.COMPLETE || journal.phase() == Phase.ROLLED_BACK) {
                    continue;
                }
                for (String managed : journal.managedPathStates()
                        .keySet()) {
                    if (paths.contains(managed)) {
                        return managed;
                    }
                }
            }
        }
        return null;
    }

    private void writeJournal(AdministrativeStateLocator.Resolution resolution, Journal journal) throws IOException {
        Files.createDirectories(resolution.baselineRoot());
        Path target = resolution.baselineRoot()
                .resolve(journal.transactionId() + ".json");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, ProviderJson.write(journal.toMap()) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private Journal readJournal(AdministrativeStateLocator.Resolution resolution, String id)
            throws IOException, BaselineFailure {
        Path path = resolution.baselineRoot()
                .resolve(id + ".json");
        if (!Files.isRegularFile(path)) {
            throw failure("BASELINE_JOURNAL_NOT_FOUND", id);
        }
        Object parsed = ProviderJson.parse(Files.readString(path));
        if (!(parsed instanceof Map<?, ?> map)) {
            throw failure("BASELINE_JOURNAL_INVALID", id);
        }
        return Journal.fromMap((Map<String, Object>) map);
    }

    /**
     * Baseline transaction phases.
     */
    public enum Phase {
        /**
         * Durable preparation marker.
         */
        PREPARED,
        /**
         * Managed files have been written and verified.
         */
        FILES_WRITTEN,
        /**
         * Administrative commit object has been created.
         */
        COMMIT_CREATED,
        /**
         * Control ref has advanced to the administrative commit.
         */
        REF_ADVANCED,
        /**
         * Real index has been synchronized to the commit.
         */
        CONTROL_INDEX_SYNCHRONIZED,
        /**
         * Local administrative state has been initialized.
         */
        LOCAL_STATE_INITIALIZED,
        /**
         * Provider refresh has completed.
         */
        PROVIDERS_REFRESHED,
        /**
         * Transaction is complete and recoverable state is stable.
         */
        COMPLETE,
        /**
         * Real-index recovery is required before authority can proceed.
         */
        CONTROL_INDEX_RECOVERY_REQUIRED,
        /**
         * Pre-ref transaction changes were safely rolled back.
         */
        ROLLED_BACK
    }

    /**
     * Observer invoked after each durable transaction phase is written.
     */
    @FunctionalInterface
    public interface PhaseHook {

        /**
         * Observes a durable phase.
         *
         * @param phase phase just persisted
         * @throws IOException when the caller wants to simulate process loss
         */
        void after(Phase phase) throws IOException;
    }

    private record ProjectLock(FileChannel channel, FileLock lock) implements AutoCloseable {

        private static ProjectLock acquire(Path administrativeRoot) throws IOException {
            Files.createDirectories(administrativeRoot);
            Path lockPath = administrativeRoot.resolve("baseline.transaction.lock");
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new IOException("BASELINE_TRANSACTION_BUSY");
                }
                return new ProjectLock(channel, lock);
            } catch (OverlappingFileLockException busy) {
                channel.close();
                throw new IOException("BASELINE_TRANSACTION_BUSY", busy);
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // The process is already leaving the transaction; the OS closes it.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The lock file is durable administrative state.
            }
        }
    }

    /**
     * Durable transaction result.
     *
     * @param transactionId      transaction identity
     * @param phase              terminal or observed phase
     * @param originalHead       original control HEAD or {@code UNBORN}
     * @param commit             administrative commit identity
     * @param administrativeRoot external administrative state root
     * @param diagnostics        safe diagnostic codes
     */
    public record Result(String transactionId, Phase phase, String originalHead, String commit,
                         Path administrativeRoot, List<String> diagnostics) {

        /**
         * Copies diagnostics.
         */
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * Durable baseline journal projection.
     *
     * @param transactionId           transaction identity
     * @param repositoryIdentity      canonical repository identity
     * @param originalHead            original control HEAD or {@code UNBORN}
     * @param refName                 control ref being advanced
     * @param phase                   durable transaction phase
     * @param originalIndex           original semantic index fingerprint
     * @param originalRealIndexDigest original physical index digest
     * @param expectedRealIndexDigest expected physical index digest
     * @param realIndexSyncStatus     real-index synchronization status
     * @param realIndexSyncFailure    synchronization failure diagnostic
     * @param commit                  administrative commit identity
     * @param managedPathStates       managed path states at transaction start
     * @param expectedManagedDigests  expected transaction content digests
     */
    public record Journal(String transactionId, String repositoryIdentity, String originalHead, String refName,
                          Phase phase, SemanticIndexFingerprint.Fingerprint originalIndex,
                          String originalRealIndexDigest, String expectedRealIndexDigest,
                          String realIndexSyncStatus, String realIndexSyncFailure, String commit,
                          Map<String, String> managedPathStates, Map<String, String> expectedManagedDigests) {

        /**
         * Compatibility constructor for a journal that has no commit field.
         *
         * @param transactionId           transaction identity
         * @param repositoryIdentity      canonical repository identity
         * @param originalHead            original control HEAD
         * @param refName                 control ref
         * @param phase                   transaction phase
         * @param originalIndex           original index fingerprint
         * @param originalRealIndexDigest original physical index digest
         * @param expectedRealIndexDigest expected physical index digest
         * @param realIndexSyncStatus     synchronization status
         * @param realIndexSyncFailure    synchronization failure
         */
        public Journal(String transactionId, String repositoryIdentity, String originalHead, String refName,
                Phase phase, SemanticIndexFingerprint.Fingerprint originalIndex,
                String originalRealIndexDigest, String expectedRealIndexDigest,
                String realIndexSyncStatus, String realIndexSyncFailure) {
            this(transactionId, repositoryIdentity, originalHead, refName, phase, originalIndex,
                    originalRealIndexDigest, expectedRealIndexDigest, realIndexSyncStatus, realIndexSyncFailure,
                    "", Map.of(), Map.of());
        }

        /**
         * Compatibility constructor for journals that predate managed-path provenance.
         *
         * @param transactionId           transaction identity
         * @param repositoryIdentity      canonical repository identity
         * @param originalHead            original control HEAD
         * @param refName                 control ref
         * @param phase                   transaction phase
         * @param originalIndex           original index fingerprint
         * @param originalRealIndexDigest original physical index digest
         * @param expectedRealIndexDigest expected physical index digest
         * @param realIndexSyncStatus     sync status
         * @param realIndexSyncFailure    sync failure
         * @param commit                  commit identity
         */
        public Journal(String transactionId, String repositoryIdentity, String originalHead, String refName,
                Phase phase, SemanticIndexFingerprint.Fingerprint originalIndex,
                String originalRealIndexDigest, String expectedRealIndexDigest,
                String realIndexSyncStatus, String realIndexSyncFailure, String commit) {
            this(transactionId, repositoryIdentity, originalHead, refName, phase, originalIndex,
                    originalRealIndexDigest, expectedRealIndexDigest, realIndexSyncStatus, realIndexSyncFailure,
                    commit, Map.of(), Map.of());
        }

        /**
         * Copies required values.
         */
        public Journal {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(repositoryIdentity, "repositoryIdentity");
            Objects.requireNonNull(originalHead, "originalHead");
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(originalIndex, "originalIndex");
            Objects.requireNonNull(originalRealIndexDigest, "originalRealIndexDigest");
            Objects.requireNonNull(expectedRealIndexDigest, "expectedRealIndexDigest");
            Objects.requireNonNull(realIndexSyncStatus, "realIndexSyncStatus");
            Objects.requireNonNull(realIndexSyncFailure, "realIndexSyncFailure");
            Objects.requireNonNull(commit, "commit");
            managedPathStates = Map.copyOf(Objects.requireNonNull(managedPathStates, "managedPathStates"));
            expectedManagedDigests = Map.copyOf(Objects.requireNonNull(expectedManagedDigests,
                    "expectedManagedDigests"));
        }

        @SuppressWarnings("unchecked")
        private static Journal fromMap(Map<String, Object> map) throws BaselineFailure {
            try {
                String id = String.valueOf(map.get("transactionId"));
                Map<String, String> states = stringMap(map.get("managedPathStates"));
                Map<String, String> expected = stringMap(map.get("expectedManagedDigests"));
                return new Journal(id,
                        String.valueOf(map.get("repositoryIdentity")),
                        String.valueOf(map.get("originalHead")),
                        String.valueOf(map.get("refName")),
                        Phase.valueOf(String.valueOf(map.get("phase"))),
                        SemanticIndexFingerprint.Fingerprint.fromMap((Map<String, Object>) map.get("originalIndex")),
                        String.valueOf(map.get("originalRealIndexDigest")),
                        String.valueOf(map.get("expectedRealIndexDigest")),
                        String.valueOf(map.get("realIndexSyncStatus")),
                        String.valueOf(map.get("realIndexSyncFailure")),
                        String.valueOf(map.get("commit")),
                        states,
                        expected);
            } catch (Exception failure) {
                throw failure("BASELINE_JOURNAL_INVALID", failure.getMessage());
            }
        }

        private static Map<String, String> stringMap(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }

        private Journal withPhase(Phase next) {
            return new Journal(transactionId,
                    repositoryIdentity,
                    originalHead,
                    refName,
                    next,
                    originalIndex,
                    originalRealIndexDigest,
                    expectedRealIndexDigest,
                    realIndexSyncStatus,
                    realIndexSyncFailure,
                    commit,
                    managedPathStates,
                    expectedManagedDigests);
        }

        private Journal withCommit(String value) {
            return new Journal(transactionId,
                    repositoryIdentity,
                    originalHead,
                    refName,
                    phase,
                    originalIndex,
                    originalRealIndexDigest,
                    expectedRealIndexDigest,
                    realIndexSyncStatus,
                    realIndexSyncFailure,
                    value,
                    managedPathStates,
                    expectedManagedDigests);
        }

        private Journal withIndexFailure(String value) {
            return new Journal(transactionId,
                    repositoryIdentity,
                    originalHead,
                    refName,
                    phase,
                    originalIndex,
                    originalRealIndexDigest,
                    expectedRealIndexDigest,
                    "RECOVERY_REQUIRED",
                    value,
                    commit,
                    managedPathStates,
                    expectedManagedDigests);
        }

        private Journal withManagedPaths(Map<String, String> states, Map<String, String> expected) {
            return new Journal(transactionId,
                    repositoryIdentity,
                    originalHead,
                    refName,
                    phase,
                    originalIndex,
                    originalRealIndexDigest,
                    expectedRealIndexDigest,
                    realIndexSyncStatus,
                    realIndexSyncFailure,
                    commit,
                    states,
                    expected);
        }

        private ManagedPathPolicy.TransactionOwnership ownership() {
            List<String> absent = managedPathStates.entrySet()
                    .stream()
                    .filter(entry -> ManagedPathPolicy.StartState.ABSENT.name()
                            .equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            return new ManagedPathPolicy.TransactionOwnership(repositoryIdentity, transactionId, absent,
                    expectedManagedDigests, managedPathStates);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("transactionId", transactionId);
            values.put("repositoryIdentity", repositoryIdentity);
            values.put("originalHead", originalHead);
            values.put("refName", refName);
            values.put("phase", phase.name());
            values.put("originalRealIndexDigest", originalRealIndexDigest);
            values.put("expectedRealIndexDigest", expectedRealIndexDigest);
            values.put("realIndexSyncStatus", realIndexSyncStatus);
            values.put("realIndexSyncFailure", realIndexSyncFailure);
            values.put("commit", commit);
            values.put("originalIndex", originalIndex.toMap());
            values.put("managedPathStates", managedPathStates);
            values.put("expectedManagedDigests", expectedManagedDigests);
            values.put("createdAt",
                    Instant.now()
                            .toString());
            return Map.copyOf(values);
        }

    }

    /**
     * Stable baseline failure with an actionable code.
     */
    public static final class BaselineFailure extends Exception {

        @java.io.Serial
        private static final long serialVersionUID = 1L;
        /**
         * Stable machine-readable failure code.
         */
        private final String code;

        /**
         * Creates a coded failure.
         *
         * @param code    stable code
         * @param message diagnostic message
         */
        public BaselineFailure(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Creates a coded failure with a cause.
         *
         * @param code    stable code
         * @param message diagnostic message
         * @param cause   underlying cause
         */
        public BaselineFailure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Returns the stable failure code.
         *
         * @return stable failure code
         */
        public String code() {
            return code;
        }
    }
}
