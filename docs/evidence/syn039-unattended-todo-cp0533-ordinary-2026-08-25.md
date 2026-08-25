# SYN-039 CP-0533 ordinary unattended acceptance

## Scope

This was a fresh ordinary two-agent acceptance after CP-0532. The agents
received only complementary visible coding prompts. No lifecycle instruction,
message relay, manual transition, ownership repair, snapshot publication,
validation, integration, or cleanup was performed by the harness.

The retained provider traces are outside this repository:

- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0533-001\logs\agent-a.jsonl`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0533-001\logs\agent-b.jsonl`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0533-001\logs\mcp-a.trace`
- `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0533-001\logs\mcp-b.trace`

Fixture project:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0533-001`

Project ID: `046bf533-7bf7-43c0-83b5-d1a318608ac9`.

The control checkout began at seed commit `f0cf453` and remained clean at
`94e29a4` after the run. The isolated agent worktrees were separate from the
control checkout.

## MCP preflight

Both independent preflight invocations used the current bundled executable:

```text
C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe
version: 0.1.0-SNAPSHOT
protocol: 2025-06-18
sha256: E91A08ADD236925A42D7A11F5F89AA615E807BB23C822925BD77E17EA0D6BEFB
tools: 10
```

The tools were exactly:
`ensure_session`, `read_file`, `apply_patch`, `run_command`,
`get_next_action`, `request_coordination`, `respond_coordination`,
`publish_capability_implementation`, `finish_lane`, and `cancel_lane`.

Both preflight sessions used the same project pin and returned
`ready / isolated` with distinct worktrees. The actual Codex MCP connections
were also distinct:

- Agent A: `syn039-cp0533-001-agent-a`, session worktree
  `...\worktrees\session-99479900-6e89-4f3e-a9c4-f7eb0bf27714`.
- Agent B: `syn039-cp0533-001-agent-b`, session worktree
  `...\worktrees\session-6e01d8b9-8276-47dd-a9ad-ccff3db0926c`.

## Coordination state and trace

The two participants announced disjoint epoch-1 claims and converged on one
WorkGroup:

- Agent A: `agt_764f50c4-8670-3904-a69b-a3d264f4b925`, intent
  `5af2190b-e365-3fe1-8fa7-a25ae5891c85`, exact claim `todo.py`, epoch `1`.
- Agent B: `agt_103d0dd5-cc45-3ca0-9367-526c4b3617b4`, intent
  `e7c4f3b0-1a36-3896-a684-d972efe4def5`, exact claim `test_todo.py`, epoch `1`.
- WorkGroup: `62e57bbc-897e-3946-a5d2-5082d1e1a2c1`, status `ACTIVE`.

The trace progressed as follows:

1. Both agents recovered from the initial `session_not_ready` projection by
   executing `ensure_session`, then performed ordinary visible repository
   work.
2. Agent A implemented `TodoList.complete` in its isolated `todo.py` and its
   focused test run passed `5/5`. Its final observed `get_next_action` was
   ordinary `IMPLEMENT` with no `recommendedTool` or lifecycle arguments.
   Agent A then ended its provider turn.
3. Agent B added one focused test in its isolated `test_todo.py`. Its local
   suite correctly reported `5 failed, 1 passed` because A's implementation
   was not integrated into B's worktree.
4. Agent B then received this executable projection:

   ```text
   nextAction=request_coordination
   nextProtocolKind=work_group_join
   intentId=5af2190b-e365-3fe1-8fa7-a25ae5891c85
   workGroupId=62e57bbc-897e-3946-a5d2-5082d1e1a2c1
   proposal=Review the immutable snapshot for this work group
   ```

   The workflow contained the same exact `request_coordination` arguments.
   Agent B executed those exact arguments successfully. Synesis created
   request `574c290b-36bd-417e-9286-dce2d9a57cc6`, kind `REVIEW`, from B to A.
5. Agent B's subsequent `get_next_action` calls continued to project the same
   pending request because Agent A's provider turn had already ended. No owner
   acceptance projection was observed or executed.

No exact projected Synesis action failed. The first boundary was provider
session engagement: the owner was no longer active when the peer request was
created, so the valid pending request could not advance to owner acceptance.
The later repeated polling is retained as agent-compliance evidence, not a
backend lifecycle defect.

## Terminal state

Read-only `synesis collaboration status --project` reported:

```text
REQUEST=574c290b-36bd-417e-9286-dce2d9a57cc6 STATUS=PENDING KIND=REVIEW
WORK_GROUP=62e57bbc-897e-3946-a5d2-5082d1e1a2c1 STATUS=ACTIVE VERSION=1
```

There were no grants, snapshots, validation decisions, integration results,
or terminal WorkGroup state. The control checkout therefore retained the
unimplemented seed and direct control `pytest -q` reported `4 failed, 1
passed`.

Doctor was `DEGRADED` with six warnings, zero errors, and zero critical
findings: two stale session leases, command namespace reconciliation, command
retention/capacity review, and two provider-migration warnings. These remain
separately classified from the provider-turn boundary.

## Classification and next action

This run proves ordinary provider/session engagement is still insufficient:
the owner can finish its visible coding turn before a peer's later REVIEW
request exists, leaving an active WorkGroup and pending request with no owner
session available to consume it. It does not prove a Synesis projection or
mutation defect, because the request projection and exact request action both
worked.

The next implementation decision requires a new bounded diagnostic focused on
ordinary session continuation/engagement. Do not add lifecycle retries,
orchestration, cleanup, or argument repair from this run alone.
