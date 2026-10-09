package org.synesis.cli.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Verifies that readable output is a presentation of the machine contract, never a change to it.
 */
final class HumanTerminalTest {

    @Test
    void statusFactsBecomeAlignedLabelsWhileValuesStayVerbatim() {
        Capture capture = new Capture(StandardCharsets.US_ASCII);
        HumanTerminal terminal = new HumanTerminal(capture.terminal, false);

        terminal.stdout("PROJECT_ROOT=C:\\work\\demo");
        terminal.stdout("PROVIDER_1_ID=codex");
        terminal.stdout("MCP_HEALTH=PASSED");

        assertEquals(List.of("  Project root              C:\\work\\demo", "  Provider 1 ID             codex", "  MCP health                PASSED"), capture.outLines());
    }

    @Test
    void resultFactsLeadWithAnOutcomeMarker() {
        Capture ascii = new Capture(StandardCharsets.US_ASCII);
        HumanTerminal plain = new HumanTerminal(ascii.terminal, false);
        plain.stdout("INIT_RESULT=SUCCESS");
        plain.stdout("DOCTOR_RESULT=DEGRADED");
        plain.stdout("INIT_RESULT=CONFLICT");
        plain.stdout("SCAN_RESULT=NO_OPINION");
        assertEquals(List.of("[ok] Init: SUCCESS", "[!] Doctor: DEGRADED", "[x] Init: CONFLICT", "- Scan: NO_OPINION"), ascii.outLines());

        Capture unicode = new Capture(StandardCharsets.UTF_8);
        new HumanTerminal(unicode.terminal, true).stdout("INIT_RESULT=SUCCESS");
        assertEquals(List.of("\u001B[32m\u2713 Init: SUCCESS\u001B[0m"), unicode.outLines());
    }

    @Test
    void progressEventsAndErrorsAreLabelledOnTheirOwnStreams() {
        Capture capture = new Capture(StandardCharsets.US_ASCII);
        HumanTerminal terminal = new HumanTerminal(capture.terminal, false);

        terminal.stdout("IDENTITY_CREATED");
        terminal.stderr("ERROR_CODE=CONTROL_CHECKOUT_DIRTY");
        terminal.stderr("REASON=Managed baseline transaction failed: a.txt");

        assertEquals(List.of("  - Identity created"), capture.outLines());
        assertEquals(List.of("  Error code                CONTROL_CHECKOUT_DIRTY", "  Reason                    Managed baseline transaction failed: a.txt"), capture.errLines());
    }

    @Test
    void anythingThatIsNotAStatusFactPassesThroughUnchanged() {
        Capture capture = new Capture(StandardCharsets.US_ASCII);
        HumanTerminal terminal = new HumanTerminal(capture.terminal, true);

        terminal.stdout("Usage: synesis [-hV] [COMMAND]");
        terminal.stdout("{\"jsonrpc\":\"2.0\",\"id\":1}");
        terminal.stdout("lower_case=value");
        terminal.stdout("");
        terminal.stdoutRaw("RAW_FRAME=untouched\n");
        terminal.out().println("WRITER_LINE=untouched");
        terminal.out().flush();

        assertEquals(List.of("Usage: synesis [-hV] [COMMAND]", "{\"jsonrpc\":\"2.0\",\"id\":1}", "lower_case=value", "", "RAW_FRAME=untouched", "WRITER_LINE=untouched"), capture.outLines());
    }

    @Test
    void valuesContainingEqualsSignsAndLineBreaksAreKeptWhole() {
        Capture capture = new Capture(StandardCharsets.US_ASCII);
        HumanTerminal terminal = new HumanTerminal(capture.terminal, false);

        terminal.stdout("NEXT_COMMAND=synesis join --invite=abc=");
        terminal.stdout("EMPTY_VALUE=");

        assertEquals(List.of("  Next command              synesis join --invite=abc=", "  Empty value               "), capture.outLines());
    }

    @Test
    void readableOutputIsOnlyChosenForAnInteractiveTerminalUnlessRequested() {
        assertTrue(HumanTerminal.selected(List.of("init"), true, null));
        assertFalse(HumanTerminal.selected(List.of("init"), false, null), "pipes and scripts keep the machine contract by default");
        assertFalse(HumanTerminal.selected(List.of("init", "--porcelain"), true, null));
        assertFalse(HumanTerminal.selected(List.of("init"), true, "porcelain"));
        assertTrue(HumanTerminal.selected(List.of("init"), false, " Human "));
        assertFalse(HumanTerminal.selected(List.of("init", "--porcelain"), false, "human"), "the flag outranks the environment");
        assertTrue(HumanTerminal.selected(List.of("init"), true, "unrecognised"));
    }

    @Test
    void colourFollowsTheConventionalEnvironmentSignals() {
        assertTrue(HumanTerminal.colorSupported(Map.of("WT_SESSION", "1")));
        assertTrue(HumanTerminal.colorSupported(Map.of("TERM", "xterm-256color")));
        assertFalse(HumanTerminal.colorSupported(Map.of("TERM", "dumb")));
        assertFalse(HumanTerminal.colorSupported(Map.of()));
        assertFalse(HumanTerminal.colorSupported(Map.of("WT_SESSION", "1", "NO_COLOR", "")));
    }

    private static final class Capture {

        private final Charset charset;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final ConsoleTerminal terminal;

        private Capture(Charset charset) {
            this.charset = charset;
            this.terminal = new ConsoleTerminal(new PrintStream(out, true, charset), new PrintStream(err, true, charset));
        }

        private List<String> outLines() {
            return lines(out);
        }

        private List<String> errLines() {
            return lines(err);
        }

        private List<String> lines(ByteArrayOutputStream stream) {
            String text = stream.toString(charset);
            return text.isEmpty() ? List.of() : List.of(text.split("\\R", -1)).subList(0, text.split("\\R", -1).length - 1);
        }
    }
}
