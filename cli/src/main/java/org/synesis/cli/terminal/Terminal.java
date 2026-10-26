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
     * Reads a secret a person types at the console, with no echo, for the operator key's passphrase (ADR-0104). Only a
     * real terminal answers: never standard input that is a pipe or a file, never an environment variable, so a script
     * or an agent's command cannot supply it.
     *
     * @param prompt what to ask
     * @return the typed characters, which the caller clears, or {@code null} when no person is at a terminal
     * @throws IOException if the console cannot be read
     */
    default char[] readSecret(String prompt) throws IOException {
        java.io.Console console = System.console();
        return console == null || !console.isTerminal() ? null : console.readPassword("%s", prompt);
    }

    /**
     * Returns whether a person is at this terminal and may be asked a question: standard input and output are a
     * terminal and output was not forced to the machine form ({@code --porcelain}, {@code SYNESIS_OUTPUT=porcelain}).
     * A script, a pipe or a test never is, so a command that asks must otherwise refuse and name the flag that answers.
     *
     * @return {@code true} only when a prompt can be answered
     */
    default boolean interactive() {
        return false;
    }

    /**
     * Returns whether the output encoding can represent every character of {@code text}.
     *
     * <p>A legacy console code page cannot represent every symbol, so decorations must ask.</p>
     *
     * @param text characters a caller would like to print
     * @return {@code true} only when all of them can be encoded
     */
    default boolean canDisplay(String text) {
        return false;
    }
}
