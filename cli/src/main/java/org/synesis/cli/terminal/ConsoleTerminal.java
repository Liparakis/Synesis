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

  private static final int DEFAULT_WIDTH = 120;
  private final PrintWriter out;
  private final PrintWriter err;
  private final BufferedReader input;
  private final int width;
  private final boolean unicodeSupported;

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
    Charset outputCharset = out.charset();
    this.out = new PrintWriter(out, true);
    this.err = new PrintWriter(err, true);
    this.input = new BufferedReader(new InputStreamReader(input, Charset.defaultCharset()));
    this.width = detectWidth();
    this.unicodeSupported = outputCharset.newEncoder()
        .canEncode("█▀▄");
  }

  private static int detectWidth() {
    String configured = System.getProperty("synesis.link.terminal.width");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("COLUMNS");
    }
    try {
      int value = configured == null ? DEFAULT_WIDTH : Integer.parseInt(configured);
      return value > 0 ? value : DEFAULT_WIDTH;
    } catch (NumberFormatException ignored) {
      return DEFAULT_WIDTH;
    }
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
  public int width() {
    return width;
  }

  @Override
  public boolean unicodeSupported() {
    return unicodeSupported;
  }

  @Override
  public String readLine() throws IOException {
    return input.readLine();
  }
}
