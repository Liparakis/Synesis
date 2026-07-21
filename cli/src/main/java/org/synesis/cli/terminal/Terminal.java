package org.synesis.cli.terminal;

import java.io.PrintWriter;

/**
 * Narrow terminal boundary used by commands and renderers.
 *
 * @since 1.0
 */
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
}
