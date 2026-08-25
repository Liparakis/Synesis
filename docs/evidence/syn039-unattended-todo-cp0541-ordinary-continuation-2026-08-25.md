# SYN-039 ordinary unattended Todo continuation — CP-0541

Date: 2026-08-25
Task: SYN-039 — Autonomous Workgroup Completion
Result: the fresh ordinary run reached shared REVIEW, grant consumption,
immutable snapshot publication, validation, and integration for the
implementation lane. It did not reach reciprocal publication or WorkGroup
closure. No unchanged projected Synesis action failed and no new production
defect was proven.

## Fixture and preflight

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0541-2026-08-25-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0541-2026-08-25-001`
- Project ID: `4ca2ea73-3d75-4895-ab76-a0a84749feb9`
- Seed commit: `3538093`
- Both wrappers used the current bundled executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- The MCP traces for both independent Codex sessions reached initialize
  response and `tools_list_received`; both sessions independently reached
  `ready / isolated` in the same initialized project.
- Agent A prompt was limited to implementing `TodoList.complete` in visible
  `todo.py`; Agent B prompt was limited to adding a focused regression test in
  visible `test_todo.py`. Neither prompt assigned lifecycle actions or
  relayed peer information.

## Durable coordination state

- WorkGroup: `94a259df-c1b3-3b0f-8bd9-3c481745c87c`, final `ACTIVE`, version 1.
- Agent A / implementation: participant
  `agt_60233a4d-32ab-3624-9d4d-7db024e10353`, intent
  `be85bc04-dc78-3204-8918-d86bf910e621`, claim `PATH_EXACT:todo.py`, epoch 1.
  Final participant state: `COMPLETED`.
- Agent B / test: participant
  `agt_adfbf959-e359-3189-814b-f98ebcd3f984`, intent
  `3c84f9d5-fbb1-36fd-8def-5074f1cbb536`, claim `PATH_EXACT:test_todo.py`,
  epoch 1. Final participant state: `ACTIVE`.
- REVIEW requests, both `ACCEPTED`:
  - B → A: `d5561561-b6b0-4680-9847-831108e42678`
  - A → B: `c9a8eca7-ca9f-47b7-93bb-6e14e7bfce53`
- REVIEW grants:
  - `d7d42eeb-45fb-35c1-9386-f9bfd435176d`, target B, target intent A,
    consumed once by B; B returned structured `ACCEPTED` validation.
  - `976388e2-d7f2-373e-83a1-9f36df6045ca`, target A, target intent B,
    epoch 1, still pending because A's session ended before consumption.

## Projection and action trace

The complete JSONL traces are retained under the harness directory. The
following records the relevant projection followed by the actual action.

1. B received `get_next_action` → exact
   `request_coordination({kind:work_group_join,payload:{workGroupId:
   94a259df-c1b3-3b0f-8bd9-3c481745c87c,intentId:
   be85bc04-dc78-3204-8918-d86bf910e621,proposal:Review the immutable snapshot
   for this work group}})` and executed it unchanged, creating request
   `d5561561-b6b0-4680-9847-831108e42678`.
2. A received `respond_coordination` for that request with exact projected
   `coordinationStatus=ACCEPTED` and executed it unchanged.
3. B received the exact grant-consumption `request_coordination` projection
   for grant `d7d42eeb-45fb-35c1-9386-f9bfd435176d`, including WorkGroup,
   intent, epoch, and target participant. The request succeeded and consumed
   the single-use grant.
4. B received `WAIT → get_next_action({})` while the implementation snapshot
   was absent. After the expected `workspace_stale` recovery projection, B
   executed `ensure_session({})`, then received the explicit
   `review_decision` choice contract for the immutable snapshot.
5. B ran the projected review command
   `python -m pytest -q test_todo.py` in review workspace
   `immutable_review_snapshot` against snapshot
   `snap_c46638443d433f95564066fc20dce6e7`, commit
   `b3be2c9fd2ec89cd4d6d2e1cf8a9f23c5705e9be`; exit code was 0 with
   `2 passed in 0.01s`. B submitted the exact structured
   `review_validation` ACCEPT response and Synesis returned `ACCEPTED`.
6. A received `snapshot_publication_required` → exact
   `finish_lane({summary:"Publish the completed immutable snapshot"})` and
   executed it unchanged. Synesis returned `snapshotState=PUBLISHED` and
   `integrationState=integrated` for snapshot
   `snap_c46638443d433f95564066fc20dce6e7`, changed path `todo.py`.
7. A then executed the exact reciprocal
   `request_coordination(work_group_join)` continuation for B's intent,
   creating request `c9a8eca7-ca9f-47b7-93bb-6e14e7bfce53`. A polled the
   unchanged `WAIT → get_next_action({})` owner-response continuation three
   times and its provider session ended.
8. B later received and executed the exact projected owner response for
   request `c9a8eca7-ca9f-47b7-93bb-6e14e7bfce53`, then received repeated
   `WAIT → get_next_action({})` with `review_grant_consumption` context for
   grant `976388e2-d7f2-373e-83a1-9f36df6045ca` targeted at A. B polled that
   continuation repeatedly; it could not consume A's grant on A's behalf.

No unchanged concrete projected tool call returned an error. The `WAIT`
projections are deliberate: `AgentNextActionService` projects a polling
continuation while the other participant owns the outstanding grant or
request. The run stopped because A no longer polled after B's response, not
because Synesis lacked a usable action.

## Final state and verification

- Control checkout integration commit: `69059ad`
  (`Synesis immutable lane snapshot`), containing the implementation snapshot.
- Control checkout `pytest -q`: `2 passed in 0.01s`. B's test snapshot was not
  published, so the control checkout still contains the original two tests.
- WorkGroup remained `ACTIVE`; A was `COMPLETED`, B remained `ACTIVE` with its
  `test_todo.py` claim; reciprocal grant
  `976388e2-d7f2-373e-83a1-9f36df6045ca` remained pending.
- Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
  reconciliation recommended, repair available, no mutations performed,
  next action `prepare_repair_plan`.
- Repository focused verification after the run: deferred validator PASS,
  workspace projection/reducer tests PASS, and `git diff --check` PASS.
- The focused `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`
  reproduced the known Git subprocess stall. At the captured point, worker
  PID 8088 test thread `Test worker` was sleeping in
  `ProcessCommandRunner.startProcess(ProcessCommandRunner.java:144)` through
  `TaskSnapshotService.hasPublishableChanges` and
  `AgentNextActionService.snapshotPublicationAction`; child Git PID 20268
  was `git ... rev-parse HEAD^`. The run was interrupted without increasing
  the timeout.

## Classification

This is agent/session compliance evidence, not a new lifecycle defect. The
diagnostic rule was satisfied by every concrete projected action that was
executed. The first incomplete boundary was provider engagement at valid
polling continuations. No production code changed, nothing was pushed, and no
SYN-040 was created.

Raw traces:

- `...\harness-ordinary-cp0541-2026-08-25-001\logs\agent-a.jsonl`
- `...\harness-ordinary-cp0541-2026-08-25-001\logs\agent-b.jsonl`
- `...\harness-ordinary-cp0541-2026-08-25-001\logs\mcp-a.trace`
- `...\harness-ordinary-cp0541-2026-08-25-001\logs\mcp-b.trace`
