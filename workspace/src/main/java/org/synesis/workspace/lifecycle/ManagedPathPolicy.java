package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /** Creates a policy using the canonical managed contract paths. */
    public ManagedPathPolicy() {
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
     * @param ownership active transaction ownership, if any
     * @return classification report
     * @throws IOException when Git state cannot be inspected
     */
    public Report inspect(Path repositoryRoot, Optional<TransactionOwnership> ownership) throws IOException {
        Path root = normalize(repositoryRoot);
        List<Finding> findings = new ArrayList<>();
        String status = git(root, "status", "--porcelain=v2", "--untracked-files=all");
        for (String line : status.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String path = statusPath(line);
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
            boolean allowed = ownership.isPresent() && ownership.get().owns(root, managed);
            findings.add(new Finding(managed, allowed
                    ? Classification.TRANSACTION_OWNED_IGNORED_MANAGED_PATH
                    : Classification.IGNORED_MANAGED_PATH_COLLISION, !allowed));
        }
        return new Report(findings);
    }

    /** Returns the canonical paths this policy protects from hidden collisions.
     * @return immutable managed paths
     */
    public List<String> managedPaths() {
        return DEFAULT_MANAGED_PATHS;
    }

    private static String statusPath(String line) {
        int tab = line.indexOf('\t');
        if (tab >= 0 && tab + 1 < line.length()) {
            String path = line.substring(tab + 1);
            int separator = path.indexOf('\t');
            return separator >= 0 ? path.substring(separator + 1) : path;
        }
        return line.length() > 2 ? line.substring(2).trim() : line;
    }

    private static boolean isTracked(Path root, String path) throws IOException {
        return run(root, "ls-files", "--error-unmatch", "--", path).exitCode() == 0;
    }

    private static boolean isIgnored(Path root, String path) throws IOException {
        return run(root, "check-ignore", "--no-index", "--quiet", "--", path).exitCode() == 0;
    }

    private static String git(Path root, String... args) throws IOException {
        Result result = run(root, args);
        if (result.exitCode() != 0) {
            throw new IOException("git inspection failed: " + result.output());
        }
        return result.output();
    }

    private static Result run(Path root, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            return new Result(exit, output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("git inspection interrupted", interrupted);
        }
    }

    private static Path normalize(Path root) {
        return Objects.requireNonNull(root, "repositoryRoot").toAbsolutePath().normalize();
    }

    /** Classification result for one inspected path. */
    public enum Classification {
        /** Tracked content has staged or unstaged changes. */
        TRACKED_CHANGE,
        /** Untracked and not ignored content must block preparation. */
        UNTRACKED_NON_IGNORED,
        /** Existing ignored content collides with managed state. */
        IGNORED_MANAGED_PATH_COLLISION,
        /** The active journal owns this ignored managed path. */
        TRANSACTION_OWNED_IGNORED_MANAGED_PATH
    }

    /** One path classification. */
    public record Finding(String path, Classification classification, boolean blocksTransaction) {
        /** Validates a finding. */
        public Finding {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(classification, "classification");
        }
    }

    /** Immutable inspection report. */
    public record Report(List<Finding> findings) {
        /** Copies findings and validates them. */
        public Report {
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        }

        /** @return whether any finding blocks the transaction */
        public boolean blocked() {
            return findings.stream().anyMatch(Finding::blocksTransaction);
        }
    }

    /**
     * Journaled ownership proof for managed files created during one transaction.
     *
     * @param repositoryIdentity canonical Git-common-directory identity
     * @param transactionId active baseline transaction identity
     * @param absentAtStart paths absent from both HEAD and worktree at start
     * @param expectedDigests expected SHA-256 content by repository-relative path
     */
    public record TransactionOwnership(String repositoryIdentity, String transactionId,
                                       List<String> absentAtStart, Map<String, String> expectedDigests) {
        /** Copies ownership evidence into immutable collections. */
        public TransactionOwnership {
            Objects.requireNonNull(repositoryIdentity, "repositoryIdentity");
            Objects.requireNonNull(transactionId, "transactionId");
            absentAtStart = List.copyOf(Objects.requireNonNull(absentAtStart, "absentAtStart"));
            expectedDigests = Map.copyOf(Objects.requireNonNull(expectedDigests, "expectedDigests"));
        }

        private boolean owns(Path root, String path) {
            if (!absentAtStart.contains(path)) {
                return false;
            }
            String expected = expectedDigests.get(path);
            if (expected == null || !Files.isRegularFile(root.resolve(path))) {
                return false;
            }
            try {
                return expected.equals(hash(Files.readAllBytes(root.resolve(path))));
            } catch (IOException failure) {
                return false;
            }
        }

        private static String hash(byte[] bytes) throws IOException {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception failure) {
                throw new IOException("managed path hash unavailable", failure);
            }
        }
    }

    private record Result(int exitCode, String output) {
    }
}
