package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for bounded local Git/process execution. */
class ProcessCommandRunnerTest {

    private static final Pattern DESCENDANT = Pattern.compile("DESCENDANT_PID=(\\d+)");

    @Test
    void drainsLargeMergedStderrWithoutBlocking(@TempDir Path temp) throws Exception {
        ProcessCommandRunner.Result result = ProcessCommandRunner.execute(child("stderr"), temp,
                Map.of(), Duration.ofSeconds(5), 4096);

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("[output truncated]"));
        assertTrue(result.output().length() < 5000);
    }

    @Test
    void closesChildStdinImmediately(@TempDir Path temp) throws Exception {
        ProcessCommandRunner.Result result = ProcessCommandRunner.execute(child("stdin"), temp,
                Map.of(), Duration.ofSeconds(5), 4096);

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("stdin-closed"));
    }

    @Test
    void timesOutWithBoundedDiagnosticsAndKillsDescendants(@TempDir Path temp) throws Exception {
        ProcessCommandRunner.CommandTimeoutException failure = assertThrows(
                ProcessCommandRunner.CommandTimeoutException.class,
                () -> ProcessCommandRunner.execute(child("descendant"), temp, Map.of(),
                        Duration.ofMillis(400), 128));

        assertTrue(failure.getMessage().contains("command="));
        assertTrue(failure.getMessage().contains("directory=" + temp));
        assertTrue(failure.getMessage().contains("[output truncated]")
                || failure.output().contains("DESCENDANT_PID="));
        assertTrue(failure.output().length() < 200);
        Matcher matcher = DESCENDANT.matcher(failure.output());
        assertTrue(matcher.find(), failure.getMessage());
        long pid = Long.parseLong(matcher.group(1));
        assertTrue(awaitProcessExit(pid), "descendant process survived timeout: " + pid);
    }

    @Test
    void ordinaryGitSetupSucceeds(@TempDir Path temp) throws Exception {
        Path repository = temp.resolve("repo");
        Files.createDirectories(repository);
        GitProcessRunner.run(repository, "init");
        Files.writeString(repository.resolve("README.md"), "test\n", StandardCharsets.UTF_8);
        GitProcessRunner.run(repository, "add", "README.md");
        GitProcessRunner.run(repository, "commit", "-m", "test");

        String head = GitProcessRunner.run(repository, "rev-parse", "HEAD").trim();
        assertEquals(40, head.length());
        GitProcessRunner.runWithIndex(repository, temp.resolve("temporary-index"), "read-tree", head);
    }

    private static List<String> child(String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        return List.of(executable, "-cp", System.getProperty("java.class.path"),
                ProcessCommandRunnerChild.class.getName(), mode);
    }

    private static boolean awaitProcessExit(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}

/** Child process used only to deterministically exercise process plumbing. */
final class ProcessCommandRunnerChild {

    private ProcessCommandRunnerChild() {
    }

    /** Runs one deterministic child behavior. @param args behavior name */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stderr" -> {
                System.err.print("e".repeat(200_000));
                System.err.flush();
            }
            case "stdin" -> {
                while (System.in.read() != -1) {
                    // The runner must close stdin so this loop terminates.
                }
                System.out.print("stdin-closed");
            }
            case "descendant" -> {
                Process child = new ProcessBuilder(javaExecutable(), "-cp",
                        System.getProperty("java.class.path"), ProcessCommandRunnerChild.class.getName(),
                        "sleep").start();
                System.out.print("DESCENDANT_PID=" + child.pid());
                System.out.flush();
                sleepForever();
            }
            case "sleep" -> sleepForever();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void sleepForever() throws InterruptedException {
        Thread.sleep(Duration.ofDays(1).toMillis());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString();
    }
}
