package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies browser opening preference, Linux fallback, and URI argument safety.
 */
final class BrowserLauncherTest {

  private static final URI UI_URI = URI.create(
      "http://127.0.0.1:43123/#bootstrap=abc%26encoded%20value");

  @Test
  void prefersSupportedDesktopBrowse() {
    RecordingDesktop desktop = new RecordingDesktop(true, true);
    List<List<String>> commands = new ArrayList<>();

    assertTrue(BrowserLauncher.open(UI_URI, "Linux", desktop, commands::add));
    assertEquals(List.of(UI_URI), desktop.browsedUris);
    assertTrue(commands.isEmpty());
  }

  @Test
  void fallsBackToXdgOpenWhenLinuxDesktopBrowseIsUnsupported() {
    RecordingDesktop desktop = new RecordingDesktop(true, false);
    List<List<String>> commands = new ArrayList<>();

    assertTrue(BrowserLauncher.open(UI_URI, "Linux", desktop, commands::add));
    assertEquals(List.of(List.of("xdg-open", UI_URI.toString())), commands);
    assertTrue(desktop.browsedUris.isEmpty());
  }

  @Test
  void fallsBackWhenDesktopIsEntirelyUnsupportedOnLinux() {
    RecordingDesktop desktop = new RecordingDesktop(false, false);
    List<List<String>> commands = new ArrayList<>();

    assertTrue(BrowserLauncher.open(UI_URI, "Linux", desktop, commands::add));
    assertEquals(List.of(List.of("xdg-open", UI_URI.toString())), commands);
  }

  @Test
  void doesNotChangeUnsupportedNonLinuxBehavior() {
    RecordingDesktop desktop = new RecordingDesktop(false, false);
    List<List<String>> commands = new ArrayList<>();

    assertFalse(BrowserLauncher.open(UI_URI, "Windows 11", desktop, commands::add));
    assertTrue(commands.isEmpty());
  }

  @Test
  void returnsFailureWhenLinuxOpenerCannotStart() {
    RecordingDesktop desktop = new RecordingDesktop(false, false);
    BrowserLauncher.ProcessStarter failingStarter = command -> {
      throw new IOException("xdg-open unavailable");
    };

    assertFalse(BrowserLauncher.open(UI_URI, "Linux", desktop, failingStarter));
  }

  @Test
  void rejectsUnsupportedUriBeforeStartingProcess() {
    RecordingDesktop desktop = new RecordingDesktop(false, false);
    List<List<String>> commands = new ArrayList<>();

    assertFalse(BrowserLauncher.open(URI.create("synesis://join/SLO1-test"), "Linux", desktop,
        commands::add));
    assertFalse(BrowserLauncher.open(URI.create("file:///tmp/bootstrap"), "Linux", desktop,
        commands::add));
    assertTrue(commands.isEmpty());
  }

  @Test
  void fallsBackAfterDesktopBrowseThrowsOnLinux() {
    RecordingDesktop desktop = new RecordingDesktop(true, true) {
      @Override
      public void browse(URI uri) throws IOException {
        throw new IOException("desktop bridge unavailable");
      }
    };
    List<List<String>> commands = new ArrayList<>();

    assertTrue(BrowserLauncher.open(UI_URI, "Linux", desktop, commands::add));
    assertEquals(List.of(List.of("xdg-open", UI_URI.toString())), commands);
  }

  private static class RecordingDesktop implements BrowserLauncher.DesktopAccess {
    private final boolean supported;
    private final boolean browseSupported;
    private final List<URI> browsedUris = new ArrayList<>();

    private RecordingDesktop(boolean supported, boolean browseSupported) {
      this.supported = supported;
      this.browseSupported = browseSupported;
    }

    @Override
    public boolean isSupported() {
      return supported;
    }

    @Override
    public boolean browseSupported() {
      return browseSupported;
    }

    @Override
    public void browse(URI uri) throws IOException {
      browsedUris.add(uri);
    }
  }
}
