package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Classifies control-checkout content for a managed-baseline transaction.
 *
 * <p>The classifier asks Git about tracked and ignored state instead of
 * recursively walking ignored build output. Ignored environmental output is
 * therefore harmless, while managed paths remain protected unless the active
 * transaction has journaled ownership of their creation.</p>
 */
public final class ManagedPathPolicy {

    private static final List<String> DEFAULT_MANAGED_PATHS = List.of(".synesis/project.json", "AGENTS.md");

    private static Map<String, String> absentStates(List<String> paths) {
        Map<String, String> states = new LinkedHashMap<>();
        for (String path : paths) {
            states.put(path, StartState.ABSENT.name());
        }
        return states;
    }

    /**
     * Creates a policy using the canonical managed contract paths.
     */
    public ManagedPathPolicy() {
    }

    private static String statusPath(String line) {
        if (line.startsWith("? ") || line.startsWith("! ")) {
            return line.substring(2)
                    .trim();
        }
        int fields = line.startsWith("u ") ? 11 : 9;
        String[] tokens = line.split(" ", fields);
        if (tokens.length == fields) {
            String path = tokens[fields - 1];
            int separator = path.indexOf('\t');
            return separator >= 0 ? path.substring(0, separator) : path;
        }
        int tab = line.indexOf('\t');
        return tab >= 0 && tab + 1 < line.length() ? line.substring(tab + 1) : line;
    }

    private static boolean isTracked(Path root, String path) throws IOException {
        return run(root, "ls-files", "--error-unmatch", "--", path).exitCode() == 0;
    }

    private static boolean isIgnored(Path root, String path) throws IOException {
        return run(root, "check-ignore", "--no-index", "--quiet", "--", path).exitCode() == 0;
    }

    private static String git(Path root) throws IOException {
        Result result = run(root, "status", "--porcelain=v2", "--untracked-files=all");
        if (result.exitCode() != 0) {
            throw new IOException("git inspection failed: " + result.output());
        }
        return result.output();
    }

    private static Result run(Path root, String... args) throws IOException {
        GitProcessRunner.Result result = GitProcessRunner.runResult(root, args);
        return new Result(result.exitCode(), result.output());
    }

    private static Path normalize(Path root) {
        return Objects.requireNonNull(root, "repositoryRoot")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Inspects one repository before or during a baseline transaction.
     *
     * @param repositoryRoot repository worktree
     * @return classification report
     * @throws IOException when Git state cannot be inspected
     */
    public Report inspect(Path repositoryRoot) throws IOException {
        return inspect(repositoryRoot, Optional.empty());
    }

    /**
     * Inspects one repository with optional transaction-owned managed files.
     *
     * @param repositoryRoot repository worktree
     * @param ownership      active transaction ownership, if any
     * @return classification report
     * @throws IOException when Git state cannot be inspected
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public Report inspect(Path repositoryRoot, Optional<TransactionOwnership> ownership) throws IOException {
        Path root = normalize(repositoryRoot);
        List<Finding> findings = new ArrayList<>();
        String status = git(root);
        for (String line : status.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String path = statusPath(line);
            if (ownership.isPresent() && ownership.get()
                    .owns(root, path)) {
                findings.add(new Finding(path, Classification.TRANSACTION_OWNED_IGNORED_MANAGED_PATH, false));
                continue;
            }
            if (line.startsWith("? ")) {
                findings.add(new Finding(path, Classification.UNTRACKED_NON_IGNORED, true));
            } else if (line.startsWith("u ") || line.startsWith("1 ") || line.startsWith("2 ")
                    || line.startsWith("0 ")) {
                findings.add(new Finding(path, Classification.TRACKED_CHANGE, true));
            }
        }
        for (String managed : DEFAULT_MANAGED_PATHS) {
            if (!Files.exists(root.resolve(managed)) || isTracked(root, managed) || !isIgnored(root, managed)) {
                continue;
            }
            boolean allowed = ownership.isPresent() && ownership.get()
                    .owns(root, managed);
            findings.add(new Finding(managed, allowed
                    ? Classification.TRANSACTION_OWNED_IGNORED_MANAGED_PATH
                    : Classification.IGNORED_MANAGED_PATH_COLLISION, !allowed));
        }
        return new Report(findings);
    }

    /**
     * Returns the canonical paths this policy protects from hidden collisions.
     *
     * @return immutable managed paths
     */
    public List<String> managedPaths() {
        return DEFAULT_MANAGED_PATHS;
    }

    /**
     * Classifies one managed path at transaction start.
     *
     * @param repositoryRoot repository worktree
     * @param path           repository-relative managed path
     * @return start-state classification
     * @throws IOException when Git state cannot be inspected
     */
    public StartState classify(Path repositoryRoot, String path) throws IOException {
        Path root = normalize(repositoryRoot);
        Path target = root.resolve(path)
                .normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (isTracked(root, path)) {
                return StartState.TRACKED;
            }
            return isIgnored(root, path) ? StartState.IGNORED : StartState.UNTRACKED;
        }
        return isTracked(root, path) ? StartState.TRACKED : StartState.ABSENT;
    }

