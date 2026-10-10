package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies which launcher a provider hook is pointed at within one installation directory.
 */
final class ProviderLauncherSelectionTest {

    @Test
    void windowsPrefersTheNativeLauncherAndFallsBackToTheScript(@TempDir Path bin) throws Exception {
        assertNull(ProviderLauncherResolution.launcherIn(bin, true));

        Path script = Files.createFile(bin.resolve("synesis.cmd"));
        assertEquals(script.toAbsolutePath().normalize(), ProviderLauncherResolution.launcherIn(bin, true), "an installation without the native launcher keeps working");

        Path nativeLauncher = Files.createFile(bin.resolve("synesis.exe"));
        assertEquals(nativeLauncher.toAbsolutePath().normalize(), ProviderLauncherResolution.launcherIn(bin, true));
    }

    @Test
    void aDirectoryNamedLikeALauncherIsNotALauncher(@TempDir Path bin) throws Exception {
        Files.createDirectory(bin.resolve("synesis.exe"));
        Path script = Files.createFile(bin.resolve("synesis.cmd"));

        assertEquals(script.toAbsolutePath().normalize(), ProviderLauncherResolution.launcherIn(bin, true));
    }

    @Test
    void otherPlatformsUseTheUnsuffixedLauncherOnly(@TempDir Path bin) throws Exception {
        Files.createFile(bin.resolve("synesis.exe"));
        Files.createFile(bin.resolve("synesis.cmd"));
        assertNull(ProviderLauncherResolution.launcherIn(bin, false));

        Path launcher = Files.createFile(bin.resolve("synesis"));
        assertEquals(launcher.toAbsolutePath().normalize(), ProviderLauncherResolution.launcherIn(bin, false));
    }
}
