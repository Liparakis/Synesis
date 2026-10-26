package org.synesis.workspace.application.agent.run;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.synesis.workspace.application.provider.ProviderExecutables;

/**
 * Starts provider agent processes and keeps each one's output as a log file.
 *
 * <p>The output is copied line by line to a file outside the project and searched only for the
 * provider's session identifier. It is never interpreted, summarised, or shown to another agent.</p>
 */
public final class ProviderAgentProcesses implements AgentRunController.AgentProcesses {

    private final Path logDirectory;
    private final String pathVariable;
    private final boolean windows;
    private final AtomicInteger launches = new AtomicInteger();

    /**
     * Creates a launcher that resolves executables from this process's own search path.
     *
     * @param logDirectory directory that receives one log file per started process
     */
    public ProviderAgentProcesses(Path logDirectory) {
        this(logDirectory, System.getenv("PATH"), System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    ProviderAgentProcesses(Path logDirectory, String pathVariable, boolean windows) {
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory").toAbsolutePath().normalize();
        this.pathVariable = pathVariable == null ? "" : pathVariable;
        this.windows = windows;
    }

    @Override
    public AgentRunController.RunningAgent start(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label, Function<String, Optional<String>> sessionIdReader)
            throws IOException {
        Files.createDirectories(logDirectory);
        Path log = logDirectory.resolve(label.replace('#', '-') + "-" + launches.incrementAndGet() + ".log");
        ProcessBuilder builder = new ProcessBuilder(executable(command)).directory(workingDirectory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        try (Writer stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            stdin.write(prompt);
        } catch (IOException closedEarly) {
            // A provider that exits before reading its prompt reports the reason on its output.
        }
        AtomicReference<String> sessionId = new AtomicReference<>();
        Thread pump = Thread.ofPlatform().daemon(true).name("synesis-agent-output-" + label).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    sessionIdReader.apply(line).ifPresent(sessionId::set);
                }
            } catch (IOException | RuntimeException ended) {
                // The stream ends when the process does; the log holds whatever was produced.
            }
        });
        return new AgentRunController.RunningAgent() {

            @Override
            public boolean isAlive() {
                // The identifier is announced on the output, so the process only counts as ended
                // once that output has been read to its end.
                return process.isAlive() || pump.isAlive();
            }

            @Override
            public int exitCode() {
                return process.exitValue();
            }

            @Override
            public Optional<String> providerSessionId() {
                return Optional.ofNullable(sessionId.get());
            }

            @Override
            public void stop() {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        };
    }

    /**
     * Resolves the provider executable so a script shim can be started as reliably as a binary
     * ({@link ProviderExecutables#command}).
     */
    List<String> executable(List<String> command) throws IOException {
        return ProviderExecutables.command(command, pathVariable, windows);
    }
}
