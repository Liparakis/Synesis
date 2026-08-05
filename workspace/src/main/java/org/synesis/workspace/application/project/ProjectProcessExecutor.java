package org.synesis.workspace.application.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Executes one bounded direct argv process inside an authoritative worktree.
 *
 * <p>This class is the only generic project-process primitive. It drains both
 * process streams concurrently, retains bounded head/tail evidence, counts all
 * raw bytes read, and kills the complete process tree on timeout,
 * cancellation, or termination. It is thread-safe because each invocation
 * owns its process and collectors.</p>
 *
 * @since 1.0
 */
public final class ProjectProcessExecutor {

    /** Maximum raw bytes retained per output stream. */
    public static final int MAX_RETAINED_BYTES = 65_536;
    /** Raw bytes retained at the beginning of an overflowing stream. */
    public static final int HEAD_RETAINED_BYTES = MAX_RETAINED_BYTES / 2;
    /** Raw bytes retained at the end of an overflowing stream. */
    public static final int TAIL_RETAINED_BYTES = MAX_RETAINED_BYTES / 2;
    /** Default process timeout in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = ProjectCommandSpec.DEFAULT_TIMEOUT_SECONDS;
    /** Maximum process timeout in seconds. */
    public static final int MAX_TIMEOUT_SECONDS = ProjectCommandSpec.MAX_TIMEOUT_SECONDS;
    /** Stable marker inserted between retained head and tail text. */
    public static final String TRUNCATION_MARKER = "\n...[output truncated; retained head and tail]...\n";

    /** Stable execution outcomes returned to agents and server gates. */
    public enum Outcome {
        /** Process started and exited with code zero. */
        COMPLETED("completed"),
        /** Process started and exited with a non-zero code. */
        NON_ZERO_EXIT("non_zero_exit"),
        /** The executable could not be started because it was not found. */
        COMMAND_EXECUTABLE_NOT_FOUND("command_executable_not_found"),
        /** The requested working directory failed policy or filesystem checks. */
        COMMAND_WORKING_DIRECTORY_INVALID("command_working_directory_invalid"),
        /** The executable was found but could not be started due to permissions. */
        COMMAND_PERMISSION_DENIED("command_permission_denied"),
        /** Process creation failed for another concrete reason. */
        COMMAND_START_FAILED("command_start_failed"),
        /** The process exceeded its configured timeout. */
        COMMAND_TIMED_OUT("command_timed_out"),
        /** The caller cancelled execution by interrupting the operation. */
        COMMAND_CANCELLED("command_cancelled"),
        /** The process tree was terminated before normal completion. */
        COMMAND_TERMINATED("command_terminated");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        /**
         * Returns the stable JSON value.
         *
         * @return lowercase outcome value
         */
        public String value() {
            return value;
        }
    }

    /**
     * One execution request. The working directory is resolved relative to
     * {@link #worktreeRoot()} and never relative to the server process.
     *
     * @param argv              direct executable and arguments
     * @param worktreeRoot      authoritative lane or integration worktree
     * @param workingDirectory  relative working directory, or {@code null}
     * @param timeoutSeconds    timeout in seconds, or {@code null} for default
     * @param controlRoot       control root whose absolute path is sanitized, or {@code null}
     */
    public record ExecutionRequest(List<String> argv, Path worktreeRoot, String workingDirectory,
            Integer timeoutSeconds, Path controlRoot) {
        /**
         * Creates a request without a separate control-root sanitization path.
         *
         * @param argv direct executable and arguments
         * @param worktreeRoot authoritative worktree root
         * @param workingDirectory relative working directory, or {@code null}
         * @param timeoutSeconds timeout in seconds, or {@code null} for default
         */
        public ExecutionRequest(List<String> argv, Path worktreeRoot, String workingDirectory,
                Integer timeoutSeconds) {
            this(argv, worktreeRoot, workingDirectory, timeoutSeconds, null);
        }

        /** Validates and normalizes request values. */
        public ExecutionRequest {
            Objects.requireNonNull(argv, "argv");
            Objects.requireNonNull(worktreeRoot, "worktreeRoot");
            argv = List.copyOf(argv);
            worktreeRoot = worktreeRoot.toAbsolutePath().normalize();
            if (workingDirectory == null || workingDirectory.isBlank()) {
                workingDirectory = ".";
            }
            if (timeoutSeconds == null) {
                timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            }
            if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("timeoutSeconds must be between 1 and "
                        + MAX_TIMEOUT_SECONDS);
            }
        }

