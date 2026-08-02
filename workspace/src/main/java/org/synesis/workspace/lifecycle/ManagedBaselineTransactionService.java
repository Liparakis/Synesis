package org.synesis.workspace.lifecycle;

import java.io.IOException;
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

    /** Creates a service rooted at the host's Synesis state directory. */
    public ManagedBaselineTransactionService() {
        this(new AdministrativeStateLocator(), new ManagedPathPolicy());
    }

    /** Creates a service with explicit local-state and path-policy dependencies.
     * @param locator administrative state locator
     * @param pathPolicy managed path policy
     */
    public ManagedBaselineTransactionService(AdministrativeStateLocator locator, ManagedPathPolicy pathPolicy) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
    }

    /**
     * Executes a fresh managed-baseline transaction.
     *
     * @param repositoryRoot repository worktree
     * @param managedFiles managed repository-relative files and their expected bytes
     * @return durable transaction result
     * @throws BaselineFailure when safety validation or synchronization fails
     */
    public Result prepare(Path repositoryRoot, Map<String, byte[]> managedFiles) throws BaselineFailure {
        Path root = normalize(repositoryRoot);
        if (managedFiles == null || managedFiles.isEmpty()) {
            throw failure("MANAGED_FILES_REQUIRED", "At least one managed file is required");
        }
        try {
            AdministrativeStateLocator.Resolution resolution = locator.resolve(root);
            ManagedPathPolicy.Report initial = pathPolicy.inspect(root);
            if (initial.blocked()) {
                throw failure("CONTROL_CHECKOUT_DIRTY", blockingPaths(initial));
            }
            SemanticIndexFingerprint.Fingerprint originalIndex = SemanticIndexFingerprint.capture(root);
            String originalHead = gitOptional(root, "rev-parse", "--verify", "HEAD");
            if (originalHead.isBlank()) originalHead = "UNBORN";
            String transactionId = "txn_" + UUID.randomUUID().toString().replace("-", "");
            Map<String, String> expected = new LinkedHashMap<>();
            List<String> absent = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : managedFiles.entrySet()) {
                String path = normalizeRelative(entry.getKey());
                Path target = root.resolve(path);
                if (Files.exists(target) || tracked(root, path)) {
                    throw failure("MANAGED_PATH_PREEXISTS", path);
                }
                absent.add(path);
                expected.put(path, hash(entry.getValue()));
            }
            Journal journal = new Journal(transactionId, resolution.repositoryIdentity(), originalHead,
                    branchRef(root), Phase.PREPARED, originalIndex, originalIndex.rawIndexDigest(), "", "", "");
            writeJournal(resolution, journal);
            writeManagedFiles(root, managedFiles);
            journal = journal.withPhase(Phase.FILES_WRITTEN);
            writeJournal(resolution, journal);
            ManagedPathPolicy.TransactionOwnership ownership = new ManagedPathPolicy.TransactionOwnership(
                    resolution.repositoryIdentity(), transactionId, absent, expected);
            ManagedPathPolicy.Report afterWrite = pathPolicy.inspect(root, Optional.of(ownership));
            if (afterWrite.blocked()) throw failure("MANAGED_PATH_PROVENANCE_MISMATCH", blockingPaths(afterWrite));

            String commit = createAdministrativeCommit(root, originalHead, managedFiles.keySet(), transactionId);
            journal = journal.withPhase(Phase.COMMIT_CREATED).withCommit(commit);
            writeJournal(resolution, journal);
            advanceRef(root, journal.refName(), commit, originalHead);
            journal = journal.withPhase(Phase.REF_ADVANCED);
            writeJournal(resolution, journal);
            synchronizeRealIndex(root, resolution, journal, originalIndex, commit);
            journal = journal.withPhase(Phase.CONTROL_INDEX_SYNCHRONIZED);
            writeJournal(resolution, journal);
            journal = journal.withPhase(Phase.LOCAL_STATE_INITIALIZED);
            writeJournal(resolution, journal);
            journal = journal.withPhase(Phase.PROVIDERS_REFRESHED).withPhase(Phase.COMPLETE);
            writeJournal(resolution, journal);
            return new Result(transactionId, journal.phase(), originalHead, commit,
                    resolution.administrativeRoot(), List.of());
        } catch (BaselineFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("BASELINE_TRANSACTION_FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), failure);
        }
    }

    /**
     * Recovers a transaction after process loss without creating another commit.
     *
     * @param repositoryRoot repository worktree
     * @param transactionId transaction identity
     * @return recovered result
     * @throws BaselineFailure when recovery cannot prove safe synchronization
     */
    public Result recover(Path repositoryRoot, String transactionId) throws BaselineFailure {
        try {
            Path root = normalize(repositoryRoot);
            AdministrativeStateLocator.Resolution resolution = locator.resolve(root);
            Journal journal = readJournal(resolution, transactionId);
            if (journal.phase() == Phase.COMPLETE) {
                return new Result(journal.transactionId(), journal.phase(), journal.originalHead(), journal.commit(),
                        resolution.administrativeRoot(), List.of());
            }
            if (journal.phase().ordinal() < Phase.REF_ADVANCED.ordinal()) {
                return new Result(journal.transactionId(), journal.phase(), journal.originalHead(), journal.commit(),
                        resolution.administrativeRoot(), List.of("NOT_ADVANCED"));
            }
            String actualHead = git(root, "rev-parse", "--verify", "HEAD");
            if (!actualHead.equals(journal.commit())) {
                throw failure("CONTROL_REF_UNEXPECTED", "HEAD does not match the journaled administrative commit");
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
            Journal complete = journal.withPhase(Phase.CONTROL_INDEX_SYNCHRONIZED)
                    .withPhase(Phase.LOCAL_STATE_INITIALIZED)
                    .withPhase(Phase.PROVIDERS_REFRESHED)
                    .withPhase(Phase.COMPLETE);
            writeJournal(resolution, complete);
            return new Result(complete.transactionId(), complete.phase(), complete.originalHead(), complete.commit(),
                    resolution.administrativeRoot(), List.of("RECOVERED"));
        } catch (BaselineFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("BASELINE_RECOVERY_FAILED", failure.getMessage(), failure);
        }
    }

    /**
     * Loads a durable transaction journal.
     *
     * @param repositoryRoot repository worktree
     * @param transactionId transaction identity
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

    private void synchronizeRealIndex(Path root, AdministrativeStateLocator.Resolution resolution,
                                      Journal journal, SemanticIndexFingerprint.Fingerprint original,
                                      String commit) throws IOException, BaselineFailure {
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
                if (!blob.equals(actualBlob)) throw failure("MANAGED_WORKTREE_BLOB_MISMATCH", managed);
            }
        }
        if (!git(root, "status", "--porcelain").isBlank()) {
            throw failure("CONTROL_CHECKOUT_NOT_CLEAN", "managed baseline left Git status dirty");
        }
    }

    private static String createAdministrativeCommit(Path root, String parent, java.util.Set<String> paths,
                                                     String transactionId) throws IOException {
        Path index = Files.createTempFile("synesis-baseline-", ".index");
        Files.deleteIfExists(index);
        try {
            if (parent.equals("UNBORN")) run(root, index, "read-tree", "--empty");
            else run(root, index, "read-tree", parent);
            List<String> add = new ArrayList<>();
            add.add("add"); add.add("-f"); add.add("-A"); add.add("--"); add.addAll(paths);
            run(root, index, add.toArray(String[]::new));
            String tree = run(root, index, "write-tree");
            List<String> commit = new ArrayList<>();
            commit.add("commit-tree"); commit.add(tree);
            if (!parent.equals("UNBORN")) { commit.add("-p"); commit.add(parent); }
            commit.add("-m"); commit.add("Synesis managed baseline " + transactionId);
            return run(root, index, commit.toArray(String[]::new));
        } finally {
            Files.deleteIfExists(index);
        }
    }

    private static void advanceRef(Path root, String ref, String commit, String expected) throws IOException {
        if (expected.equals("UNBORN")) run(root, null, "update-ref", ref, commit);
        else run(root, null, "update-ref", ref, commit, expected);
    }

    private static void writeManagedFiles(Path root, Map<String, byte[]> files) throws IOException, BaselineFailure {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = root.resolve(normalizeRelative(entry.getKey()));
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".synesis-tmp-" + UUID.randomUUID());
            Files.write(temporary, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    private static boolean tracked(Path root, String path) throws IOException {
        return !gitOptional(root, "ls-files", "--error-unmatch", "--", path).isBlank();
    }

    private static String branchRef(Path root) throws IOException {
        String ref = gitOptional(root, "symbolic-ref", "--quiet", "HEAD");
        return ref.isBlank() ? "HEAD" : ref;
    }

    private static String blockingPaths(ManagedPathPolicy.Report report) {
        return report.findings().stream().filter(ManagedPathPolicy.Finding::blocksTransaction)
                .map(ManagedPathPolicy.Finding::path).findFirst().orElse("unknown");
    }

    private static String normalizeRelative(String path) throws BaselineFailure {
        if (path == null || path.isBlank() || Path.of(path).isAbsolute()) throw failure("INVALID_MANAGED_PATH", path);
        Path normalized = Path.of(path).normalize();
        if (normalized.startsWith("..") || normalized.toString().equals(".")) throw failure("INVALID_MANAGED_PATH", path);
        return normalized.toString().replace('\\', '/');
    }

    private static String hash(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IOException("hash unavailable", failure); }
    }

    private static Path normalize(Path root) { return Objects.requireNonNull(root, "repositoryRoot").toAbsolutePath().normalize(); }

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
        List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        if (index != null) builder.environment().put("GIT_INDEX_FILE", index.toString());
        builder.environment().put("GIT_AUTHOR_NAME", "Synesis");
        builder.environment().put("GIT_AUTHOR_EMAIL", "synesis@localhost");
        builder.environment().put("GIT_COMMITTER_NAME", "Synesis");
        builder.environment().put("GIT_COMMITTER_EMAIL", "synesis@localhost");
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (required && exit != 0) throw new IOException("git " + args[0] + " failed: " + output);
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("git transaction interrupted", interrupted);
        }
    }

    private void writeJournal(AdministrativeStateLocator.Resolution resolution, Journal journal) throws IOException {
        Files.createDirectories(resolution.baselineRoot());
        Path target = resolution.baselineRoot().resolve(journal.transactionId() + ".json");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, ProviderJson.write(journal.toMap()) + System.lineSeparator(), StandardCharsets.UTF_8);
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    @SuppressWarnings("unchecked")
    private Journal readJournal(AdministrativeStateLocator.Resolution resolution, String id) throws IOException, BaselineFailure {
        Path path = resolution.baselineRoot().resolve(id + ".json");
        if (!Files.isRegularFile(path)) throw failure("BASELINE_JOURNAL_NOT_FOUND", id);
        Object parsed = ProviderJson.parse(Files.readString(path));
        if (!(parsed instanceof Map<?, ?> map)) throw failure("BASELINE_JOURNAL_INVALID", id);
        return Journal.fromMap((Map<String, Object>) map);
    }

    private static BaselineFailure failure(String code, String message) { return new BaselineFailure(code, message); }
    private static BaselineFailure failure(String code, String message, Throwable cause) { return new BaselineFailure(code, message, cause); }

    /** Baseline transaction phases. */
    public enum Phase {
        PREPARED, FILES_WRITTEN, COMMIT_CREATED, REF_ADVANCED, CONTROL_INDEX_SYNCHRONIZED,
        LOCAL_STATE_INITIALIZED, PROVIDERS_REFRESHED, COMPLETE, CONTROL_INDEX_RECOVERY_REQUIRED
    }

    /** Durable transaction result. */
    public record Result(String transactionId, Phase phase, String originalHead, String commit,
                         Path administrativeRoot, List<String> diagnostics) {
        /** Copies diagnostics. */
        public Result { diagnostics = List.copyOf(diagnostics); }
    }

    /** Durable baseline journal projection. */
    public record Journal(String transactionId, String repositoryIdentity, String originalHead, String refName,
                          Phase phase, SemanticIndexFingerprint.Fingerprint originalIndex,
                          String originalRealIndexDigest, String expectedRealIndexDigest,
                          String realIndexSyncStatus, String realIndexSyncFailure, String commit) {
        /** Compatibility constructor for an empty commit field. */
        public Journal(String transactionId, String repositoryIdentity, String originalHead, String refName,
                       Phase phase, SemanticIndexFingerprint.Fingerprint originalIndex,
                       String originalRealIndexDigest, String expectedRealIndexDigest,
                       String realIndexSyncStatus, String realIndexSyncFailure) {
            this(transactionId, repositoryIdentity, originalHead, refName, phase, originalIndex,
                    originalRealIndexDigest, expectedRealIndexDigest, realIndexSyncStatus, realIndexSyncFailure, "");
        }

        /** Copies required values. */
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
        }

        private Journal withPhase(Phase next) { return new Journal(transactionId, repositoryIdentity, originalHead, refName, next, originalIndex, originalRealIndexDigest, expectedRealIndexDigest, realIndexSyncStatus, realIndexSyncFailure, commit); }
        private Journal withCommit(String value) { return new Journal(transactionId, repositoryIdentity, originalHead, refName, phase, originalIndex, originalRealIndexDigest, expectedRealIndexDigest, realIndexSyncStatus, realIndexSyncFailure, value); }
        private Journal withIndexFailure(String value) { return new Journal(transactionId, repositoryIdentity, originalHead, refName, phase, originalIndex, originalRealIndexDigest, expectedRealIndexDigest, "RECOVERY_REQUIRED", value, commit); }

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
            values.put("createdAt", Instant.now().toString());
            return Map.copyOf(values);
        }

        @SuppressWarnings("unchecked")
        private static Journal fromMap(Map<String, Object> map) throws BaselineFailure {
            try {
                String id = String.valueOf(map.get("transactionId"));
                return new Journal(id, String.valueOf(map.get("repositoryIdentity")), String.valueOf(map.get("originalHead")),
                        String.valueOf(map.get("refName")), Phase.valueOf(String.valueOf(map.get("phase"))),
                        SemanticIndexFingerprint.Fingerprint.fromMap((Map<String, Object>) map.get("originalIndex")),
                        String.valueOf(map.get("expectedRealIndexDigest")), String.valueOf(map.get("realIndexSyncStatus")),
                        String.valueOf(map.get("realIndexSyncFailure")), String.valueOf(map.get("commit")));
            } catch (Exception failure) { throw failure("BASELINE_JOURNAL_INVALID", failure.getMessage()); }
        }

    }

    /** Stable baseline failure with an actionable code. */
    public static final class BaselineFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;
        /** Creates a coded failure. @param code stable code @param message diagnostic */
        public BaselineFailure(String code, String message) { super(message); this.code = Objects.requireNonNull(code, "code"); }
        /** Creates a coded failure with cause. @param code stable code @param message diagnostic @param cause cause */
        public BaselineFailure(String code, String message, Throwable cause) { super(message, cause); this.code = Objects.requireNonNull(code, "code"); }
        /** @return stable failure code */
        public String code() { return code; }
    }
}
