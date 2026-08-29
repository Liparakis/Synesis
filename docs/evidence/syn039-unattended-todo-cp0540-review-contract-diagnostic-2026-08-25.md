# SYN-039 bounded exact-action diagnostic — CP-0540 review contract

Date: 2026-08-25
Task: SYN-039 — Autonomous Workgroup Completion
Result: the diagnostic reached shared review, snapshot publication, immutable
validation, and integration for one lane, but ordinary provider continuation
did not reach reciprocal publication or WorkGroup closure. No new production
defect was proven.

## Fixture and preflight

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0540-review-contract-2026-08-25-001`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-syn039-diagnostic-cp0540-review-contract-2026-08-25-001`
- Project ID: `39e40dec-25a2-4cf8-a9cf-4b93727383bc`
- Seed commit: `c7f599f`
- MCP executable used by both wrappers:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- CLI metadata: `SYNESIS_VERSION=0.1.0-dev.local`, `RECORD_FORMAT=SDR2`,
  `RECONCILIATION_PROTOCOL=PRP1`, Windows x64; both MCP traces reached
  `tools_list_received`.
- Agent A session worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\39e40dec-25a2-4cf8-a9cf-4b93727383bc\worktrees\session-b2f69852-bf12-445d-a34d-bcc04a89a25e`
- Agent B session worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\39e40dec-25a2-4cf8-a9cf-4b93727383bc\worktrees\session-696b4dcd-5ad6-4070-84bb-50201be446a2`
- Both independently executed `ensure_session` and received `ready` /
  `isolated`; no manual lifecycle mutation was performed by the harness.

## Durable coordination state

- WorkGroup: `5608d46f-ba9a-3d88-8d8c-ceac20a4f8db`, final `ACTIVE`, version 1.
- Agent A / implementation: participant
  `agt_17499a8e-2182-3397-84e9-f7b1ce7d4aea`, intent
  `c964c868-0b76-3c65-9913-7156f8902911`, claim `PATH_EXACT:todo.py`, epoch 1.
- Agent B / test: participant
  `agt_a7c52ca7-3375-3185-902f-945496102858`, intent
  `c18f5b10-b487-37b9-b004-323333b739f4`, claim `PATH_EXACT:test_todo.py`,
  epoch 1.
- REVIEW requests, both `ACCEPTED`:
    - A → B: `6050acdf-9bfa-4e5f-b6ee-341e98e5da20`
    - B → A: `f08e2fa5-694c-4b33-ad6c-7a4d3d7b16a9`
- REVIEW grants:
    - `afb3791f-9fdd-3b71-b37c-275350c3fde9`, target A, consumed once.
    - `8314de57-1e14-3073-a898-2c34cc82d868`, target B, still pending.

## Projection/action trace

The complete JSONL traces are retained under the harness directory above.
Repeated polling rows are summarized by sequence range; every concrete
mutation and its immediate result is listed.

1. Both agents executed the initial `ensure_session` readiness action. B then
   received ordinary `IMPLEMENT` with no lifecycle action and performed its
   visible `test_todo.py` work. A received the exact projected
   `request_coordination(work_group_join)` for WorkGroup
   `5608d46f-ba9a-3d88-8d8c-ceac20a4f8db`; it executed the projected payload and
   created request `6050acdf-9bfa-4e5f-b6ee-341e98e5da20`.
2. B received the exact owner projection and executed
   `respond_coordination({kind:coordination_response,payload:{coordinationRequest:6050acdf-9bfa-4e5f-b6ee-341e98e5da20,coordinationStatus:ACCEPTED,proposal:admitted}})`.
3. A received the grant-consumption projection for
   `afb3791f-9fdd-3b71-b37c-275350c3fde9`. Its call changed the projected
   informational `targetParticipant` value but the server authorized the
   caller by its bound participant, consumed the single-use grant, and
   returned `CONSUMED`. This is an agent projection-compliance deviation, not
   an authorization bypass or an unchanged-action failure.
