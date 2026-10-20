package org.synesis.cli.command.link;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.synesis.cli.daemon.CliChildJvm;

/**
 * Checks the Windows Firewall rule the installer adds so that another computer can dial this one (ADR-0096). The rule
 * names one version's {@code java.exe}, and an update that does not add it again leaves it naming the old one, which
 * silently stops this computer from hosting. Reading the rule needs no administrator rights; changing it does, so the
 * check only says what to run.
 */
public final class LinkFirewallRule {

    static final String NAME = "Synesis Link";
    // netsh translates its labels, so a program is found by the shape of a path.
    private static final Pattern PROGRAM = Pattern.compile("[A-Za-z]:\\\\.*\\S");
    private static final String ANSWER = "the other computer can still connect if you paste the answer link it prints into 'synesis link answer' here";

    /**
     * Shows the rules named {@link #NAME}.
     */
    @FunctionalInterface
    interface Show {

        /**
         * @return netsh's description of the rules, or empty when there is none
         * @throws IOException when the rules cannot be read
         */
        Optional<String> rules() throws IOException;
    }

    private LinkFirewallRule() {
    }

    /**
     * What {@code synesis doctor} reports about the rule.
     *
     * @return {@code MISSING} or {@code STALE}, or empty when the rule is right or cannot be read
     */
    public static Optional<String> problem() {
        return notes().stream().filter(line -> line.startsWith("LINK_FIREWALL=")).map(line -> line.substring("LINK_FIREWALL=".length())).findFirst();
    }

    /**
     * @return the lines to print before this computer invites another, empty when it is not Windows, the rule names
     *     the runtime this command runs on, or the rules cannot be read
     */
    static List<String> notes() {
        if (!CliChildJvm.onWindows()) {
            return List.of();
        }
        return notes(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), LinkFirewallRule::netsh);
    }

    /**
     * @param program the Windows path of the {@code java.exe} that listens
     * @param show    reads the rules
     * @return the lines to print, empty when the rule names {@code program} or the rules cannot be read
     */
    static List<String> notes(String program, Show show) {
        Optional<String> rules;
        try {
            rules = show.rules();
        } catch (IOException unreadable) {
            return List.of();
        }
        if (rules.isEmpty()) {
            return List.of("LINK_FIREWALL=MISSING", "NOTE=Windows Firewall has no \"" + NAME + "\" rule, so the other computer cannot dial this one; "
                + ANSWER + ". To let it dial, run the Synesis installer with \"Let other computers connect to this one\" ticked, or in an administrator terminal: "
                + "netsh advfirewall firewall add rule name=\"" + NAME + "\" dir=in action=allow protocol=UDP profile=any program=\"" + program + "\"");
        }
        List<String> programs = PROGRAM.matcher(rules.get()).results().map(MatchResult::group).toList();
        // Compared from the version folder on: netsh prints the folders above in the console's code page, or as short
        // 8.3 names, but a version folder's name is ASCII.
        String[] names = program.split("\\\\");
        String tail = ("\\" + String.join("\\", Arrays.copyOfRange(names, Math.max(0, names.length - 4), names.length))).toLowerCase(Locale.ROOT);
        if (programs.isEmpty() || programs.stream().anyMatch(named -> named.toLowerCase(Locale.ROOT).endsWith(tail))) {
            return List.of();
        }
        return List.of("LINK_FIREWALL=STALE", "LINK_FIREWALL_RULE_PROGRAM=" + programs.getFirst(),
            "NOTE=the Windows Firewall rule \"" + NAME + "\" names another version's java.exe, so the other computer cannot dial this one; "
                + ANSWER + ". To fix it, run the Synesis installer again with \"Let other computers connect to this one\" ticked, or in an administrator terminal: "
                + "netsh advfirewall firewall set rule name=\"" + NAME + "\" new program=\"" + program + "\"");
    }

    private static Optional<String> netsh() throws IOException {
        Process process = new ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=" + NAME, "verbose")
            .redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        try {
            // netsh fails when no rule matches.
            return process.waitFor() == 0 ? Optional.of(output) : Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the firewall rule", interrupted);
        }
    }
}
