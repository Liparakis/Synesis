package org.synesis.cli.command.coordination;

import java.awt.*;
import java.net.URI;

/**
 * Small platform-neutral boundary for opening the user's default browser.
 */
final class BrowserLauncher {

  private BrowserLauncher() {
  }

  /**
   * Attempts to open a URL without invoking a shell.
   *
   * @param uri local UI URL
   * @return {@code true} when the desktop accepted the request
   */
  static boolean open(URI uri) {
    try {
      if (!Desktop.isDesktopSupported()) {
        return false;
      }
      Desktop desktop = Desktop.getDesktop();
      if (!desktop.isSupported(Desktop.Action.BROWSE)) {
        return false;
      }
      desktop.browse(uri);
      return true;
    } catch (HeadlessException | SecurityException | java.io.IOException failure) {
      return false;
    }
  }
}
