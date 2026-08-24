# SYN-039 post-fix review-projection diagnostic — CP-0494

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Classification: production projection defect fixed; fresh diagnostic then
stopped on agent-compliance evidence.

## Scope and bundle

The run used a fresh disposable Git + Synesis project:

- Project root: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0494-001`
- Project ID: `03dad00b-fbb4-4500-aa9a-22f91c7d7494`
- External harness: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0494-001`
- Agent model: two independent `gpt-5.6-luna` Codex sessions
- MCP executable for both agents:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- Wrapper arguments for both agents: `mcp --provider codex --project <project-root>`
- Bundle version: `0.1.0-dev.local`; MCP server version: `0.1.0-SNAPSHOT`
- MCP protocol: `2025-06-18`; catalog: exactly 10 tools
- Both sessions reached `ensure_session = ready`, `workspace = isolated`, with
  distinct session/worktree identities.

The provider install reported the known `DEGRADED` trust-review status, while
MCP health and the project binding were successful. No manual coordination,
relay, lifecycle trigger, claim repair, or control-checkout mutation was used.

## Production defect established before this rerun

The preceding CP-0493 run executed the projected reviewer validation action
with the projected grant, snapshot, intent, epoch, and ACCEPT decision, but the
MCP returned:

```text
status=blocked
reason=policy_denied
error=COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:workGroupId
```

`AgentNextActionService.reviewActions` had placed `workGroupId` and
`targetParticipant` in the executable `review_validation` payload. The strict
`respond_coordination` contract correctly rejects those fields; they are
context already available in the projected grant/snapshot and are not inputs
to `ReviewValidationService`.

The smallest fix keeps those fields in the surrounding review projection but
removes them from the executable `review_validation` payload. The payload now
contains only `grantId`, `snapshotId`, `intentId`, `claimEpoch`, `result`, and
the optional rejection `reason`. This preserves strict fail-closed validation
and makes the projected action executable.

The same implementation slice also records the earlier CP-0490 defect: a
normal Python test run left `__pycache__/todo.cpython-313.pyc` in the producer
lane, and `finish_lane` incorrectly collapsed the resulting artifact-policy
failure to `task_not_ready`. Python bytecode caches are now classified as
allowed runtime artifacts; the source-change policy remains fail-closed.

## Deterministic verification of the fix

Passing focused checks:

- `:workspace:test --tests org.synesis.workspace.AgentNextActionServiceTest`
- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`
- `:cli:platformBundle --rerun-tasks`
- `git diff --check`

`McpSyn039SliceTest` now asserts the exact projected validation payload has no
`workGroupId` or `targetParticipant`, preserves the grant/snapshot/intent/epoch
fields, and executes the ACCEPT branch from that projection successfully.
Its publication regression creates the Python cache artifact before executing
the exact projected `finish_lane` action and proves the immutable snapshot is
published and visible to the reviewer.

## Fresh diagnostic lifecycle

The shared WorkGroup and participants were:

- WorkGroup: `471a4f65-5210-327f-ad5a-ba2897d022ab`, `ACTIVE`, version 1,
  epoch 1
- Producer Agent A: participant
  `agt_11e6b518-98a2-318e-a663-ae7ef7beab69`, intent
  `8e631b01-115b-35c6-8e4a-d9dd0e8a27c1`, claim `todo.py`
- Reviewer Agent B: participant
  `agt_c938232e-73c6-363d-98be-c8103a68e6e1`
- REVIEW requests: `970731a8-6e01-43bb-a976-2294b6c977ce` and
  `47980751-2ccc-4d06-a115-78611afa098c`, both accepted by the owner
- REVIEW grants: `2d616273-a235-3cec-b2fd-054a855fb8c6` and
  `e6500955-750e-380f-8266-928ceef42109`; the first was consumed by Agent B,
  the second remained available

The observed projection/action pairs were:

1. Agent B received `REVIEW_ADMISSION_REQUIRED` → exact
   `request_coordination(work_group_join)` for WorkGroup
   `471a4f65-5210-327f-ad5a-ba2897d022ab` and intent
   `8e631b01-115b-35c6-8e4a-d9dd0e8a27c1`; the exact request succeeded.
2. Agent B received the exact single-use grant-consumption projection for
   grant `2d616273-a235-3cec-b2fd-054a855fb8c6`; the exact request succeeded.
3. Agent A received `snapshot_publication_required` →
   `finish_lane({"summary":"Publish the completed immutable snapshot"})` and
   executed those exact arguments successfully.
4. Agent A published snapshot `snap_3e7c0ee281c5190f43bcd2102a5853f7`,
   commit `67542ea641379d5eaef7a6b2b73d97541efd161d`, with changed path
   `todo.py`. Integration reported `integrated`; the control checkout ended
   clean at `45fc60a`.
5. Agent B then received the post-fix exact validation projection:

   ```json
   {
     "kind": "review_validation",
     "payload": {
       "grantId": "2d616273-a235-3cec-b2fd-054a855fb8c6",
       "intentId": "8e631b01-115b-35c6-8e4a-d9dd0e8a27c1",
       "claimEpoch": 1,
       "snapshotId": "snap_3e7c0ee281c5190f43bcd2102a5853f7",
       "result": "accepted|rejected"
     }
   }
   ```

   Agent B did not execute that concrete projected validation action. It
   selected an unprojected `read_file("todo.py")`, which produced
   `retry_required / workspace_stale`, then followed recovery projections.
   It never recorded ACCEPT/REJECT, and no production failure was observed
   for the projected validation action. This is agent-compliance evidence, not
   authorization or projection evidence for another production change.

The malformed duplicate request in the same log used a typo in the intent UUID
and was rejected with `Invalid UUID string`; the subsequent correctly formed
request created the second accepted REVIEW request. This is also agent-side
diagnostic noise and remains future idempotency/cleanup evidence.

## Terminal state and independent warnings

- Snapshot: published and integrated; reviewer validation: not recorded
- WorkGroup: `ACTIVE`
- Coordination CLI: `COORDINATION_STATUS=PASS`, with zero projected tasks and
  ownerships, but collaboration still retained the active WorkGroup and grants
- Control checkout: clean at `45fc60a`
- Doctor: `DEGRADED`, six warnings: two `stale_session_lease`,
  `command_namespace_reconciliation_required`, `command_capacity_or_retention`,
  and two `provider_migration_required`
- No ordinary unattended second acceptance was run because the bounded
  diagnostic did not complete end to end.

The known root Git subprocess stall, bootstrap migration failures, and Doctor
warnings remain separately classified. No timeout enlargement or lifecycle
weakening was made.

## Next narrow action

Run another fresh bounded diagnostic only to establish whether an ordinary
reviewer executes the now-executable projected validation action. Do not add
production lifecycle behavior for an agent that ignores a valid projection.
If the exact projected validation action is executed and a later transition
fails, preserve that later transition as the next SYN-039 blocker.
