package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.provider.ProviderRegistry;

/**
 * Pins the exact bytes of every write path of a provider hook file: install into the control
 * project, the copy written into a lane worktree, and uninstall, each on a fresh file and on a file
 * that already holds user hooks.
 */
class ProviderHookConfigurationFormatTest {

  private static final String USER_PRE_TOOL =
      "{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo user\"}]}";
  private static final String USER_SESSION =
      "{\"matcher\":\"startup\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo session\"}]}";

  @TempDir Path temp;

  private String previousHome;
  private String previousLauncher;
  private String previousMcpLauncher;
  private ProjectLocation location;
  private Path root;
  private Path launcher;

  @BeforeEach
  void isolate() throws Exception {
    previousHome = System.getProperty("user.home");
    previousLauncher = System.getProperty("synesis.launcher");
    previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
    System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
    Path file = Files.createFile(temp.resolve("launcher.cmd"));
    System.setProperty("synesis.launcher", file.toString());
    System.setProperty("synesis.mcp.launcher", file.toString());
    root = Files.createDirectories(temp.resolve("project"));
    location = new ProjectApplicationService().init(root).location();
    launcher = new ProviderApplicationService().launcherPath();
  }

  @AfterEach
  void restore() {
    restore("user.home", previousHome);
    restore("synesis.launcher", previousLauncher);
    restore("synesis.mcp.launcher", previousMcpLauncher);
  }

  @Test
  void claudeInstallOnUserHooks() throws IOException {
    Path settings = root.resolve(".claude/settings.json");
    write(
        settings,
        "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + "],\"SessionStart\":["
            + USER_SESSION
            + "],\"PostToolUse\":[]}}");

    new ProviderApplicationService().install(location, "claude");

