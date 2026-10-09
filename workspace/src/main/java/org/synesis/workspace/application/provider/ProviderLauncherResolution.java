package org.synesis.workspace.application.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Decides which Synesis executable provider hooks and MCP entries invoke. A launcher on the
 * {@code PATH} wins, then an explicitly configured one, then the per-user install location, and the
 * Windows native launcher is preferred over the script of the same name. The answer is written into
 * files outside the repository, so it must not depend on the caller's working directory. It is
 * separate from {@link ProviderApplicationService} so the search order can be read and tested on
 * its own.
 */
final class ProviderLauncherResolution {

    private ProviderLauncherResolution() {
    }

    private static final String NATIVE_LAUNCHER = "synesis.exe";
    private static final String SCRIPT_LAUNCHER = "synesis.cmd";
    private static final String UNIX_LAUNCHER = "synesis";

    static Path launcher() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                Path candidate = launcherIn(Path.of(entry), isWindows());
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        String configured = System.getProperty("synesis.launcher", System.getenv("SYNESIS_LAUNCHER"));
        Path configuredPath = configuredPath(configured);
        if (configuredPath != null && Files.isRegularFile(configuredPath)) {
            return configuredPath.toAbsolutePath().normalize();
        }
        Path stableBin = stableLauncher(SCRIPT_LAUNCHER).getParent();
        Path installed = launcherIn(stableBin, isWindows());
        return installed != null ? installed : stableLauncher(isWindows() ? SCRIPT_LAUNCHER : UNIX_LAUNCHER);
    }

    /**
     * Returns the launcher a provider hook should run from one directory, or {@code null} when the
     * directory has none.
     *
     * <p>On Windows the native launcher is preferred over the script of the same name: a hook runs
     * on every intercepted edit, and the script starts two shells and a PowerShell host before the
     * runtime. The script remains the answer for installations that predate the native launcher.</p>
     *
     * @param directory directory that may hold Synesis launchers
     * @param windows   whether Windows launcher names apply
     * @return absolute launcher path, or {@code null}
     */
    static Path launcherIn(Path directory, boolean windows) {
        for (String name : windows ? List.of(NATIVE_LAUNCHER, SCRIPT_LAUNCHER) : List.of(UNIX_LAUNCHER)) {
            Path candidate = directory.resolve(name).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * Parses an optional configured path.
     *
     * @param configured configured path text
     * @return path, or {@code null} when absent
     */
    private static Path configuredPath(String configured) {
        return configured == null ? null : Path.of(configured);
    }

    private static Path stableLauncher(String executable) {
        String base;
        if (isWindows()) {
            base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isBlank()) {
                base = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
            }
        } else if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            base = Path.of(System.getProperty("user.home"), "Library", "Application Support").toString();
        } else {
            base = System.getenv("XDG_DATA_HOME");
            if (base == null || base.isBlank()) {
                base = Path.of(System.getProperty("user.home"), ".local", "share").toString();
            }
        }
        return Path.of(base, "Synesis", "bin", executable).toAbsolutePath().normalize();
    }

    /**
     * Resolves the native stdio MCP launcher when the local distribution provides it, falling back to
     * the CLI launcher for development installs.
     *
     * @return native MCP launcher or development fallback
     */
    static Path mcpLauncherPath() {
        String executable = isWindows() ? "synesis-mcp.exe" : "synesis-mcp";
        String configured = System.getProperty("synesis.mcp.launcher", System.getenv("SYNESIS_MCP_LAUNCHER"));
        Path configuredPath = configuredPath(configured);
        if (configuredPath != null && Files.isRegularFile(configuredPath)) {
            return configuredPath.toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (isWindows() && localAppData != null && !localAppData.isBlank()) {
            Path installed = Path.of(localAppData, "Synesis", "bin", executable);
            if (Files.isRegularFile(installed)) {
                return installed.toAbsolutePath().normalize();
            }
        }
        Path cli = launcher();
        Path candidate = cli.getParent() == null ? Path.of(executable) : cli.getParent().resolve(executable);
        return Files.isRegularFile(candidate) ? candidate.toAbsolutePath().normalize() : cli;
    }
}
