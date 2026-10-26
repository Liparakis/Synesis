package org.synesis.workspace.application.project.execution.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Windows sandbox on its own: the token really is Low, the probe proves the negative facts, a label lasts exactly
 * as long as the runs that use it, and the argument quoting round-trips. The attacks on the whole system are in
 * {@code SandboxAdversarialTest}.
 */
class WindowsLowIntegritySandboxTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Path root;
    private WindowsLowIntegritySandbox sandbox;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(WINDOWS, "the low-integrity sandbox is Windows only");
        root = Files.createTempDirectory("synesis-winsandbox-").toRealPath();
        sandbox = new WindowsLowIntegritySandbox();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static SandboxPolicy policy(Path writable) {
        return new SandboxPolicy(List.of(writable), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private WrappedCommand wrap(Path writable, String... argv) throws SandboxSetupException {
        return sandbox.wrap(policy(writable), List.of(argv), new HashMap<>(System.getenv()));
    }

    /**
     * Runs a prepared command to its end and returns its exit code and output; always finishes the run.
     */
    private static String runToEnd(WrappedCommand wrapped, Path directory) throws Exception {
        try {
            Process process = wrapped.hosting().start(wrapped.argv(), directory, wrapped.environment());
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8) + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            return process.exitValue() + ":" + output.strip();
        } finally {
            wrapped.hosting().finish();
        }
    }

    @Test
    void theProbeProvesTheNegativeFacts() {
        SandboxAvailability availability = sandbox.probe();
        assertTrue(availability.available(), availability.reason());
        assertEquals("windows-low-integrity", sandbox.name());
    }

    @Test
    void theCommandRunsAtTheLowIntegrityLevelWithTheOwnersIdentity() throws Exception {
        Path lane = Files.createDirectories(root.resolve("lane"));
        String result = runToEnd(wrap(lane, "cmd.exe", "/d", "/c", "whoami /groups | findstr Label"), lane);
        assertTrue(result.startsWith("0:"), result);
        assertTrue(result.contains("Low Mandatory Level"), result);
        assertFalse(result.contains("Medium Mandatory Level"), result);
    }

    @Test
    void aLowCommandWritesItsLaneAndNothingElse() throws Exception {
        Path lane = Files.createDirectories(root.resolve("lane"));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        String result = runToEnd(wrap(lane, "cmd.exe", "/d", "/c", "echo in> in.txt & echo out> " + elsewhere.resolve("out.txt")), lane);
        assertTrue(Files.isRegularFile(lane.resolve("in.txt")), result);
        assertFalse(Files.exists(elsewhere.resolve("out.txt")), result);
    }

    @Test
    void theLaneIsWritableWhileAnyRunUsesItAndNotAfterTheLastEnds() throws Exception {
        Path lane = Files.createDirectories(root.resolve("lane"));
        Path other = Files.createDirectories(root.resolve("other"));
        WrappedCommand first = wrap(lane, "cmd.exe", "/d", "/c", "exit /b 0");
        WrappedCommand second = wrap(lane, "cmd.exe", "/d", "/c", "exit /b 0");
        first.hosting().finish();
        // The first run ended; the second still holds the label, so a command in another lane can still write into this one.
        String whileHeld = runToEnd(wrap(other, "cmd.exe", "/d", "/c", "echo held> " + lane.resolve("held.txt")), other);
        assertTrue(Files.isRegularFile(lane.resolve("held.txt")), whileHeld);
        second.hosting().finish();
        String afterwards = runToEnd(wrap(other, "cmd.exe", "/d", "/c", "echo late> " + lane.resolve("late.txt")), other);
        assertFalse(Files.exists(lane.resolve("late.txt")), afterwards);
    }

    @Test
    void aHiddenFileCannotBeReadAndAProtectedFileCannotBeReplacedWhileTheRunLasts() throws Exception {
        Path lane = Files.createDirectories(root.resolve("lane"));
        Path hidden = Files.createDirectories(root.resolve("hidden"));
        Files.writeString(hidden.resolve("key.bin"), "KEY");
        Path pointer = lane.resolve("pointer");
        Files.writeString(pointer, "trusted");
        SandboxPolicy policy = new SandboxPolicy(List.of(lane), List.of(), List.of(pointer), List.of(), List.of(hidden), Map.of());
        WrappedCommand wrapped = sandbox.wrap(policy, List.of("cmd.exe", "/d", "/c",
                "type " + hidden.resolve("key.bin") + " & del " + pointer + " & move " + pointer + " " + lane.resolve("moved") + " & echo evil> " + pointer), new HashMap<>(System.getenv()));
        String result = runToEnd(wrapped, lane);
        assertFalse(result.contains("KEY"), result);
        assertEquals("trusted", Files.readString(pointer).strip());
        assertFalse(Files.exists(lane.resolve("moved")));
    }

    @Test
    void aTimedOutTreeIsEndedByKillingTheProcess() throws Exception {
        Path lane = Files.createDirectories(root.resolve("lane"));
        WrappedCommand wrapped = wrap(lane, "cmd.exe", "/d", "/c", "ping -n 60 127.0.0.1 > nul");
        try {
            Process process = wrapped.hosting().start(wrapped.argv(), lane, wrapped.environment());
            assertFalse(process.waitFor(500, TimeUnit.MILLISECONDS));
            assertTrue(process.isAlive());
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        } finally {
            wrapped.hosting().finish();
        }
    }

    @Test
    void argumentsAreQuotedSoTheyRoundTrip() throws Exception {
        Path lane = Files.createDirectories(root.resolve("lane"));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Files.writeString(lane.resolve("Echo.java"), "public class Echo { public static void main(String[] a) { for (String s : a) System.out.println('[' + s + ']'); } }");
        String result = runToEnd(wrap(lane, javaExecutable, "Echo.java", "plain", "with space", "with \"quote\"", "trailing\\", "", "c:\\dir with space\\"), lane);
        assertTrue(result.startsWith("0:"), result);
        List<String> lines = result.substring(2).lines().filter(line -> line.startsWith("[")).toList();
        assertEquals(List.of("[plain]", "[with space]", "[with \"quote\"]", "[trailing\\]", "[]", "[c:\\dir with space\\]"), lines);
    }
}
