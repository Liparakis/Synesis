package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.exit.ExitCodes;

/**
 * Verifies mutating workflow commands reject ambiguous operation modes.
 */
final class CommandModePolicyTest {

    private static void assertConflict(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        int exit = SynesisCli.execute(arguments, CliRuntime.defaults(terminal));

        assertEquals(ExitCodes.USAGE, exit, String.join(" ", arguments));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("CONFLICTING_MODES"), String.join(" ", arguments));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).isEmpty(), "invalid mode combinations must not start the requested workflow");
    }

    @Test
    void dryRunCannotBeCombinedWithExecutionOrOtherLifecycleModes() {
        assertConflict("cleanup", "--dry-run", "--execute", "plan");
        assertConflict("reconcile", "--dry-run", "--execute", "plan");
        assertConflict("repair", "--dry-run", "--execute", "plan");
        assertConflict("migrate", "--dry-run", "--execute", "plan");
        assertConflict("provider", "migrate", "--dry-run", "--execute", "plan");
    }

    @Test
    void twoNonDryRunModesCannotSilentlySelectByBranchOrder() {
        assertConflict("cleanup", "--prepare", "--execute", "plan");
        assertConflict("reconcile", "--prepare", "--execute", "plan");
        assertConflict("repair", "--rollback", "execution", "--execute", "plan");
        assertConflict("migrate", "--prepare", "--show-plan", "plan");
        assertConflict("provider", "migrate", "--prepare", "--show-plan", "plan");
    }
}