    /**
     * Classification result for one inspected path.
     */
    public enum Classification {
        /**
         * Tracked content has staged or unstaged changes.
         */
        TRACKED_CHANGE,
        /**
         * Untracked and not ignored content must block preparation.
         */
        UNTRACKED_NON_IGNORED,
        /**
         * Existing ignored content collides with managed state.
         */
        IGNORED_MANAGED_PATH_COLLISION,
        /**
         * The active journal owns this ignored managed path.
         */
        TRANSACTION_OWNED_IGNORED_MANAGED_PATH
    }

    /**
     * State recorded for one managed path before a transaction writes it.
     */
    public enum StartState {
        /**
         * The path was absent from the worktree and index.
         */
        ABSENT,
        /**
         * The path was already tracked and clean.
         */
        TRACKED,
        /**
         * The path existed but was not tracked or ignored.
         */
        UNTRACKED,
        /**
         * The path existed and was ignored by Git.
         */
        IGNORED
    }

    /**
     * One path classification.
     *
     * @param path              repository-relative path
     * @param classification    classification kind
     * @param blocksTransaction whether the finding blocks the transaction
     */
    public record Finding(String path, Classification classification, boolean blocksTransaction) {

        /**
         * Validates a finding.
         */
        public Finding {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(classification, "classification");
        }
    }

    /**
     * Immutable inspection report.
     *
     * @param findings path findings
     */
    public record Report(List<Finding> findings) {

        /**
         * Copies findings and validates them.
         */
        public Report {
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        }

        /**
         * Returns whether any finding blocks the transaction.
         *
         * @return true when transaction progress is unsafe
         */
        public boolean blocked() {
            return findings.stream()
                    .anyMatch(Finding::blocksTransaction);
        }
    }

    /**
     * Journaled ownership proof for managed files created during one transaction.
     *
     * @param repositoryIdentity canonical Git-common-directory identity
     * @param transactionId      active baseline transaction identity
     * @param absentAtStart      paths absent from both HEAD and worktree at start
     * @param expectedDigests    expected SHA-256 content by repository-relative path
     * @param startStates        recorded start state by repository-relative path
     */
    public record TransactionOwnership(String repositoryIdentity, String transactionId,
                                       List<String> absentAtStart, Map<String, String> expectedDigests,
                                       Map<String, String> startStates) {

        /**
         * Copies ownership evidence into immutable collections.
         */
        public TransactionOwnership {
            Objects.requireNonNull(repositoryIdentity, "repositoryIdentity");
            Objects.requireNonNull(transactionId, "transactionId");
            absentAtStart = List.copyOf(Objects.requireNonNull(absentAtStart, "absentAtStart"));
            expectedDigests = Map.copyOf(Objects.requireNonNull(expectedDigests, "expectedDigests"));
            startStates = Map.copyOf(Objects.requireNonNull(startStates, "startStates"));
        }

        /**
         * Compatibility constructor for callers that only record absent paths.
         *
         * @param repositoryIdentity canonical repository identity
         * @param transactionId      transaction identity
         * @param absentAtStart      paths absent at transaction start
         * @param expectedDigests    expected content digests
         */
        public TransactionOwnership(String repositoryIdentity, String transactionId,
                List<String> absentAtStart, Map<String, String> expectedDigests) {
            this(repositoryIdentity, transactionId, absentAtStart, expectedDigests, absentStates(absentAtStart));
        }

        private static String hash(byte[] bytes) throws IOException {
            try {
                return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(bytes));
            } catch (Exception failure) {
                throw new IOException("managed path hash unavailable", failure);
            }
        }

        private boolean owns(Path root, String path) {
            String state = startStates.get(path);
            if (!StartState.ABSENT.name()
                    .equals(state) && !StartState.TRACKED.name()
                    .equals(state)) {
                return false;
            }
            String expected = expectedDigests.get(path);
            if (expected == null || Files.isSymbolicLink(root.resolve(path))
                    || !Files.isRegularFile(root.resolve(path))) {
                return false;
            }
            try {
                return expected.equals(hash(Files.readAllBytes(root.resolve(path))));
            } catch (IOException failure) {
                return false;
            }
        }
    }

    private record Result(int exitCode, String output) {

    }
}
