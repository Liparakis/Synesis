package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies managed manual installation and tamper attestation.
 */
class ProviderManualServiceTest {

  @Test
  void installationIsAtomicAndTamperingRestrictsAttestation(@TempDir Path tempHome)
      throws Exception {
    String previous = System.getProperty("user.home");
    System.setProperty("user.home", tempHome.toString());
    try {
      ProviderManualService service = new ProviderManualService();
      assertFalse(service.attest("codex")
          .valid());
      assertEquals(tempHome.resolve(".codex/skills/synesis-provider-manual"),
          service.skillDirectory("codex"));
      assertFalse(Files.exists(tempHome.resolve(".codex/skills/synesis-manual")));
      assertTrue(service.install("codex")
          .valid());
      Path manual = service.skillDirectory("codex")
          .resolve("SKILL.md");
      String content = Files.readString(manual);
      assertTrue(content.contains("workflow `IMPLEMENT`"));
      assertTrue(content.contains("visible assigned worktree"));
      assertTrue(content.contains("Do not inspect `.synesis/**`"));
      assertTrue(content.contains("Do not call `finish_lane` or another lifecycle tool"));
      assertTrue(content.contains("workflow.decision.required"));
      assertTrue(content.contains("responseTemplates"));
      assertTrue(content.contains("fill payload.coordinationStatus"));
      assertTrue(content.contains("Never execute an incomplete template"));
      assertTrue(content.contains("Execute `finish_lane` only when `get_next_action` projects it"));
      assertTrue(content.contains("exact tool with those exact arguments"));
      assertTrue(content.contains("compare consecutive `workflow.actionId` values"));
      assertTrue(content.contains("workflow.retryAfterMs` before polling (default 5000 ms)"));
      assertTrue(content.contains("workflow.maxUnchangedPolls` unchanged responses (default 3)"));
      assertTrue(content.contains("workflow.recoveryDeadlineEpochMillis` is present"));
      assertTrue(content.contains("does not consume the unchanged-poll limit"));
      assertTrue(content.contains("Synesis wakes you at that time"));
      assertTrue(content.contains("stop polling until a fresh state-change signal or human input"));
      assertTrue(content.contains("call `get_next_action` once with `"));
      assertTrue(content.contains(
          "optional `integrationCheck` input only evaluates explicitly supplied compatibility facts"));
      assertTrue(content.contains("When `get_next_action` projects WAIT, end your turn"));
      assertTrue(content.contains("`run_command` refuses a command that only sleeps"));
      assertTrue(content.contains(
          "Do not report success merely because your own lane is complete"));
      assertTrue(content.contains(
          "call `ensure_session` with `task.goal`, `task.acceptance`, and `task.claims`"));
      assertTrue(content.contains("task.knownDependencies"));
      assertTrue(content.contains("NEEDS_CAPABILITY"));
      assertTrue(content.contains("implementation_validation` action with `result=inspect`"));
      assertTrue(content.contains("capabilityRequestHandle` and `implementationRevision`"));
      assertTrue(content.contains("{\"completionRequested\":true}"));
      assertTrue(content.contains("request is evaluated for that call only"));
      assertTrue(content.contains("likelyScopes` is descriptive only"));
      assertTrue(content.contains(
          "A ready session without claims is not permission to mutate task files"));
      assertTrue(content.contains("managed Codex continuity is `MANAGED_CONTINUITY`"));
      assertTrue(content.contains("do not fall back to a different identity"));
      // The rules that used to live only in the managed AGENTS.md section (ADR-0103 decision 9).
      assertTrue(content.contains("Use Synesis tools for project reads, file changes, and commands."));
      assertTrue(content.contains("synesis provider install claude"));
      assertTrue(content.contains("synesis provider install codex"));
      assertTrue(content.contains("do not rerun provider installation from the assigned isolated worktree"));
      assertTrue(content.contains("Native provider hooks are optional"));
      assertTrue(content.contains("stop native mutations and verify state through `read_file`"));
      assertTrue(content.contains("Reads carry revisions; provide the matching revision when applying a patch."));
      assertTrue(content.contains("stop mutation and inspect read-only state"));
      assertTrue(content.contains("A mutating Synesis tool may return a required continuation"));
      assertTrue(content.contains("the continuation is part of the durable protocol"));
      assertTrue(content.contains("call `get_next_action` again before ending the turn"));
      assertTrue(content.contains("The MCP surface contains exactly "
          + org.synesis.mcp.contract.McpToolCatalog.rawNames().size() + " tools"));
      assertFalse(content.contains("AGENTS.md"), "the manual must not send the agent to a project file");
      Files.writeString(manual, Files.readString(manual) + "tampered\n");
      assertFalse(service.attest("codex")
          .valid());
      assertTrue(service.install("codex")
          .valid());
      assertTrue(service.attest("codex")
          .valid());
    } finally {
      if (previous == null) {
        System.clearProperty("user.home");
      } else {
        System.setProperty("user.home", previous);
      }
    }
  }
}