4. A received `WAIT -> get_next_action({})` for the consumed grant's required
   snapshot, then exact `RECOVER -> ensure_session({})` after the review
   workspace became stale.
5. A then received `nextAction=review_decision` with
   `nextProtocolKind=review_validation`, exact grant/snapshot/intent/epoch
   context, and the explicit choice contract
   `allowedResults=[accepted,rejected]` with a rejection reason required.
   The workflow intentionally did not label an incomplete reviewer choice as
   an executable command. A inspected the immutable snapshot and ran
   `pytest -q`; validation of that test-only snapshot returned exit code 1
   (`1 passed, 3 failed`) because `todo.py` was not yet integrated. A submitted
   the valid structured `review_validation` ACCEPT payload and Synesis returned
   `ACCEPTED`, WorkGroup still `ACTIVE`.
6. A received and executed the exact owner response for B's request
   `f08e2fa5-694c-4b33-ad6c-7a4d3d7b16a9`, then modified only its claimed
   `todo.py` lane. It repeatedly received the exact
   `REVIEW_GRANT_PENDING -> WAIT -> get_next_action({})` continuation for
   grant `8314de57-1e14-3073-a898-2c34cc82d868`.
7. B received `snapshot_publication_required` with exact
   `finish_lane({summary:"Publish the completed immutable snapshot"})` and
   executed it. Synesis returned `snapshotState=PUBLISHED` and
   `integrationState=integrated` for snapshot
   `snap_8c27ac8760b9ef947df1c4aae8d47bd4`, commit
   `af714a6a55b283a6553103ed2f07e46c217f11bf`, changed path
   `test_todo.py`. B then executed the exact reciprocal
   `request_coordination(work_group_join)` for A's intent and repeatedly
   received `WAIT -> get_next_action({})` for the pending owner response.
8. The provider sessions ended at that valid continuation boundary. No
   unchanged concrete projected action failed, and no required state lacked a
   usable Synesis projection.

## Final state and verification

- Control checkout integration commit: `9bd2939` (`Synesis immutable lane
  snapshot`); it contains B's `test_todo.py` snapshot only.
- Control checkout `pytest -q`: `1 passed, 3 failed`; the three failures are
  expected because A's valid `todo.py` implementation was not published.
- Validation decision: structured ACCEPT for B's snapshot by A; reciprocal
  validation was not reached.
- WorkGroup: `ACTIVE`; A remained `ACTIVE` with `todo.py` claim, B was
  `COMPLETED`; reciprocal grant `8314de57-1e14-3073-a898-2c34cc82d868`
  remained pending.
- Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings;
  reconciliation recommended, repair available, no mutations performed,
  next action `prepare_repair_plan`.

## Classification

The review-decision projection was usable as an explicit choice contract: the
reviewer selected ACCEPT and the existing strict `respond_coordination` path
accepted the structured payload. It would be incorrect to replace that choice
with an invented fixed result or to weaken fail-closed field validation.

The first incomplete boundary after the successful review/integration path was
provider/session continuation at repeated valid `WAIT -> get_next_action({})`.
The changed grant field is separately recorded as agent compliance evidence;
the bound participant, grant, epoch, and single-use checks remained effective.
No production code changed for this run. Git subprocess stalls, bootstrap
migration failures, and the six Doctor warnings remain separate verification
issues.

Raw traces:

- `...\harness-syn039-diagnostic-cp0540-review-contract-2026-08-25-001\logs\agent-a.jsonl`
- `...\harness-syn039-diagnostic-cp0540-review-contract-2026-08-25-001\logs\agent-b.jsonl`
- `...\harness-syn039-diagnostic-cp0540-review-contract-2026-08-25-001\logs\mcp-a.trace`
- `...\harness-syn039-diagnostic-cp0540-review-contract-2026-08-25-001\logs\mcp-b.trace`
