package org.synesis.workspace.application.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderIntegration.SignIn;

/**
 * Finds which registered agents are installed on this computer, which version each is, and whether
 * each says someone is signed in (ADR-0103 stage 2).
 *
 * <p>An agent is found when its executable is on the search path. Its version and its sign-in state come
 * only from commands the agent itself offers ({@link ProviderIntegration#versionArguments()},
 * {@link ProviderIntegration#signInStatusArguments()}). Discovery never opens a configuration or credential
 * file of an agent, and it keeps nothing of a command's output but the version line and the sign-in state,
 * because a status command may print an account name. A command that fails, is missing or times out
 * leaves the answer unknown; it never makes the agent not found.</p>
 */
public final class ProviderDiscovery {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final int OUTPUT_LIMIT = 16 * 1024;
    private static final int VERSION_LIMIT = 80;
    /** A warning an agent prints before its version must not be taken for the version (D1, real run 2026-10-26). */
    private static final java.util.function.Predicate<String> VERSION_LINE = java.util.regex.Pattern.compile("\\d+\\.\\d+").asPredicate();

    private final String pathVariable;
    private final boolean windows;
    private final Runner runner;

    /**
     * Creates discovery over this process's own search path.
     */
    public ProviderDiscovery() {
        this(System.getenv("PATH"), System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), ProviderDiscovery::runProcess);
    }

    /**
     * Creates discovery that runs the agents' own commands over a given search path.
     *
     * @param pathVariable the search path to look the executables up on; may be {@code null}
     * @param windows      whether to resolve executables as Windows does
     */
    public ProviderDiscovery(String pathVariable, boolean windows) {
        this(pathVariable, windows, ProviderDiscovery::runProcess);
    }

    /**
     * Creates discovery over a given search path and command runner.
     *
     * @param pathVariable the search path to look the executables up on; may be {@code null}
     * @param windows      whether to resolve executables as Windows does
     * @param runner       runs a resolved command to completion
     */
    public ProviderDiscovery(String pathVariable, boolean windows, Runner runner) {
        this.pathVariable = pathVariable;
        this.windows = windows;
        this.runner = runner;
    }

    /**
     * Runs a command to completion and returns what it printed.
     */
    @FunctionalInterface
    public interface Runner {

        /**
         * Runs a command.
         *
         * @param command argv, already resolved to something a process builder starts
         * @return exit code and combined output
         * @throws IOException when the command cannot be run or does not finish in time
         */
        Answer run(List<String> command) throws IOException;
    }

    /**
     * What a command printed.
     *
     * @param exitCode process exit code
     * @param output   standard output and error together, cut at a fixed size
     */
    public record Answer(int exitCode, String output) {

    }

    /**
     * What discovery learned about one registered agent.
     *
     * @param provider provider identifier
     * @param found    whether the executable is on the search path
     * @param version  the version line the agent printed, when it printed one
     * @param signIn   what the agent's own status command says, {@link SignIn#UNKNOWN} when it cannot tell
     */
    public record Finding(String provider, boolean found, Optional<String> version, SignIn signIn) {

    }

    /**
     * Inspects every registered provider, in registry order.
     *
     * @return one finding per registered provider
     */
    public List<Finding> discover() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Finding>> pending = ProviderRegistry.providers().stream().map(provider -> executor.submit(() -> inspect(provider))).toList();
            List<Finding> findings = new ArrayList<>();
            for (Future<Finding> future : pending) {
                try {
                    findings.add(future.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("agent discovery was interrupted", interrupted);
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("agent discovery failed", failure.getCause());
                }
            }
            return List.copyOf(findings);
        }
    }

    private Finding inspect(ProviderIntegration provider) {
        Optional<Path> executable = ProviderExecutables.find(provider.executableName(), pathVariable, windows);
        if (executable.isEmpty()) {
            return new Finding(provider.id(), false, Optional.empty(), SignIn.UNKNOWN);
        }
        Optional<String> version = ask(provider, executable.get(), provider.versionArguments()).filter(answer -> answer.exitCode() == 0).flatMap(ProviderDiscovery::firstLine);
        SignIn signIn = provider.signInStatusArguments().flatMap(arguments -> ask(provider, executable.get(), arguments)).map(answer -> provider.signInState(answer.exitCode(), answer.output())).orElse(SignIn.UNKNOWN);
        return new Finding(provider.id(), true, version, signIn);
    }

    /**
     * Runs the executable that was found, not whichever one the process builder would find on this process's own search path,
     * so the search path given to discovery decides what runs. On Windows the name is resolved again, which also wraps a shim.
     */
    private Optional<Answer> ask(ProviderIntegration provider, Path executable, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(windows ? provider.executableName() : executable.toAbsolutePath().toString());
        command.addAll(arguments);
        try {
            return Optional.of(runner.run(ProviderExecutables.command(command, pathVariable, windows)));
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstLine(Answer answer) {
        return answer.output().lines().map(String::strip).map(line -> line.replaceAll("\\p{Cntrl}", "")).filter(VERSION_LINE).findFirst().map(line -> line.length() > VERSION_LIMIT ? line.substring(0, VERSION_LIMIT) : line);
    }

    private static Answer runProcess(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> copyLimited(process.getInputStream(), captured));
        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("the command did not finish in " + COMMAND_TIMEOUT.toSeconds() + " seconds");
            }
            drain.join(1000);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
        synchronized (captured) {
            return new Answer(process.exitValue(), captured.toString(StandardCharsets.UTF_8));
        }
    }

    private static void copyLimited(InputStream input, ByteArrayOutputStream captured) {
        byte[] buffer = new byte[4096];
        try (input) {
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                synchronized (captured) {
                    captured.write(buffer, 0, Math.min(read, Math.max(0, OUTPUT_LIMIT - captured.size())));
                }
            }
        } catch (IOException ended) {
            // The stream ends with the process; what was read so far is the answer.
        }
    }
}
