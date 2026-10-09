package org.synesis.cli.terminal;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Narrow terminal boundary used by commands and renderers.
 *
 * @since 1.0
 */
@SuppressWarnings("resource")
public interface Terminal {

    /**
     * Returns the stdout writer owned by this terminal.
     *
     * @return stdout writer
     */
    PrintWriter out();

    /**
     * Returns the stderr writer owned by this terminal.
     *
     * @return stderr writer
     */
    PrintWriter err();

    /**
     * Writes one line to stdout.
     *
     * @param line line content
     */
    default void stdout(String line) {
        out().println(line);
        out().flush();
    }

    /**
     * Writes stdout text without adding a line.
     *
     * @param text text content
     */
    default void stdoutRaw(String text) {
        out().print(text);
        out().flush();
    }

    /**
     * Writes one line to stderr.
     *
     * @param line line content
     */
    default void stderr(String line) {
        err().println(line);
        err().flush();
    }

    /**
     * Reads one line from the operator input channel.
     *
     * @return next line, or {@code null} when input is closed
     * @throws IOException if the input channel cannot be read
     */
    String readLine() throws IOException;

    /**
     * Returns the detected terminal width.
     *
     * @return width in columns, or a bounded default
     */
    int width();

    /**
     * Reports whether compact QR glyphs are supported by the output charset.
     *
     * @return true when the glyphs can be encoded
     */
    boolean unicodeSupported();

    /**
     * Returns whether the output encoding can represent every character of {@code text}.
     *
     * <p>Block-drawing support, which {@link #unicodeSupported()} reports, does not imply that
     * other symbols survive a legacy console code page, so decorations must ask for themselves.</p>
     *
     * @param text characters a caller would like to print
     * @return {@code true} only when all of them can be encoded
     */
    default boolean canDisplay(String text) {
        return false;
    }
}
