package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Pins the files Synesis writes into a fresh project: the hook files of each provider install, and that it
 * writes no instruction file (no {@code AGENTS.md}, no rule file).
 *
 * <p>The launcher path is machine specific and is masked as {@code <LAUNCHER>}.
 */
class ProviderManagedFilesFormatTest {

  /**
   * The quoted launcher of a hook command, as JSON text: a Windows path has its backslashes
   * doubled and a Unix path has none. Which launcher is resolved depends on the machine.
   */
  private static final Pattern LAUNCHER_PATH =
      Pattern.compile("\\\\\"(?:[^\"\\\\]|\\\\\\\\)+\\\\\" hook ");

  /** The profile directory argument of the Claude hook command. */
  private static final Pattern PROFILE_PATH = Pattern.compile("(--profile \\\\\")(.*?)(\\\\\")");

  @TempDir Path temp;

  private String previousHome;
  private String previousLauncher;
  private String previousMcpLauncher;
  private Path launcher;
  private ProjectLocation location;
  private Path root;

  @BeforeEach
  void isolate() throws Exception {
    previousHome = System.getProperty("user.home");
    previousLauncher = System.getProperty("synesis.launcher");
    previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
    System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
    launcher = Files.createFile(temp.resolve("launcher.cmd"));
    System.setProperty("synesis.launcher", launcher.toString());
    System.setProperty("synesis.mcp.launcher", launcher.toString());
    root = Files.createDirectories(temp.resolve("project"));
    location = new ProjectApplicationService().init(root).location();
  }

  @AfterEach
  void restore() {
    restore("user.home", previousHome);
    restore("synesis.launcher", previousLauncher);
    restore("synesis.mcp.launcher", previousMcpLauncher);
  }

  @Test
  void claudeSettingsFile() throws IOException {
    new ProviderApplicationService().install(location, "claude");

    assertEquals(
        """
        {"hooks":{"PreToolUse":[{"id":"synesis-claude-code","matcher":"Edit|Write|Bash|PowerShell","hooks":[{"type":"command","command":"\\"<LAUNCHER>\\" hook claude --profile \\"<PROFILE>\\""}]}],"Stop":[{"id":"synesis-claude-code-stop","hooks":[{"type":"command","command":"\\"<LAUNCHER>\\" hook claude --profile \\"<PROFILE>\\"","timeout":660}]}]}}"""
            + System.lineSeparator(),
        masked(root.resolve(".claude/settings.json")));
  }

  @Test
  void codexHooksFile() throws IOException {
    new ProviderApplicationService().install(location, "codex");

    assertEquals(
        """
        {"hooks":{"PreToolUse":[{"matcher":"^apply_patch$","hooks":[{"type":"command","command":"\\"<LAUNCHER>\\" hook codex","commandWindows":"cmd.exe /d /s /c \\"\\"<LAUNCHER>\\" hook codex\\""}]}],"SessionStart":[{"id":"synesis-codex-session","matcher":"startup|resume","hooks":[{"type":"command","command":"\\"<LAUNCHER>\\" hook codex","commandWindows":"cmd.exe /d /s /c \\"\\"<LAUNCHER>\\" hook codex\\""}]}]}}"""
            + System.lineSeparator(),
        masked(root.resolve(".codex/hooks.json")));
  }

  /** The installed manual is the one place for agent instructions (ADR-0103 decision 9). */
  @Test
  void initAndInstallWriteNoInstructionFileIntoTheProject() throws IOException {
    new ProviderApplicationService().install(location, "claude");
    new ProviderApplicationService().install(location, "codex");

    assertFalse(Files.exists(root.resolve("AGENTS.md")));
    assertFalse(Files.exists(root.resolve(".claude/rules")));
  }

  private String masked(Path file) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    return LAUNCHER_PATH
        .matcher(PROFILE_PATH.matcher(text).replaceAll("$1<PROFILE>$3"))
        .replaceAll(Matcher.quoteReplacement("\\\"<LAUNCHER>\\\" hook "));
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
