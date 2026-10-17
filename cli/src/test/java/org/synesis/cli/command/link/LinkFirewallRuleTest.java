package org.synesis.cli.command.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Before this computer invites another, the Windows Firewall rule is checked against the runtime that listens
 * (ADR-0096): a missing rule or one left naming an earlier version's {@code java.exe} is reported with the command that
 * fixes it.
 */
class LinkFirewallRuleTest {

    private static final String PROGRAM = "C:\\Users\\Λίπαρος\\AppData\\Local\\Synesis\\versions\\0.1.0-b-2222\\runtime\\bin\\java.exe";

    private static String rule(String program) {
        // netsh's labels are translated; these are German.
        return "\r\nRegelname:                            Synesis Link\r\n"
            + "----------------------------------------------------------------------\r\n"
            + "Programm:                             " + program + "   \r\n"
            + "Aktion:                               Zulassen\r\nOK.\r\n";
    }

    private static List<String> notes(String rules) {
        return LinkFirewallRule.notes(PROGRAM, () -> Optional.ofNullable(rules));
    }

    @Test
    void aRuleThatNamesAnEarlierVersionIsReportedWithTheCommandThatPointsItAtThisOne() {
        String old = "C:\\Users\\Λίπαρος\\AppData\\Local\\Synesis\\versions\\0.1.0-a-1111\\runtime\\bin\\java.exe";

        List<String> notes = notes(rule(old));

        assertEquals("LINK_FIREWALL=STALE", notes.get(0), notes.toString());
        assertEquals("LINK_FIREWALL_RULE_PROGRAM=" + old, notes.get(1), notes.toString());
        assertTrue(notes.get(2).startsWith("NOTE=the Windows Firewall rule \"Synesis Link\" names another version's java.exe"), notes.toString());
        assertTrue(notes.get(2).contains("'synesis link answer'"), notes.toString());
        assertTrue(notes.get(2).endsWith("netsh advfirewall firewall set rule name=\"Synesis Link\" new program=\"" + PROGRAM + "\""), notes.toString());
    }

    @Test
    void noRuleIsReportedWithTheCommandThatAddsOne() {
        List<String> notes = notes(null);

        assertEquals("LINK_FIREWALL=MISSING", notes.get(0), notes.toString());
        assertTrue(notes.get(1).endsWith("netsh advfirewall firewall add rule name=\"Synesis Link\" dir=in action=allow protocol=UDP profile=any program=\"" + PROGRAM + "\""), notes.toString());
    }

    @Test
    void aRuleForThisRuntimeIsCurrentHoweverNetshSpellsTheFoldersAboveTheVersion() {
        assertEquals(List.of(), notes(rule("C:\\Users\\????~1\\APPDATA\\LOCAL\\SYNESIS\\VERSIONS\\0.1.0-B-2222\\RUNTIME\\BIN\\JAVA.EXE")));
    }

    @Test
    void nothingIsClaimedWhenTheRulesCannotBeReadOrNameNoProgram() {
        assertEquals(List.of(), LinkFirewallRule.notes(PROGRAM, () -> {
            throw new IOException("netsh is missing");
        }));
        assertEquals(List.of(), notes(rule("Any")));
    }
}
