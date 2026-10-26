package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D8 of the 2026-10-26 real runs: a command never silently uses a daemon of another build.
 */
final class DaemonBuildTest {

    @Test
    void aDaemonOfThisBuildIsAccepted() {
        assertDoesNotThrow(() -> DaemonClient.requireSameBuild(Map.of("ok", true, "build", BuildInfo.identity()), 42L));
    }

    @Test
    void aDaemonOfAnotherBuildIsRefusedWithAMessageAndAFixNamingItsProcess() {
        DaemonBuildMismatchException other = assertThrows(DaemonBuildMismatchException.class, () -> DaemonClient.requireSameBuild(Map.of("ok", true, "build", "0.0.68 abc 2026-01-01T00:00:00Z"), 4242L));

        assertTrue(other.userMessage().contains("0.0.68 abc") && other.userMessage().contains(BuildInfo.identity()), other.userMessage());
        assertTrue(other.fix().contains("4242"), other.fix());
        assertEquals("daemon_build_mismatch", other.getMessage());
    }

    @Test
    void aDaemonThatNamesNoBuildIsAnotherBuild() {
        assertThrows(DaemonBuildMismatchException.class, () -> DaemonClient.requireSameBuild(Map.of("ok", true, "state", "RUNNING"), 7L));
    }
}
