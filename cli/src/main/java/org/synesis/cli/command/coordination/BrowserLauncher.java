package org.synesis.cli.command.coordination;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Small platform-neutral boundary for opening the user's default browser.
 */
public final class BrowserLauncher {

  private BrowserLauncher() {
  }

  /**
   * Attempts to open a URL without invoking a shell.
   *
   * @param uri local UI URL
   * @return {@code true} when the desktop accepted the request
   */
  public static boolean open(URI uri) {
    if (!isSupportedUri(uri)) {
      return false;
    }
    String osName = System.getProperty("os.name", "");
    DesktopAccess desktop = DesktopAccess.create();
    return open(uri, osName, desktop, BrowserLauncher::startProcess);
  }

  static boolean open(URI uri, String osName, DesktopAccess desktop,
      ProcessStarter processStarter) {
    if (!isSupportedUri(uri)) {
      return false;
    }
    try {
      if (desktop.isSupported() && desktop.browseSupported()) {
        desktop.browse(uri);
        return true;
      }
    } catch (SecurityException | IOException ignored) {
      // Linux may still have a standard opener even when the AWT bridge fails.
    }
    return isLinux(osName) && launchLinuxOpener(uri, processStarter);
  }

  private static boolean launchLinuxOpener(URI uri, ProcessStarter processStarter) {
    try {
      processStarter.start(List.of("xdg-open", uri.toString()));
      return true;
    } catch (IOException | SecurityException ignored) {
      return false;
    }
  }

  private static void startProcess(List<String> command) throws IOException {
    new ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  private static boolean isLinux(String osName) {
    return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
  }

  private static boolean isSupportedUri(URI uri) {
    if (uri == null || uri.getHost() == null || uri.getUserInfo() != null) {
      return false;
    }
    String scheme = uri.getScheme();
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  @FunctionalInterface
  interface ProcessStarter {
    void start(List<String> command) throws IOException;
  }

  interface DesktopAccess {
    boolean isSupported();

    boolean browseSupported();

    void browse(URI uri) throws IOException;

    static DesktopAccess create() {
      try {
        return new AwtDesktopAccess();
      } catch (HeadlessException | SecurityException ignored) {
        return new DesktopAccess() {
          @Override
          public boolean isSupported() {
            return false;
          }

          @Override
          public boolean browseSupported() {
            return false;
          }

          @Override
          public void browse(URI uri) {
            throw new UnsupportedOperationException("desktop browse unavailable");
          }
        };
      }
    }
  }

  private static final class AwtDesktopAccess implements DesktopAccess {
    private final boolean supported;
    private final Desktop desktop;

    private AwtDesktopAccess() {
      supported = Desktop.isDesktopSupported();
      desktop = supported ? Desktop.getDesktop() : null;
    }

    @Override
    public boolean isSupported() {
      return supported;
    }

    @Override
    public boolean browseSupported() {
      return supported && desktop.isSupported(Desktop.Action.BROWSE);
    }

    @Override
    public void browse(URI uri) throws IOException {
      Objects.requireNonNull(desktop, "desktop").browse(uri);
    }
  }
}
