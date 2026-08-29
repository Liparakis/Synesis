# SYN-039 exact-rule diagnostic — CP-0530

## Result and classification

This fresh bounded diagnostic used two independent GPT-5.6 Luna High Codex
processes, a fresh Git + Synesis project, the current bundled MCP, and the
explicit exact-projection rule in both prompts. No messages, identifiers,
requests, grants, snapshots, validation decisions, or lifecycle transitions
were relayed or triggered by the harness.

The run reached one shared WorkGroup, exact REVIEW admission, owner acceptance,
single-use grant consumption, immutable snapshot publication, integration, and
structured ACCEPT. The first stop was agent compliance: after Agent A created
the reciprocal REVIEW request, its next `get_next_action` again projected the
exact executable `request_coordination` action, but A ended its Codex turn
without executing that projection or continuing to the reciprocal grant.
Agent B then remained in the projected `WAIT -> get_next_action({})` state for
the grant targeted at A. No unchanged projected Synesis action failed, so this
run does not prove a production protocol defect and caused no production-code
change.

The conditional second ordinary acceptance was not run because this diagnostic
did not complete end-to-end.

## Fresh project and harness

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0530-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0530-001`
- Project ID: `33a0be3b-2996-452a-b9ee-c6d154211c96`
- Seed commit: `6c3b40e Seed SYN-039 diagnostic Todo fixture`
- Managed baseline after initialization: `28ea379`
- Control checkout after Agent A integration: `3843a47`
- Control checkout status: clean
- Current bundled MCP:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `133E5C2D2A12ADF8FC3E72113BAF11A90DA0E7AB17FB536BF2E92C3ED0131D6C`
- CLI version: `0.1.0-dev.local`
- MCP startup identity: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`,
  commit `bc334ac`, exactly 10 tools
- Agent A MCP connection: `syn039-cp0530-agent-a`
- Agent B MCP connection: `syn039-cp0530-agent-b`
- Both launchers passed the same project root, provider `codex`, and the
  current bundled MCP with model `gpt-5.6-luna` and high reasoning effort.
- Both MCP traces reached `initialize_response_written` and
  `tools_list_received`.

## Participants, claims, and WorkGroup

| Agent | Session worktree                                               | Participant                                | Intent / epoch / claim                                                     |
|-------|----------------------------------------------------------------|--------------------------------------------|----------------------------------------------------------------------------|
| A     | `...\\worktrees\\session-cb1f28d8-16a3-4f2b-864f-cf4ad1d99cc2` | `agt_6932f607-83b9-3e7b-b13d-cf0edbbc200a` | `1bb491f6-7b8b-34e8-b770-ed7b5b661cb0`, epoch 1, `PATH_EXACT todo.py`      |
| B     | `...\\worktrees\\session-fc687199-2e4f-4308-8a9d-561c3a36c1ed` | `agt_b8cd0735-c01a-37d6-bcb8-eb0b080dd707` | `a4e8a40c-7d61-314f-a288-a2d397101c1a`, epoch 1, `PATH_EXACT test_todo.py` |

Shared WorkGroup: `58c39d35-7835-3efe-b9ed-731e53e87465`, version 1, terminal
state `ACTIVE`.

## Projection/action trace

1. Both sessions independently established `ready / isolated` state with
   disjoint epoch-1 claims. A received ordinary `IMPLEMENT` with no
   executable lifecycle action, implemented `TodoList.complete`, and ran
   `python -m pytest -q test_todo.py`: `2 passed`. B added one meaningful
   completion test in its own claimed file; its isolated worktree correctly
   reported `2 passed, 1 failed` because A's implementation was not yet in
   B's worktree.

2. B received and executed the exact REVIEW admission projection:

   ```json
   {"kind":"work_group_join","payload":{
     "intentId":"1bb491f6-7b8b-34e8-b770-ed7b5b661cb0",
     "proposal":"Review the immutable snapshot for this work group",
     "workGroupId":"58c39d35-7835-3efe-b9ed-731e53e87465"}}
   ```

   Request `148c22cb-2c70-4ffb-a1ab-d79893e0cf4c` was created and later
   accepted by A with the exact projected response:

   ```json
   {"kind":"coordination_response","payload":{
     "coordinationRequest":"148c22cb-2c70-4ffb-a1ab-d79893e0cf4c",
     "coordinationStatus":"ACCEPTED","proposal":"admitted"}}
   ```

