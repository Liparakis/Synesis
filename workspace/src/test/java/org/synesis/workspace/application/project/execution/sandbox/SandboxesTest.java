package org.synesis.workspace.application.project.execution.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The switch that lets the build's tests run commands unconfined must not work in an installed Synesis (ADR-0104). The
 * JVM turns {@code JAVA_TOOL_OPTIONS} into options, so a property alone proves nothing: the switch also needs the JUnit
 * API on the classpath, which the distribution does not carry.
 */
class SandboxesTest {

    @Test
    void thePropertyAloneDoesNotLeaveTheSandboxInAnInstalledSynesis() {
        assertFalse(Sandboxes.unconfinedForTests("true", className -> false), "no JUnit on the classpath");
    }

    @Test
    void theSwitchWorksInTheBuildsTestJvmAndOnlyWhenSetToTrue() {
        assertTrue(Sandboxes.unconfinedForTests("true", className -> true));
        assertFalse(Sandboxes.unconfinedForTests("false", className -> true));
        assertFalse(Sandboxes.unconfinedForTests(null, className -> true));
    }
}
