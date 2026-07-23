package org.synesis.coordination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Creates and gates one isolated Git worktree for an accepted prediction. */
public final class SpeculationWorkspace implements AutoCloseable {
    private final Path repositoryRoot;
    private final Path metadataDirectory;
    private final Path worktree;
    private final UUID predictionId;
    private final String baseCommit;
    private boolean created;

    /**
     * Defines a bounded speculation workspace below local Synesis state.
     * @param repositoryRoot repository containing the source worktree
     * @param localRoot local `.synesis` state root
     * @param predictionId prediction identifier
     * @param baseCommit immutable base commit
     */
    public SpeculationWorkspace(Path repositoryRoot, Path localRoot, UUID predictionId, String baseCommit) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repository root").toAbsolutePath().normalize();
        this.predictionId = Objects.requireNonNull(predictionId, "prediction ID");
        this.baseCommit = requireText(baseCommit, "base commit");
        this.metadataDirectory = Objects.requireNonNull(localRoot, "local root").resolve("speculation")
                .resolve(predictionId.toString()).toAbsolutePath().normalize();
        this.worktree = metadataDirectory.resolve("worktree");
        if (!worktree.startsWith(metadataDirectory)) throw new IllegalArgumentException("invalid worktree path");
    }

    /** Creates the detached worktree and writes bounded metadata.
     * @throws IOException when Git or metadata persistence fails
     */
    public synchronized void create() throws IOException {
        if (created) return;
        Files.createDirectories(metadataDirectory);
        runGit("worktree", "add", "--detach", worktree.toString(), baseCommit);
        Files.writeString(metadataDirectory.resolve("speculation.meta"),
                "predictionId=" + predictionId + "\nbaseCommit=" + baseCommit + "\nstatus=CREATED\n",
                StandardCharsets.UTF_8);
        created = true;
    }

    /** Runs fail-closed conflict and whitespace checks on the isolated tree.
     * @return gate result
     * @throws IOException when Git status cannot be obtained
     */
    public synchronized GateResult gate() throws IOException {
        if (!created) throw new IllegalStateException("speculation workspace is not created");
        String whitespace = runGitAtWorktreeAllowFailure("diff", "--check");
        String status = runGitAtWorktree("status", "--porcelain=v1");
        boolean unmerged = status.lines().anyMatch(line -> line.length() >= 2
                && (line.startsWith("UU") || line.startsWith("AA") || line.startsWith("DD")
                        || line.startsWith("AU") || line.startsWith("UA")));
        boolean accepted = whitespace.isBlank() && !unmerged;
        return new GateResult(accepted, accepted ? "SPECULATION_GATE=PASS" : "SPECULATION_GATE=REJECT",
                whitespace + status);
    }

    /** Returns the isolated worktree path.
     * @return worktree path
     */
    public Path worktree() { return worktree; }

    /** Removes the isolated worktree and local metadata.
     * @throws IOException when Git cannot remove the worktree
     */
    @Override public synchronized void close() throws IOException {
        if (!created) return;
        runGit("worktree", "remove", "--force", worktree.toString());
        Files.deleteIfExists(metadataDirectory.resolve("speculation.meta"));
        Files.deleteIfExists(metadataDirectory);
        created = false;
    }

    /** Result of the fail-closed speculation gate.
     * @param accepted whether the gate passed
     * @param status compact status marker
     * @param diagnostics bounded diagnostics
     */
    public record GateResult(boolean accepted, String status, String diagnostics) {
        /** Validates bounded gate output. */
        public GateResult {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    private String runGitAtWorktree(String... arguments) throws IOException {
        String[] command = new String[arguments.length + 3]; command[0] = "git"; command[1] = "-C";
        command[2] = worktree.toString(); System.arraycopy(arguments, 0, command, 3, arguments.length);
        return run(command);
    }
    private String runGitAtWorktreeAllowFailure(String... arguments) throws IOException {
        String[] command = new String[arguments.length + 3]; command[0] = "git"; command[1] = "-C";
        command[2] = worktree.toString(); System.arraycopy(arguments, 0, command, 3, arguments.length);
        return runAllowFailure(command);
    }
    private String runGit(String... arguments) throws IOException {
        String[] command = new String[arguments.length + 3]; command[0] = "git"; command[1] = "-C";
        command[2] = repositoryRoot.toString(); System.arraycopy(arguments, 0, command, 3, arguments.length);
        return run(command);
    }
    private static String run(String... command) throws IOException {
        return runInternal(true, command);
    }
    private static String runAllowFailure(String... command) throws IOException {
        return runInternal(false, command);
    }
    private static String runInternal(boolean requireSuccess, String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var input = process.getInputStream()) {
            String output = new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            try { if (requireSuccess && process.waitFor() != 0) throw new IOException("git failed: " + output); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("git interrupted", interrupted); }
            return output;
        }
    }
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException(label + " invalid");
        return value;
    }
}
