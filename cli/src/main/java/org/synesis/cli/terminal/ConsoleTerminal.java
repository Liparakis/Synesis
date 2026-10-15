package org.synesis.cli.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;

/**
 * Console-backed terminal using explicitly supplied streams.
 *
 * @since 1.0
 */
public final class ConsoleTerminal implements Terminal {

    private final PrintWriter out;
    private final PrintWriter err;
    private final BufferedReader input;
    private final Charset outputCharset;

    /**
     * Creates a terminal connected to the current process streams.
     */
    public ConsoleTerminal() {
        this(System.out, System.err);
    }

    /**
     * Creates a terminal with injected streams for deterministic tests.
     *
     * @param out stdout stream
     * @param err stderr stream
     */
    public ConsoleTerminal(PrintStream out, PrintStream err) {
        this(out, err, System.in);
    }

    /**
     * Creates a console terminal with injected output and input streams.
     *
     * @param out   stdout stream
     * @param err   stderr stream
     * @param input stdin stream
     */
    public ConsoleTerminal(PrintStream out, PrintStream err, InputStream input) {
        this.outputCharset = out.charset();
        this.out = new PrintWriter(out, true);
        this.err = new PrintWriter(err, true);
        this.input = new BufferedReader(new InputStreamReader(input, Charset.defaultCharset()));
    }

    @Override
    public PrintWriter out() {
        return out;
    }

    @Override
    public PrintWriter err() {
        return err;
    }

    @Override
    public boolean canDisplay(String text) {
        return outputCharset.newEncoder().canEncode(text);
    }

    @Override
    public String readLine() throws IOException {
        return input.readLine();
    }
}
