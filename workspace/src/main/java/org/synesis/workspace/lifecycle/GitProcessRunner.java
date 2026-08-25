package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes Git commands with the repository's non-interactive test and
 * administrative-state policy.
 */
public final class GitProcessRunner {

    /** Maximum lifetime of one local Git command. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    /** Maximum merged output retained for one local Git command. */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    private GitProcessRunner() {
    }

    /**
     * Runs a required Git command.
     *
     * @param workdir Git working directory
     * @param arguments Git arguments after {@code git}
     * @return bounded UTF-8 output
     * @throws IOException if Git fails to start, times out, or exits nonzero
     */
    public static String run(Path workdir, String... arguments) throws IOException {
        return runInternal(workdir, null, DEFAULT_TIMEOUT, true, arguments).output();
    }

    /**
     * Runs a required Git command and preserves its bounded raw output bytes.
     *
     * @param workdir Git working directory
     * @param arguments Git arguments after {@code git}
     * @return bounded raw Git output
     * @throws IOException if Git fails to start, times out, or exits nonzero
     */
    static byte[] runBytes(Path workdir, String... arguments) throws IOException {
        return runInternal(workdir, null, DEFAULT_TIMEOUT, true, arguments).bytes();
    }

    /**
     * Runs a required Git command with an explicit timeout.
     *
     * @param workdir Git working directory
     * @param timeout command timeout
     * @param arguments Git arguments after {@code git}
     * @return bounded UTF-8 output
     * @throws IOException if Git fails to start, times out, or exits nonzero
     */
    public static String run(Path workdir, Duration timeout, String... arguments) throws IOException {
        return runInternal(workdir, null, timeout, true, arguments).output();
    }

    /**
     * Runs a required Git command against an explicit temporary index.
     *
     * @param workdir Git working directory
     * @param index Git index file
     * @param arguments Git arguments after {@code git}
     * @return bounded UTF-8 output
     * @throws IOException if Git fails to start, times out, or exits nonzero
     */
    public static String runWithIndex(Path workdir, Path index, String... arguments) throws IOException {
        return runInternal(workdir, index, DEFAULT_TIMEOUT, true, arguments).output();
    }

    /**
     * Runs a Git command where a nonzero exit is an expected negative result.
     *
     * @param workdir Git working directory
     * @param arguments Git arguments after {@code git}
     * @return bounded output, or an empty string for a nonzero exit
     * @throws IOException if Git fails to start or times out
     */
    public static String runOptional(Path workdir, String... arguments) throws IOException {
        Result result = runInternal(workdir, null, DEFAULT_TIMEOUT, false, arguments);
        return result.exitCode() == 0 ? result.output() : "";
    }

    /**
     * Runs a Git command and preserves its exit code for inspection operations.
     *
     * @param workdir Git working directory
     * @param arguments Git arguments after {@code git}
     * @return bounded output and process exit code
     * @throws IOException if Git fails to start or times out
     */
    public static Result runResult(Path workdir, String... arguments) throws IOException {
        return runInternal(workdir, null, DEFAULT_TIMEOUT, false, arguments);
    }

    /**
     * Runs a Git inspection with a caller-selected bounded output limit.
     *
     * <p>Ordinary command evidence retains the default 65,536-byte bound. A
     * structured inspection may require a larger bounded result when its
     * complete projection is needed for correctness.</p>
     *
     * @param workdir Git working directory
     * @param maxOutputBytes maximum output retained for this inspection
     * @param arguments Git arguments after {@code git}
     * @return bounded output and process exit code
     * @throws IOException if Git fails to start or times out
     */
    static Result runResult(Path workdir, int maxOutputBytes, String... arguments) throws IOException {
        return runInternal(workdir, null, DEFAULT_TIMEOUT, false, maxOutputBytes, arguments);
    }

    private static Result runInternal(Path workdir, Path index, Duration timeout, boolean required,
                                      String... arguments) throws IOException {
        return runInternal(workdir, index, timeout, required, DEFAULT_MAX_OUTPUT_BYTES, arguments);
    }

    private static Result runInternal(Path workdir, Path index, Duration timeout, boolean required,
                                      int maxOutputBytes, String... arguments) throws IOException {
        Path hooks = Files.createTempDirectory("synesis-empty-hooks-");
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(arguments));
            Map<String, String> environment = new HashMap<>();
            environment.put("GIT_CONFIG_NOSYSTEM", "1");
            environment.put("GIT_CONFIG_NOGLOBAL", "1");
            environment.put("GIT_TERMINAL_PROMPT", "0");
            environment.put("GIT_OPTIONAL_LOCKS", "0");
            environment.put("GIT_ASKPASS", editorDisabledCommand());
            environment.put("GIT_EDITOR", editorDisabledCommand());
            environment.put("GIT_SEQUENCE_EDITOR", editorDisabledCommand());
            environment.put("GIT_AUTHOR_NAME", "Synesis");
            environment.put("GIT_AUTHOR_EMAIL", "synesis@localhost");
            environment.put("GIT_COMMITTER_NAME", "Synesis");
            environment.put("GIT_COMMITTER_EMAIL", "synesis@localhost");
            if (index != null) {
                environment.put("GIT_INDEX_FILE", index.toString());
            }
            command.add(1, "-c"); command.add(2, "core.hooksPath=" + hooks);
            command.add(3, "-c"); command.add(4, "commit.gpgSign=false");
            command.add(5, "-c"); command.add(6, "tag.gpgSign=false");
            command.add(7, "-c"); command.add(8, "core.fsmonitor=false");
            command.add(9, "-c"); command.add(10, "user.name=Synesis");
            command.add(11, "-c"); command.add(12, "user.email=synesis@localhost");
            ProcessCommandRunner.Result result = ProcessCommandRunner.execute(command, workdir,
                    environment, timeout, maxOutputBytes);
            if (required && result.exitCode() != 0) {
                throw new IOException("Git command failed: command=" + command
                        + ", directory=" + workdir + ", exit=" + result.exitCode()
                        + ", output=" + result.output());
            }
            return new Result(result.exitCode(), result.output().trim(), result.bytes());
        } finally {
            Files.deleteIfExists(hooks);
        }
    }

    private static String editorDisabledCommand() {
        return isWindows() ? "cmd.exe /d /c exit 0" : ":";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Result of one Git process. */
    public static final class Result {
        private final int exitCode;
        private final String output;
        private final byte[] bytes;

        /**
         * Creates a Git process result.
         *
         * @param exitCode process exit code
         * @param output bounded UTF-8 process output
         * @param bytes bounded raw process output
         */
        private Result(int exitCode, String output, byte[] bytes) {
            this.exitCode = exitCode;
            this.output = output;
            this.bytes = bytes.clone();
        }

        /**
         * Returns the process exit code.
         *
         * @return process exit code
         */
        public int exitCode() {
            return exitCode;
        }

        /**
         * Returns the bounded process output.
         *
         * @return bounded UTF-8 process output
         */
        public String output() {
            return output;
        }

        byte[] bytes() {
            return bytes.clone();
        }
    }
}
