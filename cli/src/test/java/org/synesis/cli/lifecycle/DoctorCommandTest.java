package org.synesis.cli.lifecycle;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;

/**
 * Exercises the CLI doctor command's local diagnostics contract.
 */
public class DoctorCommandTest {

    @Test
    public void testDoctorCliConciseAndJson(@TempDir Path tempDir) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));

        CliRuntime runtime = CliRuntime.defaults(terminal);

        int exitCode = SynesisCli.execute(new String[]{"doctor", "--project", tempDir.toString()}, runtime);
        assertEquals(0, exitCode);

        String output = stdout.toString();
        assertTrue(output.contains("DOCTOR_RESULT="));
        assertTrue(output.contains("FINDINGS="));
    }

    @Test
    public void strictExitsWithDoctorBrokenWhenTheProjectIsNotHealthy(@TempDir Path tempDir) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        int strict = SynesisCli.execute(new String[]{"doctor", "--strict", "--project", tempDir.toString()}, CliRuntime.defaults(terminal));

        assertTrue(!stdout.toString(StandardCharsets.UTF_8).contains("DOCTOR_RESULT=HEALTHY"), "a folder that is not a project is not healthy: " + stdout);
        assertEquals(ExitCodes.DOCTOR_BROKEN, strict);
        assertEquals(3, strict);
    }
}
