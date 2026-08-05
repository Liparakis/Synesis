package org.synesis.workspace.lifecycle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs a bounded local process with deterministic stdin, output, timeout, and
 * descendant cleanup behavior.
 */
final class ProcessCommandRunner {

    private ProcessCommandRunner() {
    }

    /**
     * Executes one process while merging and bounding its standard streams.
     *
     * @param command complete process command
     * @param directory process working directory
     * @param environment environment overrides
     * @param timeout maximum process lifetime
     * @param maxOutputBytes maximum captured merged output
     * @return completed process result
     * @throws IOException if the process cannot start, is interrupted, or
     *     exceeds the timeout
     */
    static Result execute(List<String> command, Path directory, Map<String, String> environment,
                          Duration timeout, int maxOutputBytes) throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("command must contain non-null arguments");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputBytes < 0) {
            throw new IllegalArgumentException("maxOutputBytes must not be negative");
        }

        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command))
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();
        OutputCollector collector = new OutputCollector(process.getInputStream(), maxOutputBytes);
        Thread reader = Thread.ofPlatform().daemon(true).name("synesis-process-output").start(collector);
        long deadline = System.nanoTime() + timeout.toNanos();
        long wallClockDeadline = System.currentTimeMillis() + timeout.toMillis();
        boolean timedOut = false;
        try {
            while (process.isAlive()) {
                if (System.nanoTime() >= deadline || System.currentTimeMillis() >= wallClockDeadline) {
                    timedOut = true;
                    break;
                }
                long remainingMillis = Math.min(
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline)),
                        Math.max(1L, wallClockDeadline - System.currentTimeMillis()));
                Thread.sleep(Math.min(25L, Math.max(1L, remainingMillis)));
            }
            if (timedOut || process.isAlive()) {
                terminateProcessTree(process);
                joinReaderAfterTermination(reader, process);
                throw timeout(command, directory, timeout, collector.text());
            }
            joinReader(reader, process, deadline);
        } catch (InterruptedException interrupted) {
            terminateProcessTree(process);
            joinReaderUnbounded(reader, process);
            Thread.currentThread().interrupt();
            throw new IOException("Process interrupted: command=" + command
                    + ", directory=" + directory, interrupted);
        }
        int exitCode = process.exitValue();
        return new Result(exitCode, collector.text());
    }

    private static long remainingNanos(long deadline) {
        return Math.max(1L, deadline - System.nanoTime());
    }

    private static void joinReader(Thread reader, Process process, long deadline) throws IOException {
        try {
            reader.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline))));
            if (reader.isAlive()) {
                terminateProcessTree(process);
                process.getInputStream().close();
                reader.join(250L);
            }
            if (reader.isAlive()) {
                throw new IOException("Process output reader did not terminate: "
                        + process.info().command().orElse("unknown process"));
            }
        } catch (InterruptedException interrupted) {
            terminateProcessTree(process);
            process.getInputStream().close();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting process output", interrupted);
        }
    }

    private static void joinReaderAfterTermination(Thread reader, Process process) throws IOException {
        try {
            reader.join(250L);
            if (reader.isAlive()) {
                process.getInputStream().close();
                reader.join(250L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting timed-out process output", interrupted);
        }
    }

    private static void joinReaderUnbounded(Thread reader, Process process) {
        try {
            reader.join(250L);
            if (reader.isAlive()) {
                process.getInputStream().close();
                reader.join(250L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The caller is already reporting the interruption.
        }
    }

    private static CommandTimeoutException timeout(List<String> command, Path directory,
                                                    Duration timeout, String output) {
        return new CommandTimeoutException("Process timed out after " + timeout + ": command="
                + command + ", directory=" + directory + ", output=" + output,
                command, directory, output);
    }

    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroy();
        }
        process.destroy();
        try {
            Thread.sleep(100L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = descendants.get(index);
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /** Result of a completed process. */
    record Result(int exitCode, String output) {
    }

    /** Describes a process that exceeded its bounded lifetime. */
    static final class CommandTimeoutException extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> command;
        private final transient Path directory;
        private final transient String output;

        private CommandTimeoutException(String message, List<String> command, Path directory,
                                        String output) {
            super(message);
            this.command = List.copyOf(command);
            this.directory = directory;
            this.output = output;
        }

        /** @return the exact command that timed out */
        List<String> command() {
            return command;
        }

        /** @return the process working directory */
        Path directory() {
            return directory;
        }

        /** @return bounded merged process output */
        String output() {
            return output;
        }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean truncated;

        private OutputCollector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (InputStream stream = input) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    int remaining = limit - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, Math.min(count, remaining));
                    }
                    if (count > Math.max(0, remaining)) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // The process lifecycle owns the stream; timeout cleanup may close it.
            }
        }

        private synchronized String text() {
            String value = output.toString(StandardCharsets.UTF_8);
            return truncated ? value + "\n[output truncated]" : value;
        }
    }
}
