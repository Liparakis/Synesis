package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;

/**
 * Runs one CLI invocation against in-memory streams and compares the whole observable result.
 */
final class CliHarness {

    static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private CliHarness() {
    }

    /**
     * Everything a caller can observe from one invocation.
     *
     * @param exit   process exit code
     * @param stdout standard output lines
     * @param stderr standard error lines
     */
    record Result(int exit, List<String> stdout, List<String> stderr) {

        /**
         * Asserts the exit code and both streams. A line starting with {@code ~} is a regular expression that must match the whole line; any other line is compared exactly.
         */
        void assertIs(int expectedExit, List<String> expectedStdout, List<String> expectedStderr) {
            assertEquals(expectedExit, exit, () -> "exit; stdout=" + stdout + " stderr=" + stderr);
            assertLines("stdout", expectedStdout, stdout);
            assertLines("stderr", expectedStderr, stderr);
        }
    }

    /**
     * The lines of the error shape for {@link Result#assertIs}: the code, a message and a fix that say something, then
     * the legacy lines a command keeps printing.
     */
    static List<String> shaped(String code, String... legacy) {
        List<String> lines = new java.util.ArrayList<>(List.of("ERROR=" + code, "~MESSAGE=.{6,}", "~FIX=.{6,}"));
        lines.addAll(List.of(legacy));
        return lines;
    }

    static Result run(Path profile, String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime runtime = new CliRuntime(new Onboarding(profile, new StatusRenderer(terminal)), terminal);
        int exit = SynesisCli.execute(arguments, runtime);
        return new Result(exit, lines(out), lines(err));
    }

    private static List<String> lines(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static void assertLines(String stream, List<String> expected, List<String> actual) {
        assertEquals(expected.size(), actual.size(), () -> stream + " line count; actual=" + actual);
        for (int i = 0; i < expected.size(); i++) {
            String want = expected.get(i);
            String got = actual.get(i);
            if (want.startsWith("~")) {
                assertTrue(Pattern.matches(want.substring(1), got), () -> stream + " line does not match " + want + ": " + got);
            } else {
                assertEquals(want, got, stream + " line " + i);
            }
        }
    }
}
