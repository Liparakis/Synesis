# SYN-039 CP-0476 — Fresh unattended Todo harness preflight

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Checkpoint: CP-0476  
Repository commit: `194ef16` (`Implement SYN-039 snapshot publication action`)  

## Purpose

This run was a fresh two-agent acceptance attempt after CP-0475. The harness
was required to prove both independent agents were using the current bundled
MCP and the same initialized project before either agent began Todo work.
Neither agent was allowed to start the task after a failed preflight.

## Fresh fixture

- Project root: `C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-cp0476-20260824-001`
- Project ID: `c6fe0862-b75a-4e9f-8d9a-6c0e9aa0ea43`
- Initial application commit: `b2f3841` (`baseline`)
- Synesis managed baseline: `b6165ec686a1df3518ccc8ffa0567e8ec9bd4df0`
- Final fixture status: clean at the managed baseline

The fixture contained a minimal `TodoList` implementation and one passing
test. No agent changed it during this run.

## Direct current-bundle control preflight

The repository-bundled executable used for the control preflight was:

`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`

- SHA-256: `FAECFCB1B9ED43E9786C922BA880841FCD950FE612B1C359DCD61CD9807FB1BA`
- MCP startup version: `0.1.0-SNAPSHOT`
- MCP startup commit: `bc334ac`
- Protocol: `2025-06-18`
- Server version: `0.1.0-SNAPSHOT`
- Provider: `codex`
- Project argument: the exact fixture root above
- MCP catalog: exactly 10 tools, identical for both control connections:
  `ensure_session`, `read_file`, `apply_patch`, `run_command`,
  `get_next_action`, `request_coordination`, `respond_coordination`,
  `publish_capability_implementation`, `finish_lane`, `cancel_lane`

Two explicit independent control connections were opened with connection IDs
`syn039-cp0476-agent-a` and `syn039-cp0476-agent-b`. Both returned
`ensure_session.status=ready` and `workspace=isolated`, with distinct session
worktrees:

- Agent A control worktree:
  `...\worktrees\session-df4a5516-84a3-46df-8187-0f7f5ad85cf0`
- Agent B control worktree:
  `...\worktrees\session-c90edd3b-4f56-4913-bb6d-216b17fa10fb`

Both control connections observed the same project ID and initial coordination
state: sequence `0`, no participants, intents, groups, requests, grants, or
snapshots. A direct post-failure control invocation also returned
`ensure_session(refresh=true).status=ready`, `workspace=isolated`, and a valid
current participant, proving that the bundled executable can establish a
usable session for this fixture outside the agent harness route.

## Independent agent results

Two independent `gpt-5.6-luna` agents were launched with high reasoning and
complementary roles. Both were instructed to perform the same preflight and
to stop before Todo work if any check failed.

### Agent A

- MCP catalog: exactly 10 tools
- `ensure_session(refresh=true)`: three attempts returned
  `status=retry_required`, `reason=workspace_not_ready`,
  `nextAction=ensure_session`
- No usable session/isolation identity was established
- No `get_next_action`, claim, coordination request, Todo read/edit, review,
  snapshot, validation, or integration operation was performed

### Agent B

- MCP catalog: exactly 10 tools
- Fixture root and project ID matched the initialized fixture
- `ensure_session(refresh=true)`: three attempts returned
  `status=retry_required`, `reason=workspace_not_ready`,
  `nextAction=ensure_session`
- No active MCP session identity or initialize version/protocol was verified by
  the agent harness
- No Todo or Synesis repository files were modified

## State reached

The agents never began Todo work. The fixture remained at:

- Coordination sequence: `0`
- Tasks: `0`
- Ownerships: `0`
- Participants/intents/groups: none
- Requests/grants/snapshots: none
- Validation/integration: none
- WorkGroup terminal state: not applicable; no WorkGroup was created

Therefore this is not evidence about review, handoff, snapshot, validation,
integration, or WorkGroup closure. The first blocker is the agent-harness
preflight path: it reports `workspace_not_ready` while an explicit invocation
of the same current bundled MCP, project root, provider, and `refresh=true`
returns `ready / isolated`.

## Doctor and separate verification state

The disposable fixture Doctor result was `DEGRADED` with four warnings and no
errors or critical findings:

- `command_namespace_reconciliation_required`
- `command_capacity_or_retention`
- two `provider_migration_required` findings because the deliberately
  project-pinned configuration references the repository development bundle
  rather than the stable launcher

The known root `McpServerTest` Git subprocess stall and bootstrap migration
test failures remain separate verification issues. This preflight run did not
prove either warning set causes the agent-harness result.

## Conclusion and next action

No production code changed. No later SYN-039 lifecycle stage was reached.
The next implementation/investigation slice is to identify why the two
Codex agent MCP connections are not using the same effective current-bundle
project/session context as the successful explicit control invocation. Capture
the actual executable, startup line, project arguments, connection identity,
and readiness diagnostics from the agent route before changing readiness or
lifecycle code. Do not broaden SYN-039 and do not create SYN-040.
