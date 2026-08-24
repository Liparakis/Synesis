# SYN-039 CP-0478 — Exact next-action protocol diagnostic

Date: 2026-08-24
Task: SYN-039 — Autonomous Workgroup Completion
Checkpoint: CP-0478
Production code changed: none

## Purpose

This was a fresh diagnostic acceptance after CP-0477. Both independent agents
were given one additional rule: whenever `get_next_action` returns an
executable action, execute that exact tool with the exact projected arguments
before choosing another Synesis lifecycle action. No messages, transitions,
claims, grants, snapshots, or validation decisions were relayed or triggered
manually.

The diagnostic did not reach a WorkGroup. It therefore does not provide a
new production lifecycle failure and did not justify a second ordinary-agent
acceptance run.

## Fixture and MCP identity

- Fresh fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0478-001`
- Project ID: `72b38176-c65c-47a6-942e-cf91eee4348f`
- Baseline commit: `25bd256`; Synesis-managed baseline: `7ee03be`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- SHA-256: `FAECFCB1B9ED43E9786C922BA880841FCD950FE612B1C359DCD61CD9807FB1BA`
- Startup identity: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`,
  commit `bc334ac`, exactly ten MCP tools
- Both Codex sessions used explicit provider `codex`, the exact project root,
  the current bundled MCP executable, and distinct connection-instance IDs:
  `syn039-cp0478-agent-a` and `syn039-cp0478-agent-b`.

Both agents independently established `ready / isolated` sessions against
the same project. Agent A's isolated worktree was
`...\\worktrees\\session-5bce9381-b461-47a2-8cc1-7281d98522af`; Agent B's was
`...\\worktrees\\session-5b40a44a-2204-4e8c-a9db-0185caeaab9e`. No agent
claimed files or changed the fixture.

## Projection/action trace

### Agent A — implementer

1. `get_next_action` returned `status=ready`, workflow `IMPLEMENT`, action
   `6995fdb3-51b4-3939-9872-f123eaa804fd`, with permitted operations
   `read_file`, `apply_patch`, and `run_command`. It did not project a
   coordination lifecycle tool or arguments.
2. The agent immediately used the permitted `run_command` operation with
   `git rev-parse --show-toplevel`.
3. A second `get_next_action` returned the same `IMPLEMENT` action and
   permitted-operation set. The agent immediately used permitted
   `run_command` with `git ls-files`.
4. Before any lifecycle action was projected, the agent selected an
   unprojected metadata read: `read_file(".synesis/project.json")`.
   Synesis returned `status=blocked`, `reason=invalid_path`. The agent
   stopped without mutation.

### Agent B — reviewer

1. `get_next_action` returned `status=ready`, workflow `IMPLEMENT`, action
   `4fe9cbb5-1478-3b1a-8066-bf3b613acec0`, with permitted operations
   `read_file`, `apply_patch`, and `run_command`. It did not project a
   coordination lifecycle tool or arguments.
2. The agent used permitted reads/commands in sequence: `run_command git
   ls-files`, `run_command git rev-parse --show-toplevel`,
   `read_file("todo.py")`, and `read_file("AGENTS.md")`.
3. It then selected the same unprojected metadata read,
   `read_file(".synesis/project.json")`, which returned
   `status=blocked`, `reason=invalid_path`.
4. The agent recovered its inspection using the permitted command
   `git show HEAD:.synesis/project.json`, which succeeded and showed schema
   version 2 and the expected project ID. It did not create a WorkGroup or
   perform a lifecycle transition.

The `IMPLEMENT` projections exposed a bounded operation class, not a specific
`read_file` path or lifecycle tool. Therefore the failed hidden-path read was
an agent-selected operation, not an exact projected action that failed. No
production protocol defect is proven by this run.

## Final state

The fixture remained at the managed baseline. Direct coordination status:

```text
COORDINATION_STATUS=PASS
PROJECT_ID=72b38176-c65c-47a6-942e-cf91eee4348f
PROJECT_SEQUENCE=0
PREDICTIONS=0
TASKS=0
OWNERSHIPS=0
```

No WorkGroup, participant claim, request, grant, snapshot, validation,
integration, or terminal WorkGroup state was created. No Todo implementation
was made by either agent, and no second acceptance was run because the
required diagnostic did not complete end to end.

Doctor was `DEGRADED` with six warnings: two stale session leases,
`command_namespace_reconciliation_required`, `command_capacity_or_retention`,
and two `provider_migration_required` findings. These are separately
classified existing disposable-project diagnostics; this run provides no
evidence that they caused the `invalid_path` agent choice.

## Classification and next action

Classification: agent discoverability/protocol compliance at the ordinary
file-inspection step, before SYN-039 coordination. This is not evidence to
change review, handoff, snapshot, validation, integration, ownership, or
cleanup production code.

Exact next action: preserve CP-0478 and assess the MCP contract for hidden
metadata paths. If another diagnostic is run, constrain only the agent's
initial repository inspection to valid repository operations and observe
whether it reaches the first projected coordination action. Do not manually
relay or trigger lifecycle actions, and do not run the second ordinary-agent
acceptance until the diagnostic completes.
