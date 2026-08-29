package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Coordinates a project-identity reset without making either identity
 * authoritative until the durable reset journal proves the transition.
 *
 * <p>The journal is keyed by the canonical Git common-directory identity and
 * transaction ID, not by either project ID. This makes a reset discoverable
 * after the metadata namespace changes and prevents a new project ID from
 * hiding an interrupted old-identity transaction.</p>
 */
public final class ResetRecoveryService {

    private final AdministrativeStateLocator locator;
    private final PhaseHook phaseHook;

    /**
     * Creates a reset service using the host administrative state root.
     */
    @SuppressWarnings("unused")
    public ResetRecoveryService() {
        this(new AdministrativeStateLocator(), _ -> {
        });
    }

    /**
     * Creates a reset service with an explicit state locator.
     *
     * @param locator administrative state locator
     */
    public ResetRecoveryService(AdministrativeStateLocator locator) {
        this(locator, _ -> {
        });
    }

    /**
     * Creates a reset service with a deterministic phase observer.
     *
     * @param locator   administrative state locator
     * @param phaseHook phase observer
     */
    public ResetRecoveryService(AdministrativeStateLocator locator, PhaseHook phaseHook) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.phaseHook = Objects.requireNonNull(phaseHook, "phaseHook");
    }

    private static void fenceOldNamespace(Journal journal) throws IOException {
        if (!Files.exists(journal.oldNamespace())) {
            return;
        }
        Files.createDirectories(journal.oldNamespace());
        writeMarker(journal.oldNamespace()
                        .resolve("authority.state"),
                "FENCED\ntransaction=" + journal.transactionId() + "\nprojectId=" + journal.oldProjectId() + "\n");
    }

    private static void prepareNewIdentity(Journal journal) throws IOException {
        Files.createDirectories(journal.stagingNamespace());
        writeMarker(journal.stagingNamespace()
                        .resolve("pending.identity"),
                "projectId=" + journal.newProjectId() + "\ntransaction=" + journal.transactionId() + "\n");
    }

    private static void persistBaseline(Journal journal) throws IOException {
        if (!Files.isRegularFile(journal.stagingNamespace()
                .resolve("pending.identity"))) {
            throw new IOException("RESET_BASELINE_STAGING_MISSING");
        }
        writeMarker(journal.stagingNamespace()
                .resolve("baseline.reference"), journal.baselineReference() + "\n");
    }

    private static void transferNamespace(Journal journal) throws IOException {
        Files.createDirectories(journal.stagingNamespace()
                .getParent());
        if (Files.exists(journal.newNamespace())) {
            if (Files.isRegularFile(journal.newNamespace()
                    .resolve("authority.state"))) {
                throw new IOException("RESET_NAMESPACE_CONFLICT");
            }
            throw new IOException("RESET_NAMESPACE_CONFLICT");
        }
        if (Files.exists(journal.oldNamespace())) {
            move(journal.oldNamespace(),
                    journal.stagingNamespace()
                            .resolve("old-state"));
        }
        Path source = journal.stagingNamespace();
        Files.createDirectories(journal.newNamespace()
                .getParent());
        move(source, journal.newNamespace());
    }

    private static void activateNewNamespace(Journal journal) throws IOException {
        Path namespace = journal.newNamespace();
        if (!Files.isDirectory(namespace)
                || !Files.isRegularFile(namespace.resolve("baseline.reference"))) {
            throw new IOException("RESET_NAMESPACE_TRANSFER_MISSING");
        }
        writeMarker(namespace.resolve("authority.state"),
                "ACTIVE\ntransaction=" + journal.transactionId() + "\nprojectId=" + journal.newProjectId() + "\n");
        Files.deleteIfExists(namespace.resolve("pending.identity"));
    }

    private static void move(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("RESET_NAMESPACE_CONFLICT");
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void writeMarker(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @SuppressWarnings("unchecked")
    private static Journal readJournalFile(AdministrativeStateLocator.Resolution resolution, Path file)
            throws Exception {
        if (!file.toAbsolutePath()
                .normalize()
                .startsWith(resolution.resetRoot()) || !Files.isRegularFile(file)) {
            throw new IOException("RESET_JOURNAL_UNAVAILABLE");
        }
        Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IOException("RESET_JOURNAL_INVALID");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        return new Journal(string(map, "transactionId"), string(map, "repositoryIdentity"),
                string(map, "oldProjectId"), string(map, "newProjectId"), Path.of(string(map, "oldNamespace")),
                Path.of(string(map, "newNamespace")), Path.of(string(map, "stagingNamespace")),
                string(map, "baselineReference"), Phase.valueOf(string(map, "phase")), optionalFailure(map));
    }

    private static Result result(Journal journal, String reason) {
        return new Result(journal.transactionId(), journal.phase(), journal.phase() == Phase.COMPLETE, reason);
    }

    private static String baselineReference(String repositoryIdentity, String transactionId, String newProjectId) {
        return "baseline-" + hash(repositoryIdentity + "\n" + transactionId + "\n" + newProjectId);
    }

    private static void validateIdentity(String value, String name) throws ResetFailure {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw failure("RESET_IDENTITY_INVALID", name);
        }
    }

    private static String string(Map<String, Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IOException("RESET_JOURNAL_INVALID:" + key);
        }
        return text;
    }

    private static String optionalFailure(Map<String, Object> map) throws IOException {
        Object value = map.get("failure");
        if (!(value instanceof String text)) {
            throw new IOException("RESET_JOURNAL_INVALID:failure");
        }
        return text;
    }

    private static ResetFailure failure(String code, String message) {
        return new ResetFailure(code, message);
    }

    private static ResetFailure failure(String code, String message, Throwable cause) {
        return new ResetFailure(code, message, cause);
    }

    private static String diagnostic(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass()
                                              .getSimpleName() : failure.getMessage();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("reset identity hashing unavailable", failure);
        }
    }

    /**
     * Prepares a reset journal without granting authority or changing a
     * project namespace.
     *
     * @param repositoryRoot repository worktree
     * @param oldProjectId   old project identity
     * @param newProjectId   new project identity
     * @return durable prepared journal
     * @throws ResetFailure when identities, state, or conflicting journals are unsafe
     */
    public Journal prepare(Path repositoryRoot, String oldProjectId, String newProjectId) throws ResetFailure {
        validateIdentity(oldProjectId, "oldProjectId");
        validateIdentity(newProjectId, "newProjectId");
        if (oldProjectId.equals(newProjectId)) {
            throw failure("RESET_IDENTITY_UNCHANGED", "old and new project identities must differ");
        }
        try {
            AdministrativeStateLocator.Resolution resolution = locator.resolve(repositoryRoot);
            try (ResetLock lock = ResetLock.acquire(resolution.resetRoot())) {
                lock.assertHeld();
                List<Journal> active = activeJournals(resolution);
                if (!active.isEmpty()) {
                    throw failure("RESET_CONFLICTING_JOURNALS",
                            active.getFirst()
                                    .transactionId());
                }
                Files.createDirectories(resolution.resetRoot());
                String transactionId = "reset_" + UUID.randomUUID()
                        .toString()
                        .replace("-", "");
                Path namespaceRoot = resolution.resetRoot()
                        .resolve("namespaces")
                        .normalize();
                Path oldNamespace = namespaceRoot.resolve(oldProjectId)
                        .normalize();
                Path newNamespace = namespaceRoot.resolve(newProjectId)
                        .normalize();
                Path staging = resolution.resetRoot()
                        .resolve("transfers")
                        .resolve(transactionId)
                        .normalize();
                if (!oldNamespace.startsWith(namespaceRoot) || !newNamespace.startsWith(namespaceRoot)
                        || !staging.startsWith(resolution.resetRoot())) {
                    throw failure("RESET_NAMESPACE_INVALID", "namespace escapes reset root");
                }
                if (Files.exists(newNamespace)) {
                    throw failure("RESET_NAMESPACE_CONFLICT", "new namespace already exists");
                }
                Journal journal = new Journal(transactionId, resolution.repositoryIdentity(), oldProjectId,
                        newProjectId, oldNamespace, newNamespace, staging,
                        baselineReference(resolution.repositoryIdentity(), transactionId, newProjectId),
                        Phase.PREPARED, "");
                writeJournal(resolution, journal);
                return journal;
            }
        } catch (ResetFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("RESET_PREPARE_FAILED", diagnostic(failure), failure);
        }
    }

    /**
     * Starts and completes a new reset transaction.
     *
     * @param repositoryRoot repository worktree
     * @param oldProjectId   old project identity
     * @param newProjectId   new project identity
     * @return final reset result
     * @throws ResetFailure when preparation or a phase cannot be completed
     */
    public Result reset(Path repositoryRoot, String oldProjectId, String newProjectId) throws ResetFailure {
        Journal journal = prepare(repositoryRoot, oldProjectId, newProjectId);
        return resume(repositoryRoot, journal.transactionId());
    }

    /**
     * Resumes one prepared transaction idempotently after process loss.
     *
     * @param repositoryRoot repository worktree
     * @param transactionId  reset transaction identity
     * @return current or completed result
     * @throws ResetFailure when the journal or repository identity cannot be verified
     */
    public Result resume(Path repositoryRoot, String transactionId) throws ResetFailure {
        return advance(repositoryRoot, transactionId);
    }

    /**
     * Recovers one transaction using the same durable resume protocol.
     *
     * @param repositoryRoot repository worktree
     * @param transactionId  reset transaction identity
     * @return recovered result
     * @throws ResetFailure when recovery must fail closed
     */
    public Result recover(Path repositoryRoot, String transactionId) throws ResetFailure {
        return advance(repositoryRoot, transactionId);
    }

    /**
     * Lists reset journals for the repository's canonical Git common directory.
     *
     * @param repositoryRoot repository worktree
     * @return deterministic journal list
     * @throws ResetFailure when a journal is malformed or belongs to another repository
     */
    public List<Journal> discover(Path repositoryRoot) throws ResetFailure {
        try {
            return allJournals(locator.resolve(repositoryRoot));
        } catch (ResetFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("RESET_DISCOVERY_FAILED", diagnostic(failure), failure);
        }
    }

    private Result advance(Path repositoryRoot, String transactionId) throws ResetFailure {
        if (transactionId == null || !transactionId.matches("reset_[A-Za-z0-9]+")) {
            throw failure("RESET_TRANSACTION_INVALID", "invalid reset transaction ID");
        }
        try {
            AdministrativeStateLocator.Resolution resolution = locator.resolve(repositoryRoot);
            try (ResetLock lock = ResetLock.acquire(resolution.resetRoot())) {
                lock.assertHeld();
                Journal journal = readJournal(resolution, transactionId);
                if (!journal.repositoryIdentity()
                        .equals(resolution.repositoryIdentity())) {
                    throw failure("RESET_REPOSITORY_IDENTITY_MISMATCH", "journal common directory differs");
                }
                if (journal.phase() == Phase.COMPLETE || journal.phase() == Phase.ROLLED_BACK) {
                    return result(journal, "reset_already_complete");
                }
                List<Journal> active = activeJournals(resolution);
                if (active.stream()
                        .anyMatch(candidate -> !candidate.transactionId()
                                .equals(transactionId))) {
                    throw failure("RESET_CONFLICTING_JOURNALS", "another reset transaction is active");
                }
                journal = resumePhases(resolution, journal);
                return result(journal, journal.phase() == Phase.COMPLETE
                        ? "reset_complete" : "reset_recovery_required");
            }
        } catch (ResetFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("RESET_RECOVERY_FAILED", diagnostic(failure), failure);
        }
    }

    private Journal resumePhases(AdministrativeStateLocator.Resolution resolution, Journal journal)
            throws Exception {
        while (journal.phase() != Phase.COMPLETE && journal.phase() != Phase.ROLLED_BACK
                && journal.phase() != Phase.RECOVERY_REQUIRED) {
            switch (journal.phase()) {
                case PREPARED -> {
                    fenceOldNamespace(journal);
                    journal = persistPhase(resolution, journal, Phase.OLD_FENCED);
                }
                case OLD_FENCED -> {
                    prepareNewIdentity(journal);
                    journal = persistPhase(resolution, journal, Phase.NEW_ID_GENERATED);
                }
                case NEW_ID_GENERATED -> {
                    persistBaseline(journal);
                    journal = persistPhase(resolution, journal, Phase.BASELINE_COMMITTED);
                }
                case BASELINE_COMMITTED -> {
                    transferNamespace(journal);
                    journal = persistPhase(resolution, journal, Phase.NAMESPACE_TRANSFERRED);
                }
                case NAMESPACE_TRANSFERRED -> {
                    activateNewNamespace(journal);
                    journal = persistPhase(resolution, journal, Phase.ACTIVATED);
                }
                case ACTIVATED -> journal = persistPhase(resolution, journal, Phase.PROVIDERS_REFRESHED);
                case PROVIDERS_REFRESHED -> journal = persistPhase(resolution, journal, Phase.COMPLETE);
                default -> throw failure("RESET_PHASE_INVALID",
                        journal.phase()
                                .name());
            }
        }
        return journal;
    }

    private Journal persistPhase(AdministrativeStateLocator.Resolution resolution, Journal current,
            Phase next) throws Exception {
        Journal updated = new Journal(current.transactionId(), current.repositoryIdentity(), current.oldProjectId(),
                current.newProjectId(), current.oldNamespace(), current.newNamespace(), current.stagingNamespace(),
                current.baselineReference(), next, "");
        writeJournal(resolution, updated);
        phaseHook.after(next);
        return updated;
    }

    private List<Journal> allJournals(AdministrativeStateLocator.Resolution resolution) throws Exception {
        if (!Files.isDirectory(resolution.resetRoot())) {
            return List.of();
        }
        List<Journal> journals = new ArrayList<>();
        try (var files = Files.list(resolution.resetRoot())) {
            for (Path file : files.filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".json"))
                    .sorted()
                    .toList()) {
                Journal journal = readJournalFile(resolution, file);
                journals.add(journal);
            }
        }
        journals.sort(java.util.Comparator.comparing(Journal::transactionId));
        return List.copyOf(journals);
    }

    private List<Journal> activeJournals(AdministrativeStateLocator.Resolution resolution) throws Exception {
        return allJournals(resolution).stream()
                .filter(journal -> journal.phase() != Phase.COMPLETE && journal.phase() != Phase.ROLLED_BACK)
                .toList();
    }

    private Journal readJournal(AdministrativeStateLocator.Resolution resolution, String transactionId)
            throws ResetFailure {
        try {
            return readJournalFile(resolution,
                    resolution.resetRoot()
                            .resolve(transactionId + ".json"));
        } catch (ResetFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure("RESET_JOURNAL_UNAVAILABLE", diagnostic(failure), failure);
        }
    }

    private static Map<String, Object> journalMap(Journal journal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transactionId", journal.transactionId());
        map.put("repositoryIdentity", journal.repositoryIdentity());
        map.put("oldProjectId", journal.oldProjectId());
        map.put("newProjectId", journal.newProjectId());
        map.put("oldNamespace", journal.oldNamespace().toString());
        map.put("newNamespace", journal.newNamespace().toString());
        map.put("stagingNamespace", journal.stagingNamespace().toString());
        map.put("baselineReference", journal.baselineReference());
        map.put("phase", journal.phase().name());
        map.put("failure", journal.failure());
        return map;
    }

    private void writeJournal(AdministrativeStateLocator.Resolution resolution, Journal journal) throws IOException {
        Files.createDirectories(resolution.resetRoot());
        Map<String, Object> map = journalMap(journal);
        Path journalPath = resolution.resetRoot()
                .resolve(journal.transactionId() + ".json");
        Path temporary = journalPath.resolveSibling(journalPath.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, ProviderJson.write(map), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, journalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, journalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Durable phases of one identity reset.
     */
    public enum Phase {
        /**
         * The reset transaction exists but has not fenced the old identity.
         */
        PREPARED,
        /**
         * Old authority is fenced and remains non-authoritative.
         */
        OLD_FENCED,
        /**
         * The new identity has been recorded in the transaction staging area.
         */
        NEW_ID_GENERATED,
        /**
         * The new managed baseline reference has been durably recorded.
         */
        BASELINE_COMMITTED,
        /**
         * The old namespace has been transferred into the new namespace.
         */
        NAMESPACE_TRANSFERRED,
        /**
         * The new namespace is authoritative and the old one remains fenced.
         */
        ACTIVATED,
        /**
         * Provider refresh has been observed after activation.
         */
        PROVIDERS_REFRESHED,
        /**
         * The reset completed and is idempotently replayable.
         */
        COMPLETE,
        /**
         * Recovery cannot prove the namespace or identity transition safe.
         */
        RECOVERY_REQUIRED,
        /**
         * An explicit rollback completed before activation.
         */
        ROLLED_BACK
    }

    /**
     * Test and diagnostic seam for deterministic process-loss recovery tests.
     */
    @FunctionalInterface
    public interface PhaseHook {

        /**
         * Observes a phase after its durable journal write.
         *
         * @param phase durable phase
         * @throws Exception to simulate process loss after the phase
         */
        void after(Phase phase) throws Exception;
    }

    /**
     * Durable reset journal projection.
     *
     * @param transactionId      reset transaction identity
     * @param repositoryIdentity canonical Git common-directory identity
     * @param oldProjectId       old project identity
     * @param newProjectId       new project identity
     * @param oldNamespace       old external namespace
     * @param newNamespace       new external namespace
     * @param stagingNamespace   transaction-owned staging namespace
     * @param baselineReference  immutable prepared baseline reference
     * @param phase              durable reset phase
     * @param failure            fail-closed diagnostic, when present
     */
    public record Journal(String transactionId, String repositoryIdentity, String oldProjectId,
                          String newProjectId, Path oldNamespace, Path newNamespace,
                          Path stagingNamespace, String baselineReference, Phase phase,
                          String failure) {

        /**
         * Validates and normalizes a journal.
         */
        public Journal {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(repositoryIdentity, "repositoryIdentity");
            Objects.requireNonNull(oldProjectId, "oldProjectId");
            Objects.requireNonNull(newProjectId, "newProjectId");
            oldNamespace = normalize(oldNamespace, "oldNamespace");
            newNamespace = normalize(newNamespace, "newNamespace");
            stagingNamespace = normalize(stagingNamespace, "stagingNamespace");
            Objects.requireNonNull(baselineReference, "baselineReference");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(failure, "failure");
        }

        private static Path normalize(Path value, String name) {
            return Objects.requireNonNull(value, name)
                    .toAbsolutePath()
                    .normalize();
        }
    }

    /**
     * Result of preparing, resuming, or recovering a reset transaction.
     *
     * @param transactionId reset transaction identity
     * @param phase         observed durable phase
     * @param authoritative whether the new identity is active
     * @param reason        stable result reason
     */
    public record Result(String transactionId, Phase phase, boolean authoritative, String reason) {

        /**
         * Validates a reset result.
         */
        public Result {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Stable reset failure with an actionable reason code.
     */
    public static final class ResetFailure extends Exception {

        @java.io.Serial
        private static final long serialVersionUID = 1L;
        /**
         * Stable machine-readable failure code.
         */
        private final String code;

        /**
         * Creates a reset failure.
         *
         * @param code    stable failure code
         * @param message diagnostic message
         */
        public ResetFailure(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Creates a reset failure with an underlying cause.
         *
         * @param code    stable failure code
         * @param message diagnostic message
         * @param cause   underlying cause
         */
        public ResetFailure(String code, String message, Throwable cause) {
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

    /**
     * Exclusive project reset lock.
     */
    private record ResetLock(FileChannel channel, FileLock lock) implements AutoCloseable {

        private static ResetLock acquire(Path resetRoot) throws ResetFailure {
            try {
                Files.createDirectories(resetRoot);
                Path lockPath = resetRoot.resolve("reset.transaction.lock");
                FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        channel.close();
                        throw failure("RESET_TRANSACTION_BUSY", "another reset transaction owns the project lock");
                    }
                    return new ResetLock(channel, lock);
                } catch (OverlappingFileLockException busy) {
                    channel.close();
                    throw failure("RESET_TRANSACTION_BUSY", "another reset transaction owns the project lock");
                } catch (Exception failure) {
                    if (failure instanceof ResetFailure resetFailure) {
                        throw resetFailure;
                    }
                    channel.close();
                    throw failure("RESET_LOCK_FAILED", diagnostic(failure), failure);
                }
            } catch (IOException failure) {
                throw failure("RESET_LOCK_FAILED", diagnostic(failure), failure);
            }
        }

        private void assertHeld() throws ResetFailure {
            if (!lock.isValid()) {
                throw failure("RESET_TRANSACTION_BUSY", "reset lock is not valid");
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // The process is already leaving the bounded lock scope.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The OS releases the lock on process termination.
            }
        }
    }
}
