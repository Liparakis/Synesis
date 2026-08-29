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
import org.synesis.cli.terminal.ConsoleTerminal;

public class RepairCommandTest {

    @Test
    public void testRepairCliDryRunAndPrepare(@TempDir Path tempDir) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        ConsoleTerminal terminal = new ConsoleTerminal(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        CliRuntime runtime = CliRuntime.defaults(terminal);

        int exitCodeDry = SynesisCli.execute(new String[]{"repair", "--dry-run", "--project", tempDir.toString()},
                runtime);
        assertEquals(0, exitCodeDry);
        assertTrue(stdout.toString()
                .contains("REPAIR_DRY_RUN=COMPLETED"));

        stdout.reset();
        int exitCodePrep = SynesisCli.execute(new String[]{"repair", "--prepare", "--project", tempDir.toString()},
                runtime);
        assertEquals(0, exitCodePrep);
        assertTrue(stdout.toString()
                .contains("REPAIR_RESULT=PLAN_PREPARED"));
    }
}
