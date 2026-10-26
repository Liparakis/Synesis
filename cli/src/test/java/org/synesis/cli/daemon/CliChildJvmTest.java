package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * A child JVM must not write its crash report into the project it runs in (D9 of the 2026-10-26 real runs).
 */
final class CliChildJvmTest {

    @Test
    void theCrashReportGoesToSynesisStateBeforeTheClassPath() {
        List<String> command = CliChildJvm.builder(false, List.of("daemon", "--foreground")).command();

        String option = command.stream().filter(argument -> argument.startsWith("-XX:ErrorFile=")).findFirst().orElseThrow();
        assertTrue(option.endsWith("hs_err_pid%p.log"), option);
        assertTrue(option.startsWith("-XX:ErrorFile=" + AdministrativeStateLocator.applicationStateRoot().resolve("crash")), option);
        assertTrue(command.indexOf(option) < command.indexOf("-cp"), "JVM options come before the class path: " + command);
        assertEquals("org.synesis.cli.SynesisCli", command.get(command.indexOf("-cp") + 2));
    }
}
