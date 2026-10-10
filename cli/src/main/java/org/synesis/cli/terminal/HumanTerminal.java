package org.synesis.cli.terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents the stable {@code KEY=VALUE} status contract as aligned, labelled lines for a person
 * reading a terminal.
 *
 * <p>Commands keep emitting the machine contract; this terminal only changes how those lines are
 * shown. Each line is rendered as it arrives, so long-running commands still report progress
 * immediately. Anything that is not a status fact — usage text, QR codes, protocol frames, free
 * prose — passes through untouched, and the raw writers are never wrapped, so a stream used as a
 * protocol channel cannot be reformatted by accident.</p>
 */
public final class HumanTerminal implements Terminal {

    /**
     * Command-line flag that keeps the machine contract even on an interactive terminal.
     */
    static final String PORCELAIN_FLAG = "--porcelain";
    /**
     * Environment variable that selects {@code human} or {@code porcelain} output explicitly.
     */
    public static final String OUTPUT_VARIABLE = "SYNESIS_OUTPUT";

    private static final Pattern FACT = Pattern.compile("([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*)(?:=(.*))?", Pattern.DOTALL);
    private static final String RESULT_SUFFIX = "_RESULT";
    private static final int LABEL_WIDTH = 26;
    private static final Set<String> ACRONYMS = Set.of("ID", "IDS", "MCP", "URI", "URL", "QR", "UI", "SHA", "PID", "HTTP", "CLI", "TTL");
    private static final Map<Tone, Set<String>> TONE_WORDS = Map.of(
            Tone.GOOD, Set.of("SUCCESS", "PASSED", "OK", "INSTALLED", "BOUND", "VERIFIED", "ATTESTED", "ASSIGNED", "RECORDED", "COMPLETE", "COMPLETED", "HEALTHY", "READY", "ALLOW", "ALREADY_INITIALIZED"),
            Tone.CAUTION, Set.of("DEGRADED", "UNPROVEN", "REVIEW_REQUIRED", "NOT_COMPLETED", "NOT_INSTALLED", "UNKNOWN", "FALLBACK", "EXPERIMENTAL", "PARTIAL", "WAITING", "PENDING", "WORKSPACE_UNVERIFIED"),
            Tone.BAD, Set.of("CONFLICT", "FAILED", "FAILURE", "ERROR", "BLOCKED", "DENY", "DENIED", "REJECTED", "CRITICAL", "MALFORMED", "UNAVAILABLE", "INTERNAL"));

    private final Terminal delegate;
    private final boolean color;
    private final boolean symbols;

    /**
     * Wraps a terminal.
     *
     * @param delegate terminal that owns the real streams
     * @param color    whether ANSI colour sequences may be written
     */
    public HumanTerminal(Terminal delegate, boolean color) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.color = color;
        this.symbols = delegate.canDisplay("✓▲✗•");
    }

    /**
     * Decides whether an invocation should be presented for a person.
     *
     * <p>An explicit request always wins. Without one, only an interactive terminal gets the
     * readable form, so pipes, files, provider hooks, and existing scripts keep receiving the
     * machine contract without having to opt out.</p>
     *
     * @param arguments   process arguments
     * @param interactive whether standard output is attached to a terminal
     * @param configured  value of {@link #OUTPUT_VARIABLE}, or {@code null}
     * @return {@code true} when readable output should be used
     */
    public static boolean selected(List<String> arguments, boolean interactive, String configured) {
        if (arguments.contains(PORCELAIN_FLAG)) {
            return false;
        }
        String mode = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "porcelain" -> false;
            case "human" -> true;
            default -> interactive;
        };
    }

    /**
     * Decides whether colour is appropriate from the conventional environment signals.
     *
     * @param environment process environment
     * @return {@code true} when the terminal is known to interpret ANSI colour
     */
    public static boolean colorSupported(Map<String, String> environment) {
        if (environment.get("NO_COLOR") != null) {
            return false;
        }
        String term = environment.getOrDefault("TERM", "");
        return environment.get("WT_SESSION") != null || environment.get("TERM_PROGRAM") != null || "ON".equalsIgnoreCase(environment.get("ConEmuANSI")) || (!term.isBlank() && !"dumb".equals(term));
    }

    @Override
    public PrintWriter out() {
        return delegate.out();
    }

    @Override
    public PrintWriter err() {
        return delegate.err();
    }

    @Override
    public void stdout(String line) {
        delegate.stdout(render(line));
    }

    @Override
    public void stdoutRaw(String text) {
        delegate.stdoutRaw(text);
    }

    @Override
    public void stderr(String line) {
        delegate.stderr(render(line));
    }

    @Override
    public String readLine() throws IOException {
        return delegate.readLine();
    }

    @Override
    public int width() {
        return delegate.width();
    }

    @Override
    public boolean unicodeSupported() {
        return delegate.unicodeSupported();
    }

    String render(String line) {
        Matcher fact = line == null ? null : FACT.matcher(line);
        if (fact == null || !fact.matches()) {
            return line;
        }
        String key = fact.group(1);
        String value = fact.group(2);
        if (value == null) {
            // A bare key is a progress event, such as a completed onboarding step.
            return "  " + bullet() + " " + label(key);
        }
        Tone tone = tone(value);
        if (key.endsWith(RESULT_SUFFIX) && key.length() > RESULT_SUFFIX.length()) {
            String subject = label(key.substring(0, key.length() - RESULT_SUFFIX.length()));
            return paint(tone, marker(tone) + " " + subject + ": " + value);
        }
        String name = label(key);
        String padding = " ".repeat(Math.max(2, LABEL_WIDTH - name.length()));
        return "  " + name + padding + paint(tone, value);
    }

    private static String label(String key) {
        StringBuilder text = new StringBuilder();
        for (String word : key.split("_")) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            if (ACRONYMS.contains(word)) {
                text.append(word);
            } else {
                String lower = word.toLowerCase(Locale.ROOT);
                text.append(text.isEmpty() ? Character.toUpperCase(lower.charAt(0)) + lower.substring(1) : lower);
            }
        }
        return text.toString();
    }

    private static Tone tone(String value) {
        for (Map.Entry<Tone, Set<String>> words : TONE_WORDS.entrySet()) {
            if (words.getValue().contains(value)) {
                return words.getKey();
            }
        }
        return Tone.NEUTRAL;
    }

    private String marker(Tone tone) {
        return switch (tone) {
            case GOOD -> symbols ? "✓" : "[ok]";
            case CAUTION -> symbols ? "▲" : "[!]";
            case BAD -> symbols ? "✗" : "[x]";
            case NEUTRAL -> bullet();
        };
    }

    private String bullet() {
        return symbols ? "•" : "-";
    }

    private String paint(Tone tone, String text) {
        if (!color || tone == Tone.NEUTRAL) {
            return text;
        }
        String code = switch (tone) {
            case GOOD -> "32";
            case CAUTION -> "33";
            default -> "31";
        };
        return "\u001B[" + code + "m" + text + "\u001B[0m";
    }

    /**
     * How a status value should read at a glance.
     */
    private enum Tone {
        GOOD, CAUTION, BAD, NEUTRAL
    }
}
