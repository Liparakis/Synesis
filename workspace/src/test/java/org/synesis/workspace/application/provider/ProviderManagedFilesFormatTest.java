package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Pins the files Synesis writes into a fresh project: the hook files of each provider install and
 * the managed section of {@code AGENTS.md}.
 *
 * <p>The launcher path is machine specific and is masked as {@code <LAUNCHER>}.
 */
class ProviderManagedFilesFormatTest {

  /** The installed launcher, as the JSON-escaped Windows path the hook commands embed. */
  private static final Pattern LAUNCHER_PATH =
      Pattern.compile("[A-Za-z]:(?:\\\\\\\\[^\\\\\"]+)*\\\\\\\\synesis\\.exe");

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
        {"hooks":{"PreToolUse":[{"id":"synesis-claude-code","matcher":"Edit|Write","hooks":[{"type":"command","command":"\\"<LAUNCHER>\\" hook claude --profile \\"<PROFILE>\\""}]}]}}"""
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

  @Test
  void agentsManagedSection() throws IOException {
    String agents = Files.readString(root.resolve("AGENTS.md"), StandardCharsets.UTF_8);
    int begin = agents.indexOf("<!-- SYNESIS-BEGIN -->");
    int end = agents.indexOf("<!-- SYNESIS-END -->") + "<!-- SYNESIS-END -->".length();

    assertEquals(
        """
        <!-- SYNESIS-BEGIN -->
        ## Synesis

        This repository uses Synesis.

        - Use Synesis tools for project reads, file changes, and commands.
        - One persistent MCP connection owns one provider binding and one isolated worker context.
        - Before the first MCP session after `synesis init`, install the integration matching the responding harness in the control project directory: Claude Code uses `synesis provider install claude`, and Codex uses `synesis provider install codex`. The matching `claude` or `codex` executable must be installed and available to that harness. Once `ensure_session` is ready, continue with that provider binding; do not rerun provider installation from the assigned isolated worktree, which may not contain the control project profile.
        - After synesis.ensure_session succeeds, keep the provider in the current project directory and use Synesis MCP for all reads, writes, and commands. Synesis applies those operations internally in the assigned worktree; do not switch branches, cd, or edit another worker's worktree manually. Native provider hooks are optional and may be unavailable in desktop harnesses, but they do not replace the required Synesis integration, so never assume a native mutation was routed. If a native tool is used and Synesis reports workspace_mismatch, stop native mutations and verify state through synesis.read_file.
        - Reads carry revisions; provide the matching revision when applying a patch.
        - Do not modify the control checkout or another worker's files directly.
        - When Synesis reports an identity, ownership, freshness, or workspace failure, stop mutation and inspect read-only state.
        - The MCP surface currently contains exactly 10 tools; follow get_next_action's recommended tool and typed arguments.
        - A ready session is not yet an announced collaboration intent. Before mutating visible task files, call ensure_session with task.goal, task.acceptance, and task.claims containing the exact repository-relative files or subtrees assigned to this responsibility. Use claim kind path_exact or path_subtree, keep claims disjoint, and do not guess or overlap ownership. This is the existing intent/claim announcement; there is no separate announce tool. likelyScopes is descriptive only and does not acquire ownership.
        - If ensure_session returns overlapping_claim, stop attempting to claim or mutate that path. Use the conflict's exact ownerAuthorityLineageId, establish a new intent on a disjoint consumer-file claim, and declare the capability identifier in task.knownDependencies. Submit request_coordination kind capability_request with a complete API contract. If the owner revises it, explicitly accept or counter the exact contractRevision from get_next_action before building against it. Both lanes may implement after acceptance; integration waits for the owner implementation, requester validation, and the owner's implementation snapshot.
        - Call get_next_action with no arguments to read the durable coordination inbox. Its optional integrationCheck input only evaluates explicitly supplied compatibility facts; it never advances a lane or WorkGroup, cannot close or validate work, and must not replace empty-argument polling.
        - A mutating Synesis tool may return a required continuation. When its result contains a concrete nextAction/recommendedTool and typed arguments, execute that exact tool with those exact arguments before ending the turn or choosing another Synesis lifecycle action; the continuation is part of the durable protocol, not an optional suggestion.
        - After any completed Synesis mutation, call get_next_action again before ending the turn; a successful coordination or publication mutation may create the next durable inbox action even when the mutation result has no explicit continuation.
        - When get_next_action reports IMPLEMENT without a concrete recommendedTool and typed arguments, continue the visible assigned coding work normally; do not call finish_lane or another lifecycle tool merely because the coding appears complete. Execute lifecycle actions only when get_next_action projects the exact tool and arguments, then return to get_next_action after coding progress.
        - After implementation, tests, and acceptance criteria are complete, call get_next_action once with completionRequested:true to request the exact publication projection; do not substitute repeated empty-argument polls.
        - For repeated WAIT (including recommendedTool=get_next_action), IMPLEMENT, or blocked projections, compare consecutive workflow.actionId values, honor workflow.retryAfterMs (default 5000 ms), and stop after workflow.maxUnchangedPolls unchanged responses (default 3). Reset the count when actionId changes. At the limit, report the stalled action ID and stop polling until a fresh state-change signal or human input; an active WorkGroup alone does not justify repeated unchanged calls. This is provider guidance, not a server-enforced counter.
        - When get_next_action projects WAIT with recommendedTool=get_next_action and arguments {}, execute that continuation after workflow.retryAfterMs (default 5000 ms), subject to the unchanged-response limit above. Do not report success merely because your own lane is complete.
        <!-- SYNESIS-END -->""",
        agents.substring(begin, end).replace("\r\n", "\n"));
  }

  private String masked(Path file) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    return LAUNCHER_PATH
        .matcher(PROFILE_PATH.matcher(text).replaceAll("$1<PROFILE>$3"))
        .replaceAll("<LAUNCHER>");
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
