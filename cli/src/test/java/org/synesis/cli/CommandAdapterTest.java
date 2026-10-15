package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.onboarding.OnboardingFailureCode;

/**
 * Verifies adapter output and typed failure mapping with injected streams.
 */
final class CommandAdapterTest {

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    @Test
    void statusRendererPreservesTheExactShareLink() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        String link = "synesis://join/SLB3-exact-link";
        new StatusRenderer(terminal).accept(new OnboardingEvent(OnboardingEventType.SHARE_LINK, link));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("SHARE_LINK=" + link));
        assertTrue(!out.toString(StandardCharsets.UTF_8).contains("QR_"));
        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void failureMappingUsesStableExitAndRedactedStderr() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        int exit = FailureMapper.map(new OnboardingFailure(OnboardingFailureCode.INVITE_INVALID, new IllegalStateException("secret link must not escape")), terminal);
        assertEquals(ExitCodes.INVITE_INVALID, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("FAILURE=INVITE_INVALID"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("invalid or expired"));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("secret link"));
    }

    @Test
    void aCompatibilityMismatchIsNamedLoudlyWithBothNumbersAndTheOlderSide() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        int exit = FailureMapper.map(new OnboardingFailure(OnboardingFailureCode.INVITE_INVALID, null,
                new org.synesis.link.protocol.LinkCompatibility.Mismatch("invitation", 2, 3)), terminal);
        assertEquals(ExitCodes.INCOMPATIBLE_VERSION, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("FAILURE=INCOMPATIBLE_VERSION"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains(
                "This invitation comes from a different Synesis version (compatibility 3; this computer has 2). "
                        + "This computer is older: update Synesis on this computer, then ask for a new invitation."));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("invalid or expired"));
    }
}
