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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        Process process = startProcess(builder, command, directory, timeout);
        process.getOutputStream().close();
        OutputCollector collector = new OutputCollector(process.getInputStream(), maxOutputBytes);
        Thread reader = Thread.ofPlatform().daemon(true).name("synesis-process-output").start(collector);
        FutureTask<Integer> wait = new FutureTask<>(process::waitFor);
        Thread waiter = Thread.ofPlatform().daemon(true).name("synesis-process-wait").start(wait);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofPlatform().daemon(true).name("synesis-process-watchdog").start(() -> {
            try {
                Thread.sleep(timeout.toMillis());
                if (!wait.isDone()) {
                    timedOut.set(true);
                    terminateProcessTree(process);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try {
            while (!wait.isDone() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            if (timedOut.get() || !wait.isDone()) {
                wait.cancel(true);
                terminateProcessTree(process);
                joinReaderAfterTermination(reader, process);
                throw timeout(command, directory, timeout, collector.text());
            }
            int exitCode = wait.get();
            joinReader(reader, process, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            return new Result(exitCode, collector.text(), collector.bytes());
        } catch (InterruptedException interrupted) {
            wait.cancel(true);
            terminateProcessTree(process);
            joinReaderUnbounded(reader, process);
            Thread.currentThread().interrupt();
            throw new IOException("Process interrupted: command=" + command
                    + ", directory=" + directory, interrupted);
        } catch (ExecutionException failure) {
            throw new IOException("Process wait failed: command=" + command
                    + ", directory=" + directory, failure.getCause());
        } finally {
            if (waiter.isAlive() && wait.isCancelled()) {
                waiter.interrupt();
            }
            watchdog.interrupt();
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(1L, deadline - System.nanoTime());
    }

    private static Process startProcess(ProcessBuilder builder, List<String> command,
                                        Path directory, Duration timeout) throws IOException {
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicReference<Process> started = new AtomicReference<>();
        FutureTask<Process> launch = new FutureTask<>(() -> {
            Process process = builder.start();
            started.set(process);
            if (timedOut.get()) {
                terminateProcessTree(process);
            }
            return process;
        });
        Thread launcher = Thread.ofPlatform().daemon(true).name("synesis-process-launch").start(launch);
        Thread watchdog = Thread.ofPlatform().daemon(true).name("synesis-process-launch-watchdog").start(() -> {
            try {
                Thread.sleep(timeout.toMillis());
                if (!launch.isDone()) {
                    timedOut.set(true);
                    Process process = started.get();
                    if (process != null) {
                        terminateProcessTree(process);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try {
            while (!launch.isDone() && !timedOut.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            if (timedOut.get() || !launch.isDone()) {
                timedOut.set(true);
                launch.cancel(true);
                Process process = started.get();
                if (process != null) {
                    terminateProcessTree(process);
                }
                throw timeout(command, directory, timeout, "process launch timed out");
            }
            return launch.get();
        } catch (InterruptedException interrupted) {
            timedOut.set(true);
            launch.cancel(true);
            Process process = started.get();
            if (process != null) {
                terminateProcessTree(process);
            }
            Thread.currentThread().interrupt();
            throw new IOException("Process launch interrupted: command=" + command
                    + ", directory=" + directory, interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Process launch failed: command=" + command
                    + ", directory=" + directory, cause);
        } finally {
            if (launcher.isAlive() && launch.isCancelled()) {
                launcher.interrupt();
            }
            watchdog.interrupt();
        }
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
    static final class Result {
        private final int exitCode;
        private final String output;
        private final byte[] bytes;

        Result(int exitCode, String output) {
            this(exitCode, output, output.getBytes(StandardCharsets.UTF_8));
        }

        Result(int exitCode, String output, byte[] bytes) {
            this.exitCode = exitCode;
            this.output = output;
            this.bytes = bytes.clone();
        }

        int exitCode() {
            return exitCode;
        }

        String output() {
            return output;
        }

        byte[] bytes() {
            return bytes.clone();
        }
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

        private synchronized byte[] bytes() {
            return output.toByteArray();
        }
    }
}
