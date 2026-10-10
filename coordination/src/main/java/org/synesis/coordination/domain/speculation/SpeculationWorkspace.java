package org.synesis.coordination.domain.speculation;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;

/**
 * Creates and gates one isolated Git worktree for an accepted prediction.
 */
public final class SpeculationWorkspace implements AutoCloseable {

    private static final String METADATA_FILE = "speculation.meta";
    private static final String BASE_COMMIT_KEY = "baseCommit=";
    private final Path repositoryRoot;
    private final Path metadataDirectory;
    private final Path worktree;
    private final UUID predictionId;
    private final String baseCommit;
    private boolean created;

    /**
     * Defines a bounded speculation workspace below local Synesis state.
     *
     * @param repositoryRoot repository containing the source worktree
     * @param localRoot      local `.synesis` state root
     * @param predictionId   prediction identifier
     * @param baseCommit     immutable base commit
     */
    public SpeculationWorkspace(Path repositoryRoot, Path localRoot, UUID predictionId, String baseCommit) {
        this.repositoryRoot = CoordinationNullException.requireNonNull(repositoryRoot, "repository root")
                .toAbsolutePath().normalize();
        this.predictionId = CoordinationNullException.requireNonNull(predictionId, "prediction ID");
        this.baseCommit = requireText(baseCommit);
        this.metadataDirectory = CoordinationNullException.requireNonNull(localRoot, "local root")
                .resolve("speculation").resolve(predictionId.toString()).toAbsolutePath().normalize();
        this.worktree = metadataDirectory.resolve("worktree");
        if (!worktree.startsWith(metadataDirectory)) {
            throw new CoordinationArgumentException("invalid worktree path");
        }
        this.created = Files.isDirectory(worktree);
    }

    /**
     * Runs a bounded command and returns its validated output.
     *
     * @param command authenticated command to evaluate
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String run(String... command) throws IOException {
        return runInternal(true, command);
    }

    /**
     * Runs a process whose nonzero exit is an expected gate result.
     *
     * @param command authenticated command to evaluate
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String runAllowFailure(String... command) throws IOException {
        return runInternal(false, command);
    }

    /**
     * Runs a bounded process and captures its result.
     *
     * @param command authenticated command to evaluate
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String runInternal(boolean requireSuccess, String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var input = process.getInputStream()) {
            String output = new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            try {
                if (requireSuccess && process.waitFor() != 0) {
                    throw new CoordinationException("git failed: " + output);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CoordinationException("git interrupted", interrupted);
            }
            return output;
        }
    }

    /**
     * Checks that the text value satisfies this type's bounds.
     */
    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new CoordinationArgumentException("base commit invalid");
        }
        return value;
    }

    /**
     * Creates the detached worktree and writes bounded metadata.
     *
     * @throws IOException when Git or metadata persistence fails
     */
    public synchronized void create() throws IOException {
        if (created) {
            return;
        }
        Files.createDirectories(metadataDirectory);
        runGit("worktree", "add", "--detach", worktree.toString(), baseCommit);
        Files.writeString(metadataDirectory.resolve("speculation.meta"),
                "predictionId=" + predictionId + "\nbaseCommit=" + baseCommit + "\nstatus=CREATED\n",
                StandardCharsets.UTF_8);
        created = true;
    }

    /**
     * Reads the base commit that {@link #create()} recorded for a prediction.
     *
     * @param localRoot    local `.synesis` state root
     * @param predictionId prediction identifier
     * @return the recorded base commit, or empty when the metadata has no base commit line
     * @throws IOException when the metadata file is missing or unreadable
     */
    public static Optional<String> recordedBaseCommit(Path localRoot, UUID predictionId) throws IOException {
        Path metadata = localRoot.resolve("speculation").resolve(predictionId.toString()).resolve(METADATA_FILE);
        return Files.readAllLines(metadata).stream()
                .filter(line -> line.startsWith(BASE_COMMIT_KEY))
                .findFirst()
                .map(line -> line.substring(BASE_COMMIT_KEY.length()));
    }

    /**
     * Runs fail-closed conflict and whitespace checks on the isolated tree.
     *
     * @return gate result
     * @throws IOException when Git status cannot be obtained
     */
    public synchronized SpeculationGateResult gate() throws IOException {
        if (!created) {
            throw new CoordinationStateException("speculation workspace is not created");
        }
        String whitespace = runGitAtWorktreeAllowFailure();
        String status = runGitAtWorktree();
        boolean unmerged = status.lines()
                .anyMatch(line -> line.length() >= 2 && (line.startsWith("UU") || line.startsWith("AA")
                        || line.startsWith("DD") || line.startsWith("AU") || line.startsWith("UA")));
        boolean accepted = whitespace.isBlank() && !unmerged;
        return new SpeculationGateResult(accepted ? SpeculationGateStatus.PASSED : SpeculationGateStatus.REJECTED,
                accepted ? "SPECULATION_GATE=PASS" : "SPECULATION_GATE=REJECT",
                whitespace + status);
    }

    /**
     * Returns the isolated worktree path.
     *
     * @return worktree path
     */
    public Path worktree() {
        return worktree;
    }

    /**
     * Removes the isolated worktree and local metadata.
     *
     * @throws IOException when Git cannot remove the worktree
     */
    @Override
    public synchronized void close() throws IOException {
        if (!created) {
            return;
        }
        runGit("worktree", "remove", "--force", worktree.toString());
        Files.deleteIfExists(metadataDirectory.resolve(METADATA_FILE));
        Files.deleteIfExists(metadataDirectory);
        created = false;
    }

    /**
     * Runs the Git operation in the registered worktree.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private String runGitAtWorktree() throws IOException {
        String[] command = new String[5];
        command[0] = "git";
        command[1] = "-C";
        command[2] = worktree.toString();
        command[3] = "status";
        command[4] = "--porcelain=v1";
        return run(command);
    }

    /**
     * Runs the Git operation in the registered worktree.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private String runGitAtWorktreeAllowFailure() throws IOException {
        String[] command = new String[5];
        command[0] = "git";
        command[1] = "-C";
        command[2] = worktree.toString();
        command[3] = "diff";
        command[4] = "--check";
        return runAllowFailure(command);
    }

    /**
     * Runs the Git command with this workspace's repository root.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void runGit(String... arguments) throws IOException {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repositoryRoot.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        run(command);
    }

}
