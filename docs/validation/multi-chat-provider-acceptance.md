# Multi-chat provider acceptance checklist

This checklist is the reproducible real-harness follow-up to the deterministic
`MultiChatLogicalWorkspaceTest`. It validates provider behavior without
claiming native hook enforcement or remote authority.

## Preconditions

- Install the current local Synesis distribution and configure exactly one
  Synesis MCP server entry per provider scope.
- Use a disposable Git project with a committed baseline and the Synesis MCP
  server available to both chats.
- Keep each chat's MCP connection instance distinct. Never copy a binding,
  session, participant, or worktree identity between chats.

## Run matrix

For each row, record the MCP `tools/list` count, participant handles, intent
IDs, work-group ID, claim epochs, snapshot references, integration result, and
test output:

| Row | Chat A | Chat B | Required result |
| --- | --- | --- | --- |
| 1 | Codex | Codex | disjoint lanes integrate; same-provider cross-binding is denied |
| 2 | Claude | Claude | disjoint lanes integrate; same-provider cross-binding is denied |
| 3 | Codex | Claude | overlap is denied before mutation; declared contract negotiation succeeds |
| 4 | parent | delegated subagent | delegated lane has its own binding/worktree and targeted grant |

For every row:

1. Join one logical work group and acquire disjoint exact-path or subtree
   claims. Verify distinct isolated worktrees.
2. Mutate concurrently, publish dirty immutable snapshots, and verify that
   provenance contains the work-group, lane, participant, binding, epoch,
   claims, and snapshot reference.
3. Attempt an overlapping claim and mutation from the losing chat; require a
   fail-closed response and unchanged source/revision.
4. Integrate both snapshots in the dedicated integration worktree and run the
   configured project tests.
5. Close one lane and verify the group and sibling lane remain active.
6. Exercise a single-use continuation grant, including replay, wrong target,
   stale epoch, and revoked-grant rejection.

## External evidence boundary

This checklist proves provider MCP transport and collaboration behavior only.
It does not prove that a provider's native editor, shell, IDE, or hook cannot
write outside Synesis. Record native-hook outcomes separately. If a provider
quota, authentication state, or harness UI prevents a row, record the exact
blocker and leave the deterministic local acceptance as the authoritative
implementation evidence.

## Current provider audit

- Codex CLI `0.140.0` is installed and authenticated. Its configured Synesis
  server is enabled, and direct stdio `initialize` succeeds from the task-
  tracker project. The bounded real Codex CLI probe nevertheless cancelled
  both `ensure_session` calls before a response, so no participant or claim is
  recorded. This is provider-runtime evidence, not a Synesis claim failure.
- Claude Code `2.1.220` is installed, but its global MCP file currently uses
  `servers` instead of the required `mcpServers` key. Claude reports that MCP
  configuration as unparsable; no real collaboration row is claimed.
- Claude's bounded probe succeeded when given an ephemeral valid MCP config:
  it acquired the exact claim as participant `agt_c015f69d-27cc-35ef-b5bb-
  dccea2c43ad6`, then released it with empty claims. No file was modified.
- Antigravity CLI `1.1.8` is installed, but its bounded probe repeatedly
  returned `workspace_not_ready` because the harness reported no active
  workspace. A bounded `--new-project` retry entered Antigravity's project
  onboarding prompt instead of exposing the existing project's MCP session;
  no claim was established. Existing direct MCP evidence remains separate from
  native-hook maturity.