        /**
         * Creates a request from a project-owned command specification.
         *
         * @param spec project-owned command specification
         * @param worktreeRoot authoritative lane or integration root
         * @param controlRoot control root used for sanitization
         * @return executable request
         */
        public static ExecutionRequest from(ProjectCommandSpec spec, Path worktreeRoot, Path controlRoot) {
            Objects.requireNonNull(spec, "spec");
            return new ExecutionRequest(spec.argv(), worktreeRoot, spec.workingDirectory(),
                    spec.timeoutSeconds(), controlRoot);
        }
    }

    /**
     * Bounded structured evidence from one process execution.
     *
     * @param outcome              stable outcome classification
     * @param exitCode             process exit code, or {@code null} when no exit exists
     * @param stdout               sanitized retained stdout text
     * @param stderr               sanitized retained stderr text
     * @param stdoutBytesRead     raw stdout bytes drained before decoding
     * @param stderrBytesRead     raw stderr bytes drained before decoding
     * @param stdoutBytesRetained raw stdout bytes represented in returned text
     * @param stderrBytesRetained raw stderr bytes represented in returned text
     * @param stdoutTruncated      whether stdout evidence is incomplete
     * @param stderrTruncated      whether stderr evidence is incomplete
     */
    public record ExecutionResult(Outcome outcome, Integer exitCode, String stdout, String stderr,
            long stdoutBytesRead, long stderrBytesRead, long stdoutBytesRetained, long stderrBytesRetained,
            boolean stdoutTruncated, boolean stderrTruncated) {
        /** Validates result values. */
        public ExecutionResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
            if (stdoutBytesRead < 0 || stderrBytesRead < 0 || stdoutBytesRetained < 0 || stderrBytesRetained < 0
                    || stdoutBytesRetained > stdoutBytesRead || stderrBytesRetained > stderrBytesRead
                    || stdoutBytesRetained > MAX_RETAINED_BYTES || stderrBytesRetained > MAX_RETAINED_BYTES) {
                throw new IllegalArgumentException("invalid output evidence counts");
            }
        }

        /**
         * Converts this evidence to the stable agent-facing result object.
         *
         * @return insertion-ordered result map
         */
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", outcome.value());
            if (exitCode != null) {
                result.put("exitCode", exitCode);
            }
            result.put("stdout", stdout);
            result.put("stderr", stderr);
            result.put("stdoutTruncated", stdoutTruncated);
            result.put("stderrTruncated", stderrTruncated);
            result.put("stdoutBytesRead", stdoutBytesRead);
            result.put("stderrBytesRead", stderrBytesRead);
            result.put("stdoutBytesRetained", stdoutBytesRetained);
            result.put("stderrBytesRetained", stderrBytesRetained);
            return result;
        }

        /**
         * Returns whether the process started and exited with code zero.
         *
         * @return true only for a completed zero exit
         */
        public boolean succeeded() {
            return outcome == Outcome.COMPLETED;
        }
    }

    /** Exact process identity captured immediately after a command starts.
     * @param pid operating-system process ID
     * @param executableIdentity executable identity reported by the process handle
     * @param commandLine bounded process command line evidence
     * @param processStartTime process start epoch milliseconds
     */
    public record StartedProcessIdentity(long pid, String executableIdentity,
            String commandLine, long processStartTime) {
        /** Validates process identity evidence. */
        public StartedProcessIdentity {
            if (pid <= 0 || processStartTime < 0) {
                throw new IllegalArgumentException("invalid started process identity");
            }
            Objects.requireNonNull(executableIdentity, "executableIdentity");
            Objects.requireNonNull(commandLine, "commandLine");
        }
    }

    /** Creates a stateless generic executor. */
    public ProjectProcessExecutor() {
    }

    /**
     * Executes the request synchronously.
     *
     * @param request execution request
     * @return structured bounded evidence; never {@code null}
     */
    public ExecutionResult execute(ExecutionRequest request) {
        return execute(request, ignored -> {
        });
    }

    /**
     * Executes one request and notifies the caller before waiting for completion.
     *
     * @param request command execution request
     * @param startedObserver callback invoked after process start and before waiting
     * @return bounded execution evidence
     */
    public ExecutionResult execute(ExecutionRequest request, Consumer<StartedProcessIdentity> startedObserver) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(startedObserver, "startedObserver");
        Path worktreeRoot = request.worktreeRoot().toAbsolutePath().normalize();
        Path workingDirectory;
        try {
            workingDirectory = resolveWorkingDirectory(worktreeRoot, request.workingDirectory());
        } catch (RuntimeException failure) {
            return empty(Outcome.COMMAND_WORKING_DIRECTORY_INVALID);
        }
        List<String> argv;
        try {
            argv = validateArgv(request.argv());
        } catch (RuntimeException failure) {
            return empty(Outcome.COMMAND_START_FAILED);
        }

        Process process = null;
        StreamCollector stdout = new StreamCollector();
        StreamCollector stderr = new StreamCollector();
        boolean interrupted = false;
        Outcome terminalOutcome = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(workingDirectory.toFile());
            filterEnvironment(builder.environment());
            process = builder.start();
            ProcessHandle.Info processInfo = process.info();
            long processStart = processInfo.startInstant().map(java.time.Instant::toEpochMilli)
                    .orElse(System.currentTimeMillis());
            try {
                startedObserver.accept(new StartedProcessIdentity(process.pid(),
                        processInfo.command().orElse(argv.getFirst()),
                        processInfo.commandLine().orElse(String.join(" ", argv)), processStart));
            } catch (RuntimeException observerFailure) {
                killProcessTree(process);
                terminalOutcome = Outcome.COMMAND_START_FAILED;
            }
            if (terminalOutcome != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                stdout.markComplete();
                stderr.markComplete();
                return result(terminalOutcome, null, stdout, stderr, request);
            }
            Thread stdoutThread = startCollector(process.getInputStream(), stdout, "synesis-command-stdout");
            Thread stderrThread = startCollector(process.getErrorStream(), stderr, "synesis-command-stderr");
            boolean finished;
            try {
                finished = process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException cancellation) {
                interrupted = true;
                finished = false;
                terminalOutcome = Outcome.COMMAND_CANCELLED;
                // Clear the interrupt while the killed process streams are
                // drained and joined; restore it in the outer finally block.
                Thread.interrupted();
            }
            if (!finished) {
                if (terminalOutcome == null) {
                    terminalOutcome = Outcome.COMMAND_TIMED_OUT;
                }
                killProcessTree(process);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
            if (finished && process.exitValue() != 0) {
                terminalOutcome = Outcome.NON_ZERO_EXIT;
            } else if (finished) {
                terminalOutcome = Outcome.COMPLETED;
            }
            joinCollector(stdoutThread);
            joinCollector(stderrThread);
            Integer exitCode = finished ? process.exitValue() : null;
            return result(terminalOutcome, exitCode, stdout, stderr, request);
        } catch (IOException startFailure) {
            if (process != null) {
                killProcessTree(process);
            } else {
                // No process means no pipe can remain open. Report the empty
                // streams as complete evidence alongside the distinct no-start
                // outcome instead of manufacturing truncation.
                stdout.markComplete();
                stderr.markComplete();
            }
            Outcome outcome = classifyStartFailure(startFailure);
            return result(outcome, null, stdout, stderr, request);
        } catch (RuntimeException failure) {
            if (process != null) {
                killProcessTree(process);
            }
            return result(Outcome.COMMAND_TERMINATED, null, stdout, stderr, request);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<String> validateArgv(List<String> input) {
        if (input.isEmpty() || input.size() > ProjectCommandSpec.MAX_ARGUMENTS) {
            throw new IllegalArgumentException("invalid argv size");
        }
        for (String argument : input) {
            if (argument == null || argument.length() > ProjectCommandSpec.MAX_ARGUMENT_LENGTH) {
                throw new IllegalArgumentException("invalid argv entry");
            }
        }
        if (input.getFirst().isBlank()) {
            throw new IllegalArgumentException("blank executable");
        }
        return List.copyOf(input);
    }

    private static Path resolveWorkingDirectory(Path root, String configured) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("worktree root is not a directory");
        }
        Path relative;
        try {
            String requested = configured == null || configured.isBlank() ? "." : configured;
            if (looksLikeAbsolutePath(requested)) {
                throw new IllegalArgumentException("working directory is absolute");
            }
            relative = Path.of(requested);
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("working directory path is invalid", failure);
        }
        if (relative.isAbsolute() || relative.normalize().startsWith(Path.of(".."))) {
            throw new IllegalArgumentException("working directory escapes worktree");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || !Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("working directory is not inside worktree");
        }
        try {
            Path canonicalRoot = root.toRealPath();
            Path canonicalCandidate = candidate.toRealPath();
            if (!canonicalCandidate.startsWith(canonicalRoot)) {
                throw new IllegalArgumentException("working directory symlink escapes worktree");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("working directory cannot be verified", failure);
        }
        return candidate;
    }

    private static boolean looksLikeAbsolutePath(String value) {
        return value.startsWith("/") || value.startsWith("\\\\")
                || value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static void filterEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("KEY")
                    || upper.contains("AUTH");
        });
    }

    private static Thread startCollector(InputStream input, StreamCollector collector, String name) {
        Thread thread = new Thread(() -> collector.read(input), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinCollector(Thread thread) {
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Outcome classifyStartFailure(IOException failure) {
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("denied") || message.contains("permission") || message.contains("access is denied")) {
            return Outcome.COMMAND_PERMISSION_DENIED;
        }
        if (failure instanceof java.nio.file.NoSuchFileException
                || message.contains("cannot find") || message.contains("no such file")
                || message.contains("error=2") || message.contains("error=3")) {
            return Outcome.COMMAND_EXECUTABLE_NOT_FOUND;
        }
        return Outcome.COMMAND_START_FAILED;
    }

    private static ExecutionResult result(Outcome outcome, Integer exitCode, StreamCollector stdout,
            StreamCollector stderr, ExecutionRequest request) {
        Evidence out = stdout.evidence();
        Evidence err = stderr.evidence();
        return new ExecutionResult(outcome, exitCode,
                sanitize(out.text(), request.controlRoot(), request.worktreeRoot()),
                sanitize(err.text(), request.controlRoot(), request.worktreeRoot()),
                out.bytesRead(), err.bytesRead(), out.bytesRetained(), err.bytesRetained(),
                out.truncated(), err.truncated());
    }

    private static ExecutionResult empty(Outcome outcome) {
        return new ExecutionResult(outcome, null, "", "", 0, 0, 0, 0, false, false);
    }

    private static String sanitize(String text, Path controlRoot, Path worktreeRoot) {
        String sanitized = text == null ? "" : text;
        if (controlRoot != null) {
            sanitized = sanitized.replace(controlRoot.toAbsolutePath().normalize().toString(), "[PROJECT_ROOT]");
        }
        if (worktreeRoot != null) {
            sanitized = sanitized.replace(worktreeRoot.toAbsolutePath().normalize().toString(), "[WORKTREE_ROOT]");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            sanitized = sanitized.replace(home, "~");
        }
        return sanitized;
    }

    private static void killProcessTree(Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (RuntimeException ignored) {
                }
            });
            process.destroyForcibly();
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private record Evidence(String text, long bytesRead, long bytesRetained, boolean truncated) {
    }

    private static final class StreamCollector {
        private final ByteArrayOutputStream head = new ByteArrayOutputStream(HEAD_RETAINED_BYTES);
        private final byte[] tail = new byte[TAIL_RETAINED_BYTES];
        private int tailSize;
        private int tailCursor;
        private long bytesRead;
        private boolean eof;

        private void read(InputStream input) {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    append(buffer, 0, count);
                }
                eof = true;
            } catch (IOException ignored) {
                // A killed process can close a pipe before EOF. The missing EOF
                // is reflected by the explicit truncated flag.
            }
        }

        private void markComplete() {
            eof = true;
        }

        private void append(byte[] buffer, int offset, int length) {
            bytesRead += length;
            int headRemaining = HEAD_RETAINED_BYTES - head.size();
            int headCount = Math.min(headRemaining, length);
            if (headCount > 0) {
                head.write(buffer, offset, headCount);
            }
            int tailOffset = offset + headCount;
            int tailLength = length - headCount;
            for (int i = 0; i < tailLength; i++) {
                tail[tailCursor] = buffer[tailOffset + i];
                tailCursor = (tailCursor + 1) % TAIL_RETAINED_BYTES;
                tailSize = Math.min(TAIL_RETAINED_BYTES, tailSize + 1);
            }
        }

        private Evidence evidence() {
            byte[] headBytes = head.toByteArray();
            byte[] tailBytes = tailBytes();
            boolean overflow = bytesRead > MAX_RETAINED_BYTES;
            if (!overflow && eof) {
                // At or below the bound the evidence is complete. Reassemble
                // the two internal buffers before decoding so a UTF-8 code
                // point crossing their storage boundary is not mistaken for
                // truncated output.
                byte[] complete = new byte[headBytes.length + tailBytes.length];
                System.arraycopy(headBytes, 0, complete, 0, headBytes.length);
                System.arraycopy(tailBytes, 0, complete, headBytes.length, tailBytes.length);
                return new Evidence(new String(complete, StandardCharsets.UTF_8), bytesRead, bytesRead, false);
            }
            int headEnd = safeHeadEnd(headBytes);
            int tailStart = safeTailStart(tailBytes);
            byte[] safeHead = Arrays.copyOf(headBytes, headEnd);
            byte[] safeTail = Arrays.copyOfRange(tailBytes, tailStart, tailBytes.length);
            boolean boundaryDiscarded = safeHead.length != headBytes.length || safeTail.length != tailBytes.length;
            String headText = new String(safeHead, StandardCharsets.UTF_8);
            String tailText = new String(safeTail, StandardCharsets.UTF_8);
            String text = overflow ? headText + TRUNCATION_MARKER + tailText : headText + tailText;
            long retained = safeHead.length + safeTail.length;
            return new Evidence(text, bytesRead, retained, overflow || boundaryDiscarded || !eof);
        }

        private byte[] tailBytes() {
            if (tailSize == 0) {
                return new byte[0];
            }
            byte[] result = new byte[tailSize];
            int start = tailSize == TAIL_RETAINED_BYTES ? tailCursor : 0;
            for (int i = 0; i < tailSize; i++) {
                result[i] = tail[(start + i) % TAIL_RETAINED_BYTES];
            }
            return result;
        }

        private static int safeHeadEnd(byte[] value) {
            if (value.length == 0) {
                return 0;
            }
            int index = value.length - 1;
            int continuation = 0;
            while (index >= 0 && isContinuation(value[index])) {
                continuation++;
                index--;
            }
            if (index < 0) {
                return value.length;
            }
            int expected = sequenceLength(value[index]);
            if (expected > 1 && expected > continuation + 1) {
                return index;
            }
            return continuation == 0 && expected > 1 ? index : value.length;
        }

        private static int safeTailStart(byte[] value) {
            int start = 0;
            while (start < value.length && isContinuation(value[start])) {
                start++;
            }
            if (start >= value.length) {
                return value.length;
            }
            int expected = sequenceLength(value[start]);
            if (expected > 1) {
                int cursor = start + 1;
                while (cursor < value.length && isContinuation(value[cursor])) {
                    cursor++;
                }
                if (expected > cursor - start) {
                    return cursor;
                }
            }
            return start;
        }

        private static boolean isContinuation(byte value) {
            return (value & 0xC0) == 0x80;
        }

        private static int sequenceLength(byte value) {
            int unsigned = value & 0xFF;
            if ((unsigned & 0x80) == 0) return 1;
            if ((unsigned & 0xE0) == 0xC0) return 2;
            if ((unsigned & 0xF0) == 0xE0) return 3;
            if ((unsigned & 0xF8) == 0xF0) return 4;
            return 0;
        }
    }
}
