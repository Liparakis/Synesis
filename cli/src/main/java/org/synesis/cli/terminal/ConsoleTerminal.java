package org.synesis.cli.terminal;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;

/**
 * Console-backed terminal using explicitly supplied streams.
 *
 * @since 1.0
 */
public final class ConsoleTerminal implements Terminal {
    private static final int DEFAULT_WIDTH = 120;
    private final PrintWriter out;
    private final PrintWriter err;
    private final int width;
    private final boolean unicodeSupported;

    /** Creates a terminal connected to the current process streams. */
    public ConsoleTerminal() { this(System.out, System.err); }

    /**
     * Creates a terminal with injected streams for deterministic tests.
     *
     * @param out stdout stream
     * @param err stderr stream
     */
    public ConsoleTerminal(PrintStream out, PrintStream err) {
        Charset outputCharset = out.charset();
        this.out = new PrintWriter(out, true);
        this.err = new PrintWriter(err, true);
        this.width = detectWidth();
        this.unicodeSupported = outputCharset.newEncoder().canEncode("█▀▄");
    }

    @Override public PrintWriter out() { return out; }
    @Override public PrintWriter err() { return err; }
    @Override public int width() { return width; }
    @Override public boolean unicodeSupported() { return unicodeSupported; }

    private static int detectWidth() {
        String configured = System.getProperty("synesis.link.terminal.width");
        if (configured == null || configured.isBlank()) configured = System.getenv("COLUMNS");
        try {
            int value = configured == null ? DEFAULT_WIDTH : Integer.parseInt(configured);
            return value > 0 ? value : DEFAULT_WIDTH;
        } catch (NumberFormatException ignored) {
            return DEFAULT_WIDTH;
        }
    }
}
