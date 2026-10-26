package org.synesis.workspace.application.project.execution.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The macOS sandbox's generated profile and its fail-closed probe. They run on any computer, because nothing here
 * starts {@code sandbox-exec}; running it is the Mac checklist in ADR-0104 stage 4.
 */
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
class MacSandboxExecSandboxTest {

    private static SandboxPolicy policy(Path writable, Path hidden) {
        return new SandboxPolicy(List.of(writable), List.of(Path.of("/ctl/.git")), List.of(Path.of("/lane/.git")), List.of(Path.of("/lane/config.worktree")),
                List.of(hidden), Map.of("HOME", "/lane/home"));
    }

    @Test
    void probeReportsUnavailableUntilVerifiedOnAMac() {
        SandboxAvailability availability = new MacSandboxExecSandbox().probe();

        assertFalse(availability.available());
        assertTrue(availability.reason().contains("macOS sandbox not yet verified on a real Mac (ADR-0104 stage 4)"));
        assertTrue(availability.fix().contains(MacSandboxExecSandbox.UNVERIFIED_PROPERTY));
    }

    @Test
    void wrapRefusesWhileUnavailable() {
        assertThrows(SandboxSetupException.class, () -> new MacSandboxExecSandbox().wrap(policy(Path.of("/lane"), Path.of("/ctl")), List.of("ls"), Map.of()));
    }

    @Test
    void platformSandboxOnMacIsTheRefusingOne() {
        assertInstanceOf(MacSandboxExecSandbox.class, Sandboxes.create("mac os x", false));
    }

    @Test
    void profileOrdersRulesSoLaterOnesWin() throws Exception {
        String profile = MacSandboxExecSandbox.profile(policy(Path.of("/lane"), Path.of("/ctl")));

        assertTrue(profile.startsWith("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n"));
        assertFalse(profile.contains("(deny network"), "the network stays allowed");
        List<String> order = List.of("(deny file-read* file-write* (subpath \"/",
                "(allow file-read* (subpath \"/ctl/.git\"))", "(allow file-read* file-write* (subpath \"/lane\"))",
                "(deny file-write* (subpath \"/lane/.git\"))", "(deny file-write* (literal \"/lane/config.worktree\"))");
        assertTrue(profile.lines().anyMatch(line -> line.startsWith("(deny file-read* file-write*") && line.contains("(subpath \"/ctl\")")), "the control checkout is hidden");
        int last = -1;
        for (String rule : order) {
            int at = profile.indexOf(rule);
            assertTrue(at > last, "rule missing or out of order: " + rule + "\n" + profile);
            last = at;
        }
    }

    @Test
    void hostilePathCharactersCannotAddARule() throws Exception {
        String profile = MacSandboxExecSandbox.profile(policy(Path.of("/lane/a\") (allow file-read* (subpath \"/\")) (\"b c\\d"), Path.of("/ctl")));

        assertTrue(profile.contains("(subpath \"/lane/a\\\") (allow file-read* (subpath \\\"/\\\")) (\\\"b c\\\\d\")"), profile);
        assertEquals(1, profile.lines().filter(line -> line.startsWith("(allow file-read* file-write*")).count());
    }

    @Test
    void parenthesesAndSpacesStayInsideTheString() throws Exception {
        String profile = MacSandboxExecSandbox.profile(policy(Path.of("/lane/My Work (copy)"), Path.of("/ctl")));

        assertTrue(profile.contains("(subpath \"/lane/My Work (copy)\")"));
    }

    @Test
    void controlCharacterInAPathIsRefused() {
        assertThrows(SandboxSetupException.class, () -> MacSandboxExecSandbox.profile(policy(Path.of("/lane/a\nb"), Path.of("/ctl"))));
    }
}
