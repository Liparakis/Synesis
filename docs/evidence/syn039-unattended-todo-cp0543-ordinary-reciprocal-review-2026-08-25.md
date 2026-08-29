# SYN-039 ordinary unattended Todo reciprocal review — CP-0542

Date: 2026-08-25
Task: SYN-039 — Autonomous Workgroup Completion
Result: the fresh ordinary run reached one shared WorkGroup, reciprocal REVIEW
admission, grant consumption for the test snapshot, immutable publication,
structured ACCEPT, and integration. It stopped before the implementation
lane consumed its reciprocal grant and published its snapshot. No unchanged
projected lifecycle action failed and no new production defect was proven.

## Fixture and preflight

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0543-2026-08-25-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0543-2026-08-25-001`
- Project ID: `e2153f80-9aa1-4dc5-8c95-06c2cf2f9d48`
- Initial managed baseline: `99ef13a`
- Both wrappers used the current bundled executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- Both independent Codex sessions reached `ready / isolated` against the
  same project. The MCP traces reached initialize response and
  `tools_list_received` for both connections.
- Prompts described only the visible coding responsibilities: Agent A
  implemented `TodoList.complete` in `todo.py`; Agent B added one regression
  test in `test_todo.py`. No lifecycle instructions, manual message passing, or post-startup
  file assignment was supplied.

The generated project contract already states that a ready agent must follow
exact projected tools, call `get_next_action` after mutations, and remain
engaged through `WAIT -> get_next_action({})` while the WorkGroup or grants
remain unresolved. The sessions nevertheless ended at valid wait boundaries.

## Durable coordination state

- WorkGroup: `0b1f5704-1c63-3373-b5ea-7e16ea6c8b79`, final `ACTIVE`, version 1.
- Agent A / implementation: participant
  `agt_fbe7fcda-5278-364e-83e9-f2c52d5638ac`, intent
  `60b43e4f-6e16-3217-8f97-4ee32041ef38`, claim `PATH_EXACT:todo.py`, epoch 1.
  Final state: `ACTIVE`.
- Agent B / test: participant
  `agt_bf00f7fe-6794-307d-8a23-ee4853313705`, intent
  `ae9be337-cea2-3f76-9d9b-70882252cc28`, claim `PATH_EXACT:test_todo.py`,
  epoch 1. Final state: `COMPLETED`.
- REVIEW requests, both `ACCEPTED`:
    - A → B: `590cbda5-7f0d-49fc-a878-37575d0f0fd8`
    - B → A: `91dfd886-af80-4649-909b-27b8fcf2733b`
- REVIEW grants:
    - `f2e6b918-06f3-317c-9c3a-15b3b6cdc723`, target A, target intent B,
      consumed once by A.
    - `939017b6-16be-377e-9ff2-915dc002ffc6`, target B, target intent A,
      epoch 1, still pending.

## Projection and action trace

The complete JSONL traces are retained under the harness directory.

1. A received and executed the exact projected
   `request_coordination(work_group_join)` for request
   `590cbda5-7f0d-49fc-a878-37575d0f0fd8`. B received and executed the exact
   projected owner `respond_coordination(...ACCEPTED...)`.
2. A received the exact grant-consumption projection for
   `f2e6b918-06f3-317c-9c3a-15b3b6cdc723`, including WorkGroup, intent, epoch,
   and target participant, and executed it unchanged.
3. A followed the projected recovery and review path. It inspected the
   immutable snapshot `snap_a787bc5da5e0e7ca279b6f199625e1ed`, commit
   `9abb1b1f67423fe92af78cec68793c9d6caced14`, and ran
   `python -m pytest -q test_todo.py` in the immutable review workspace.
   The command returned exit code 1: `1 passed, 2 failed`, because the
   sibling `todo.py` implementation was not yet integrated into the test-only
   snapshot. The review projection exposed an explicit ACCEPT/REJECT choice;
   A submitted the valid structured ACCEPT response and Synesis returned
   `ACCEPTED`. This was a reviewer choice, not a failed Synesis tool call.
4. B received `snapshot_publication_required` and executed the exact
   `finish_lane({summary:"Publish the completed immutable snapshot"})`.
   Synesis returned `snapshotState=PUBLISHED` and
   `integrationState=integrated` for snapshot
   `snap_a787bc5da5e0e7ca279b6f199625e1ed`, changed path `test_todo.py`.
5. B executed the exact reciprocal `request_coordination(work_group_join)`
   continuation for A's intent. B then received repeated valid
   `WAIT -> get_next_action({})` owner-response projections and its provider
   session ended before A's response was observed.
6. A later received and executed the exact owner response for request
   `91dfd886-af80-4649-909b-27b8fcf2733b`, then received repeated valid
   `WAIT -> get_next_action({})` projections while grant
   `939017b6-16be-377e-9ff2-915dc002ffc6` remained targeted at B. A's session
   ended before B consumed that grant, so A's `todo.py` snapshot was not
   published.

No unchanged concrete projected action returned a protocol error. The
non-zero review command was a validation result inside the immutable snapshot,
not an integration classification error. The review contract intentionally
leaves ACCEPT/REJECT as the reviewer’s structured decision.

## Final state and verification

- Control checkout integration commit: `50651bd` (`Synesis immutable lane
  snapshot`), containing only the test snapshot.
- Control checkout `pytest -q`: `1 passed, 2 failed`; failures are the
  unintegrated `TodoList.complete` implementation tests.
- WorkGroup remained `ACTIVE`; the implementation claim remained active and
  grant `939017b6-16be-377e-9ff2-915dc002ffc6` remained pending.
- Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
  reconciliation recommended, repair available, no mutations performed,
  next action `prepare_repair_plan`.
- Existing Git subprocess stall, bootstrap migration failures, and Doctor
  warnings remain separately classified.

## Classification

This is provider/session engagement evidence. The generated contract already
states the required wait continuation, and every unchanged concrete action
that the agents executed succeeded. No production code changed, nothing was
pushed, and no SYN-040 was created.

Raw traces:

- `...\harness-ordinary-cp0543-2026-08-25-001\logs\agent-a.jsonl`
- `...\harness-ordinary-cp0543-2026-08-25-001\logs\agent-b.jsonl`
- `...\harness-ordinary-cp0543-2026-08-25-001\logs\mcp-a.trace`
- `...\harness-ordinary-cp0543-2026-08-25-001\logs\mcp-b.trace`
