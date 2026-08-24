# SYN-039 CP-0527 post-fix projection diagnostic

Date: 2026-08-24

## Scope and harness

This was a fresh bounded diagnostic after the CP-0526 claim-aware publication
fix. Both agents were instructed only to execute an exact concrete
`get_next_action` projection before choosing another Synesis lifecycle action;
ordinary `IMPLEMENT` with no executable action remained ordinary visible-file
work. No manual relay or lifecycle transition was performed.

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0527-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0527-001`
- Project ID: `290da007-e42b-4d8d-9353-c446c56c0e1a`
- Seed commit: `56646d4 seed Todo diagnostic acceptance`
- Managed baseline: `4fe7acff687bd6ed4d8b6381a8ed2e02714b5172`
- MCP executable: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `133E5C2D2A12ADF8FC3E72113BAF11A90DA0E7AB17FB536BF2E92C3ED0131D6C`
- MCP identity: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit `bc334ac`, exactly 10 tools
- MCP startup connections: A `conn-instance-e541f753-88d6-4e58-afc4-7987c6583c2e`, B `conn-instance-e0a3b1a2-816c-4d2e-b0a6-3ac5c745fc02`

## Participants and ownership

- Agent A session `session-a295fd53-2597-4884-b3be-6636f27a8501`, participant
  `agt_d08a4cdc-de4e-34c9-ac1e-769ff533921f`, intent
  `7def130b-5a95-3ff2-8750-c1f3d8811c8a`, `PATH_EXACT todo.py`, epoch 1.
- Agent B session `session-6ce6316a-99c7-45aa-9259-b0075adcb8bd`, participant
  `agt_7623f50b-5e1e-36ef-baa2-b5fb5225feb3`, intent
  `033d5eb9-df31-3e39-af29-f13d36cc461d`, `PATH_EXACT test_todo.py`, epoch 1.
- Shared WorkGroup: `39e79e24-f414-3e69-9000-da49f7983e3f`, `ACTIVE`, version 1.
- Both sessions reached isolated worktrees and both initial `get_next_action`
  projections exposed the same shared WorkGroup and disjoint claims.

## Projection/action trace to first failure

1. Agent B called `get_next_action({})` after `ensure_session`. Synesis
   projected `REVIEW_ADMISSION_REQUIRED` with executable
   `request_coordination` and these exact arguments:

   ```json
   {
     "kind": "work_group_join",
     "payload": {
       "intentId": "7def130b-5a95-3ff2-8750-c1f3d8811c8a",
       "proposal": "Review the immutable snapshot for this work group",
       "workGroupId": "39e79e24-f414-3e69-9000-da49f7983e3f"
     }
   }
   ```

2. Agent B executed that exact tool and exact payload. Synesis returned:

   ```json
   {"status":"blocked","reason":"policy_denied","nextAction":"retry",
    "result":{"error":"INTENT_NOT_FOUND"}}
   ```

3. Agent B then called `get_next_action({})` again and received the same
   unchanged REVIEW admission projection. No grant, snapshot, validation,
   integration, or WorkGroup closure was reached.

This is not an agent-compliance deviation: the projected action was sent
unchanged. The projection listed the target intent in the same response, but
the admission lookup rejected that exact target by the time the mutation was
processed. Final read-only status after stopping showed Agent A detached and
Agent B active; the per-call Codex JSON logs do not timestamp the exact request
relative to the detach event, so the race ordering is not yet proven.

## Final diagnostics

After the stop, the WorkGroup remained `ACTIVE`; no coordination request or
grant was durably present, and no snapshot, validation, or integration state
existed. Doctor reported `DEGRADED` with five warnings: stale session lease,
command namespace reconciliation, command capacity/retention, and two provider
migration warnings. These remain separately classified until causal evidence
connects them to this admission failure.

Raw agent JSONL and MCP stderr logs are retained under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0527-001`.

## Classification and next slice

CP-0527 establishes the next concrete SYN-039 blocker: an unchanged projected
REVIEW admission can fail closed with `INTENT_NOT_FOUND` while the projection
still exposes the target intent. The next implementation slice is to reproduce
the projection-to-admission state transition with durable participant/intent
timestamps and determine whether the projection is stale or the admission path
is resolving the wrong current state. Do not weaken ownership or fail-closed
authorization, and do not alter snapshot, validation, integration, cleanup, or
Doctor behavior speculatively.
