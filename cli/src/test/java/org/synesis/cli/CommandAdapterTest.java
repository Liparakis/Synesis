package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.transport.OnboardingEvent;
import org.synesis.link.transport.OnboardingEventType;
import org.synesis.link.transport.OnboardingFailure;
import org.synesis.link.transport.OnboardingFailureCode;

/** Verifies adapter output and typed failure mapping with injected streams. */
final class CommandAdapterTest {
    @Test
    void statusRendererPreservesTheExactShareLink() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        String link = "synesis://join/SYN1-exact-link";
        new StatusRenderer(terminal).accept(new OnboardingEvent(OnboardingEventType.SHARE_LINK, link));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("SHARE_LINK=" + link));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("QR_RENDERED=COMPACT"));
        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void failureMappingUsesStableExitAndRedactedStderr() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        int exit = FailureMapper.map(new OnboardingFailure(OnboardingFailureCode.INVITE_INVALID,
                new IllegalStateException("secret link must not escape")), terminal);
        assertEquals(ExitCodes.INVITE_INVALID, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("FAILURE=INVITE_INVALID"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("invalid or expired"));
        assertTrue(!err.toString(StandardCharsets.UTF_8).contains("secret link"));
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }
}
