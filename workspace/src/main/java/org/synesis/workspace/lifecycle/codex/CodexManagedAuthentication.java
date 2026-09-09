package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Determines whether Codex authentication can be shared safely with an isolated managed home
 * without copying a long-lived credential.
 */
public final class CodexManagedAuthentication {

  private CodexManagedAuthentication() {
  }

  /**
   * Returns the architecture-aware strategy for a managed runtime mode.
   *
   * <p>Normal-home mode deliberately does not inspect or classify provider
   * credential files; Codex owns that authentication boundary.</p>
   *
   * @param mode          managed runtime-home mode
   * @param userCodexHome normal provider home
   * @return strategy classification
   * @throws IOException when isolated-home inspection cannot read config
   */
  public static Strategy forMode(ManagedCodexRuntimeMode mode, Path userCodexHome)
      throws IOException {
    if (mode == ManagedCodexRuntimeMode.NORMAL_PROVIDER_HOME_MANAGED) {
      return Strategy.NORMAL_PROVIDER_HOME;
    }
    return inspect(userCodexHome);
  }

  /**
   * Inspects the user's Codex configuration without reading credential contents.
   *
   * @param userCodexHome normal user Codex home
   * @return safe strategy classification
   * @throws IOException when configuration cannot be read
   */
  public static Strategy inspect(Path userCodexHome) throws IOException {
    if (userCodexHome == null || !Files.isDirectory(userCodexHome)) {
      return Strategy.UNAVAILABLE;
    }
    Path config = userCodexHome.resolve("config.toml");
    if (Files.isRegularFile(config)) {
      String text = Files.readString(config);
      for (String line : text.split("\\R")) {
        String normalized = line.trim()
            .toLowerCase(Locale.ROOT);
        if (normalized.startsWith("cli_auth_credentials_store") && normalized.contains("keyring")) {
          return Strategy.SHARED_KEYRING;
        }
      }
    }
    return Files.isRegularFile(userCodexHome.resolve("auth.json"))
        ? Strategy.UNSAFE_FILE_AUTH : Strategy.UNAVAILABLE;
  }

  /**
   * Resolves the normal local Codex home.
   *
   * @return normal Codex home
   */
  public static Path userHome() {
    String configured = System.getenv("CODEX_HOME");
    return configured == null || configured.isBlank()
        ? Path.of(System.getProperty("user.home"), ".codex")
          .toAbsolutePath()
          .normalize()
        : Path.of(configured)
          .toAbsolutePath()
          .normalize();
  }

  /**
   * Supported managed authentication choices.
   */
  public enum Strategy {
    /**
     * The managed home uses the user's provider keyring entry.
     */
    SHARED_KEYRING,
    /**
     * The provider reads credentials from its own normal home.
     */
    NORMAL_PROVIDER_HOME,
    /**
     * A file credential was found but cannot be safely copied in v1.
     */
    UNSAFE_FILE_AUTH,
    /**
     * No supported authentication evidence was found.
     */
    UNAVAILABLE
  }
}
