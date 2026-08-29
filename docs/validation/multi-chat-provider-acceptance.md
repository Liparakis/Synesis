# Multi-chat provider acceptance checklist

## SYN-029 native-launcher follow-up (2026-07-29)

- The installed Windows distribution now exposes
  `C:\Users\Liparakis\AppData\Local\Synesis\bin\synesis-mcp.exe` and Codex's
  user configuration points to that executable with raw `mcp` arguments.
- The installer health probe passed MCP `initialize` and `tools/list` with
  exactly 11 tools. `./gradlew.bat check --no-daemon --max-workers=1
  --dependency-verification=strict` and bootstrap Go tests/vet pass.
- A real Codex CLI `exec` run with approvals bypassed established a fresh
  isolated Synesis session and called `get_next_action`; the durable projection
  returned active participants, recovery-held lanes, and no pending inbox
  items. A normal read-only run was cancelled by the Codex harness before the
  MCP call, so approval policy remains a provider-boundary consideration.
- A bounded Antigravity `agy --print` run returned a generic CLI response and
  made no MCP call. Antigravity native/model-driven collaboration therefore
  remains unverified; the successful native MCP transport probe is separate
  evidence and does not establish agent autonomy.

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

| Row | Chat A | Chat B             | Required result                                                           |
|-----|--------|--------------------|---------------------------------------------------------------------------|
| 1   | Codex  | Codex              | disjoint lanes integrate; same-provider cross-binding is denied           |
| 2   | Claude | Claude             | disjoint lanes integrate; same-provider cross-binding is denied           |
| 3   | Codex  | Claude             | overlap is denied before mutation; declared contract negotiation succeeds |
| 4   | parent | delegated subagent | delegated lane has its own binding/worktree and targeted grant            |

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

## SYN-028 real recovery evidence

- A rebuilt local Synesis MCP stdio process for Codex initialized against a
  disposable Git project and advertised exactly 11 raw tools.
- The process established an exact claim on
  `tests/real-recovery-probe.txt`, then was terminated abruptly. No source
  checkout was modified.
- Immediate reconciliation classified the lease as `SUSPECTED_STALE` and
  transferred no ownership. After the configured five-minute grace period,
  dry-run reconciliation reported one `RECOVERY_ELIGIBLE` lease and two
  executable actions.
- Owner-independent reconciliation prepared and executed a recovery plan with
  `completed=2`, `failed=0`, and `controlCheckoutModified=false`; the source
  participant became `RECOVERY_HELD`.
- A second real Codex MCP process received a new isolated lane and a single-use
  continuation grant. The continuation call returned `CONTINUED`; status then
  showed the source participant `DETACHED`, the target participant `ACTIVE`,
  the same logical work group, and the transferred exact claim.
- A stale/old-process retry could not reacquire the path: Synesis returned
  `overlapping_claim` naming the new target participant. This is protocol
  evidence only; it does not claim native provider-hook enforcement.

## SYN-029 ordinary-feature provider probe (2026-07-30)

- A clean disposable Git/Synesis fixture was initialized with project ID
  `3f1e44a2...`. A real Codex `exec` process, using only an ordinary feature
  request, established an isolated lane, claimed `src/feature_x3.txt`, wrote
  the file through Synesis, and read it back with a matching revision hash.
- The first completion attempt initially returned
  `coordination_intent_required` because completion derived the participant
  from the transient connection ID rather than the verified session binding.
  That defect was fixed and the focused MCP collaboration regressions pass.
- A subsequent clean probe passed the intent check and reached the snapshot
  path, but the MCP integration-check response exposed an enum in a JSON result
  (`unsupported JSON value`). The response projection is now normalized to
  string failure codes; no control-checkout mutation occurred.
- A real Claude process did establish an isolated lane and mutate a claimed
  `src/feature_y.txt` with a matching revision hash, but its MCP session
  resolved the previously configured `Synesis-Collaboration-Test` project
  instead of the requested disposable fixture. This is provider session/project
  context leakage; Claude same-fixture autonomous integration is not claimed.
- Antigravity installation and native MCP health passed, but `agy --print`
  either returned a generic parser response or timed out without an MCP call.
  A later attempt established a session against a different stale project and
  stopped on `workspace_mismatch`. Antigravity model-driven autonomy remains
  unverified.

These results distinguish transport, lane mutation, completion/integration,
and provider project-context behavior. Codex and Claude MCP transport is
confirmed; autonomous end-to-end integration and Antigravity model autonomy
remain open acceptance work.

## 2026-07-30 follow-up

- Claude Code 2.1.220, installed with a project-scoped `.mcp.json`, completed
  a disposable-project probe autonomously: isolated session, exact claim,
  revision-verified create/read, immutable snapshot, integration, and lane
  closure all succeeded. A prior probe exposed provider/admin metadata in the
  control diff; `.mcp.json` is now treated as administrative and excluded from
  snapshots and control-branch blocking.
- Antigravity 1.1.8 accepted a bounded `agy --print` prompt but returned a
  generic one-turn response without invoking MCP. Reinstalling with the native
  `synesis-mcp.exe` global registration (the documented Antigravity config path)
  and a disposable workspace-level config produced the same result. Transport
  installation is healthy; model-driven Antigravity collaboration remains
  unverified and is currently a provider CLI behavior boundary.