    assertEquals(
        "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + ","
            + claudeHook()
            + "],\"SessionStart\":["
            + USER_SESSION
            + "],\"PostToolUse\":[]}}"
            + System.lineSeparator(),
        read(settings));
  }

  @Test
  void claudeInstallDropsEmptySessionStart() throws IOException {
    Path settings = root.resolve(".claude/settings.json");
    write(settings, "{\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "],\"SessionStart\":[]}}");

    new ProviderApplicationService().install(location, "claude");

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "," + claudeHook() + "]}}"
            + System.lineSeparator(),
        read(settings));
  }

  @Test
  void claudeWorktreeCopyOnFreshFile() throws IOException {
    Path worktree = Files.createDirectories(temp.resolve("lane-fresh"));

    ProviderHookConfiguration.materializeHook(
        worktree, ProviderRegistry.find("claude"), launcher, location.profile());

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":[" + claudeHook() + "],\"SessionStart\":[]}}"
            + System.lineSeparator(),
        read(worktree.resolve(".claude/settings.json")));
  }

  @Test
  void claudeWorktreeCopyOnUserHooks() throws IOException {
    Path worktree = Files.createDirectories(temp.resolve("lane-user"));
    write(
        worktree.resolve(".claude/settings.json"),
        "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + "],\"SessionStart\":["
            + USER_SESSION
            + "]}}");

    ProviderHookConfiguration.materializeHook(
        worktree, ProviderRegistry.find("claude"), launcher, location.profile());

    assertEquals(
        "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + ","
            + claudeHook()
            + "],\"SessionStart\":["
            + USER_SESSION
            + "]}}"
            + System.lineSeparator(),
        read(worktree.resolve(".claude/settings.json")));
  }

  @Test
  void claudeWorktreeCopyReplacesItsOwnEntryInPlaceOfDuplicating() throws IOException {
    Path worktree = Files.createDirectories(temp.resolve("lane-twice"));
    var claude = ProviderRegistry.find("claude");

    ProviderHookConfiguration.materializeHook(worktree, claude, launcher, location.profile());
    ProviderHookConfiguration.materializeHook(worktree, claude, launcher, location.profile());

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":[" + claudeHook() + "],\"SessionStart\":[]}}"
            + System.lineSeparator(),
        read(worktree.resolve(".claude/settings.json")));
  }

  @Test
  void claudeUninstallLeavesUserHooks() throws IOException {
    Path settings = root.resolve(".claude/settings.json");
    String user =
        "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + "],\"SessionStart\":["
            + USER_SESSION
            + "]}}";
    write(settings, user);
    var service = new ProviderApplicationService();
    service.install(location, "claude");

    service.uninstall(location, "claude");

    assertEquals(user + System.lineSeparator(), read(settings));
  }

  @Test
  void claudeUninstallDropsEmptiedKeys() throws IOException {
    Path settings = root.resolve(".claude/settings.json");
    write(settings, "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "]}}");
    var service = new ProviderApplicationService();
    service.install(location, "claude");

    service.uninstall(location, "claude");

    assertEquals(
        "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "]}}"
            + System.lineSeparator(),
        read(settings));
  }

  @Test
  void claudeUninstallDeletesFileHoldingOnlyItsOwnHooks() throws IOException {
    Path settings = root.resolve(".claude/settings.json");
    write(
        settings,
        "{\"hooks\":{\"PreToolUse\":[" + claudeHook() + "],\"SessionStart\":[]}}");

    new ProviderApplicationService().uninstall(location, "claude");

    assertFalse(Files.exists(settings));
  }

  @Test
  void codexInstallOnUserHooks() throws IOException {
    Path hooks = root.resolve(".codex/hooks.json");
    write(
        hooks,
        "{\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + "],\"SessionStart\":["
            + USER_SESSION
            + "],\"Stop\":[]}}");

    new ProviderApplicationService().install(location, "codex");

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + ","
            + codexHook()
            + "],\"SessionStart\":["
            + USER_SESSION
            + ","
            + codexSessionHook()
            + "],\"Stop\":[]}}"
            + System.lineSeparator(),
        read(hooks));
  }

  @Test
  void codexWorktreeCopyOnFreshFile() throws IOException {
    Path worktree = Files.createDirectories(temp.resolve("lane-codex"));

    ProviderHookConfiguration.materializeHook(
        worktree, ProviderRegistry.find("codex"), launcher, location.profile());

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":["
            + codexHook()
            + "],\"SessionStart\":["
            + codexSessionHook()
            + "]}}"
            + System.lineSeparator(),
        read(worktree.resolve(".codex/hooks.json")));
  }

  @Test
  void codexWorktreeCopyOnUserHooks() throws IOException {
    Path worktree = Files.createDirectories(temp.resolve("lane-codex-user"));
    write(
        worktree.resolve(".codex/hooks.json"),
        "{\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "]},\"other\":1}");

    ProviderHookConfiguration.materializeHook(
        worktree, ProviderRegistry.find("codex"), launcher, location.profile());

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + ","
            + codexHook()
            + "],\"SessionStart\":["
            + codexSessionHook()
            + "]},\"other\":1}"
            + System.lineSeparator(),
        read(worktree.resolve(".codex/hooks.json")));
  }

  @Test
  void codexUninstallLeavesUserHooks() throws IOException {
    Path hooks = root.resolve(".codex/hooks.json");
    String user =
        "{\"hooks\":{\"PreToolUse\":["
            + USER_PRE_TOOL
            + "],\"SessionStart\":["
            + USER_SESSION
            + "]}}";
    write(hooks, user);
    var service = new ProviderApplicationService();
    service.install(location, "codex");

    service.uninstall(location, "codex");

    assertEquals(user + System.lineSeparator(), read(hooks));
  }

  @Test
  void codexUninstallDropsEmptiedKeys() throws IOException {
    Path hooks = root.resolve(".codex/hooks.json");
    write(hooks, "{\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "]}}");
    var service = new ProviderApplicationService();
    service.install(location, "codex");

    service.uninstall(location, "codex");

    assertEquals(
        "{\"hooks\":{\"PreToolUse\":[" + USER_PRE_TOOL + "]}}" + System.lineSeparator(),
        read(hooks));
  }

  @Test
  void codexUninstallDeletesFileHoldingOnlyItsOwnHooks() throws IOException {
    var service = new ProviderApplicationService();
    service.install(location, "codex");
    Path hooks = root.resolve(".codex/hooks.json");
    assertEquals(true, Files.exists(hooks));

    service.uninstall(location, "codex");

    assertFalse(Files.exists(hooks));
  }

  private String claudeHook() {
    String command =
        quoted(launcher)
            + " hook claude --profile "
            + quoted(location.profile());
    return "{\"id\":\"synesis-claude-code\",\"matcher\":\"Edit|Write\",\"hooks\":[{\"type\":\"command\",\"command\":"
        + json(command)
        + "}]}";
  }

  private String codexHook() {
    return "{\"matcher\":\"^apply_patch$\"," + codexHandlers();
  }

  private String codexSessionHook() {
    return "{\"id\":\"synesis-codex-session\",\"matcher\":\"startup|resume\"," + codexHandlers();
  }

  private String codexHandlers() {
    return "\"hooks\":[{\"type\":\"command\",\"command\":"
        + json(quoted(launcher) + " hook codex")
        + ",\"commandWindows\":"
        + json("cmd.exe /d /s /c \"" + quoted(launcher) + " hook codex\"")
        + "}]}";
  }

  private static String quoted(Path path) {
    return "\"" + path.toAbsolutePath().normalize() + "\"";
  }

  private static String json(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
