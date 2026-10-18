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

    /**
     * In the PC + VM run of 2026-10-18 (s5-run2) the acceptance build was first on {@code PATH} and an older build was
     * installed: the hooks ran the new build, the agents' MCP servers the installed one, which could not read the
     * new build's {@code REMERGE_SNAPSHOT_CREATED} and answered every call {@code SESSION_TERMINAL_STATE_UNAVAILABLE}.
     */
    @Test
    void theMcpServerRunsTheBuildTheHooksRun(@TempDir Path temp) throws Exception {
        Path localAppData = temp.resolve("LocalAppData");
        Path installedBin = Files.createDirectories(localAppData.resolve("Synesis").resolve("bin"));
        Files.createFile(installedBin.resolve("synesis.exe"));
        Path installedMcp = Files.createFile(installedBin.resolve("synesis-mcp.exe"));
        Path buildBin = Files.createDirectories(temp.resolve("build").resolve("bin"));
        Files.createFile(buildBin.resolve("synesis.cmd"));
        Path hooks = ProviderLauncherResolution.launcherIn(buildBin, true);

        assertEquals(hooks, ProviderLauncherResolution.mcpLauncher(hooks, null, true),
                "a build without a native MCP launcher serves MCP through its own CLI launcher");
        Path buildMcp = Files.createFile(buildBin.resolve("synesis-mcp.exe"));
        assertEquals(buildMcp.toAbsolutePath().normalize(), ProviderLauncherResolution.mcpLauncher(hooks, null, true));

        Path installedHooks = ProviderLauncherResolution.launcherIn(installedBin, true);
        assertEquals(installedMcp.toAbsolutePath().normalize(), ProviderLauncherResolution.mcpLauncher(installedHooks, null, true),
                "an installed launcher keeps its native MCP launcher");
        assertEquals(installedMcp.toAbsolutePath().normalize(), ProviderLauncherResolution.mcpLauncher(hooks, installedMcp.toString(), true),
                "an explicitly configured MCP launcher still wins");
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