3. B received the single-use grant projection containing grant,
   participant, intent, epoch, and WorkGroup identifiers. B first omitted
   `targetParticipant` and received fail-closed
   `COORDINATION_FIELD_REQUIRED:targetParticipant`. B then changed the
   projected intent ID and received fail-closed
   `LANE_GRANT_TARGET_MISMATCH`. B finally executed the unchanged projection:

   ```json
   {"kind":"work_group_join","payload":{
     "grantId":"47da8861-47e4-3d66-8dba-b67959b31daf",
     "intentId":"1bb491f6-7b8b-34e8-b770-ed7b5b661cb0",
     "claimEpoch":1,
     "workGroupId":"58c39d35-7835-3efe-b9ed-731e53e87465",
     "targetParticipant":"agt_b8cd0735-c01a-37d6-bcb8-eb0b080dd707"}}
   ```

   The result was `CONSUMED`. These two failed calls are agent-compliance
   evidence; they are not failures of an unchanged projected action.

4. A then received and executed the exact publication projection:

   ```json
   {"summary":"Publish the completed immutable snapshot"}
   ```

   `finish_lane` returned `task=integrated`, snapshot
   `snap_2d49b85428978b46934f2a308975ae73`, state `PUBLISHED`, claim epoch 1,
   and integration state `integrated`. The control checkout advanced to
   `3843a47`; its visible tests passed `2 passed`.

5. B received the structured `review_decision` projection for that immutable
   snapshot. It first chose an unprojected `read_file(todo.py)` and Synesis
   correctly returned `workspace_stale`. B then returned to `get_next_action`
   and executed the exact structured decision:

   ```json
   {"kind":"review_validation","payload":{
     "grantId":"47da8861-47e4-3d66-8dba-b67959b31daf",
     "intentId":"1bb491f6-7b8b-34e8-b770-ed7b5b661cb0",
     "claimEpoch":1,
     "snapshotId":"snap_2d49b85428978b46934f2a308975ae73",
     "result":"accepted"}}
   ```

   Synesis returned `ACCEPTED` with WorkGroup status `ACTIVE`.

6. A received and executed the exact reciprocal REVIEW admission request for
   B's still-active intent:

   ```json
   {"kind":"work_group_join","payload":{
     "proposal":"Review the immutable snapshot for this work group",
     "workGroupId":"58c39d35-7835-3efe-b9ed-731e53e87465",
     "intentId":"a4e8a40c-7d61-314f-a288-a2d397101c1a"}}
   ```

   Request `6cc03ba9-9173-4564-b588-12c6f0b2f424` was accepted by B. A's
   next `get_next_action` still projected that exact executable
   `request_coordination` action. A ended its turn without executing the
   unchanged projection or continuing to poll for its reciprocal grant
   `99fe5115-716b-32ea-aa12-171e22a918c4`, which targeted A.

7. B obeyed the available continuation and repeatedly executed the exact
   projected `get_next_action({})` wait. The projection remained
   `review_grant_consumption` with `snapshotRequired=true` and target
   participant A. Because A had ended, B could not advance its own snapshot
   publication. No B snapshot, second validation decision, or closure was
   reached.

## State at the first blocker

- WorkGroup: `58c39d35-7835-3efe-b9ed-731e53e87465`, `ACTIVE`
- A: `COMPLETED`, claim released, A snapshot published/integrated
- B: `ACTIVE`, epoch-1 claim `PATH_EXACT test_todo.py`
- Request B → A: `148c22cb-2c70-4ffb-a1ab-d79893e0cf4c`, `ACCEPTED`
- Request A → B: `6cc03ba9-9173-4564-b588-12c6f0b2f424`, `ACCEPTED`
- Grant for A's snapshot: `47da8861-47e4-3d66-8dba-b67959b31daf`, consumed
- Reciprocal grant for B's snapshot: `99fe5115-716b-32ea-aa12-171e22a918c4`,
  targeted at A and unresolved
- Published snapshot: `snap_2d49b85428978b46934f2a308975ae73`
- Validation: structured ACCEPT recorded for A's snapshot
- B snapshot/validation/integration: not reached
- WorkGroup closure: not reached
- Coordination status: `PASS`, sequence `0`, tasks `0`, ownerships `0`
- Control checkout: clean at `3843a47`; the integrated snapshot also carried
  generated `__pycache__` files, a later cleanup observation rather than the
  first blocker in this diagnostic

## Doctor and verification

The final fixture Doctor result was `DEGRADED`, with 0 critical findings, 0
errors, and 6 warnings:

- two `stale_session_lease` findings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` findings.

These warnings did not prevent ready/isolated sessions, WorkGroup formation,
REVIEW admission, grant consumption, snapshot publication, validation, or one
integration. They remain separately classified.

Raw Codex JSONL, prompts, launchers, MCP startup logs, and traces remain under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0530-001`.

The diagnostic first stopped at an agent ending after an unchanged executable
projection, not at a production response. Therefore no production code or
MCP lifecycle semantics should be changed from this evidence alone.
