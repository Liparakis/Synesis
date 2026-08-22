# SYN-039 reviewer-validation slice evidence — 2026-08-22

## Scope

This evidence records the implementation slice after CP-0467 and the fresh
unattended two-agent Todo rerun. It covers reviewer admission, single-use
grant consumption, exact next-action projection, structured validation
recording, and the existing WorkGroup completion transition. It does not claim
WorkGroup cleanup, rejection handoff, stale-lease repair, detached-agent
cleanup, or Doctor closure.

Source baseline: CP-0467, commits `5ebc207` and `d261879`.

## Production changes

- Added the existing-model `REVIEW_VALIDATION_RECORDED` event and immutable
  `ReviewValidationPayload`.
- Added `ReviewValidationService` behind `respond_coordination` for typed
  `review_validation` decisions.
- Extended `WorkGroupProjection` with consumed-grant and validation state.
- Extended `get_next_action` projections with reviewer admission, targeted
  grant, snapshot-pending, and validation actions. Projections include the
  exact grant, immutable snapshot, and next protocol tool/action when those
  objects exist.
- Preserved LaneGrant target, intent, claim-epoch, snapshot, session, and
  single-use checks. Review never acquires the implementer's write claims.
- Added deterministic coverage for valid admission, wrong reviewer, wrong
  snapshot, grant replay, ACCEPT, REJECT payloads, and reviewer discovery.

## Deterministic verification

Passed:

```text
git diff --check
:coordination:test --tests WorkGroupServiceTest --tests ReviewValidationPayloadTest
:workspace:test --tests AgentNextActionServiceTest --tests IntegrationOrchestrationServiceTest --tests TaskIntegrationServiceTest
:mcp:test --tests McpSyn039SliceTest
```

The combined focused Gradle run completed `BUILD SUCCESSFUL` in 39 seconds.
`:cli:installDist` also completed `BUILD SUCCESSFUL`; the fresh acceptance
used that rebuilt distribution.

The full command
`gradlew.bat check --dependency-verification=strict --no-daemon --no-parallel --max-workers=1 --console=plain`
did not complete. It reached `:mcp:test` after repository hygiene, format,
Javadoc, static analysis, packaging, and earlier module checks. The test
worker remained in `McpServerTest.setUp` while
`ManagedBaselineTransactionService.prepare` waited in
`ProcessCommandRunner.startProcess` for a Git subprocess. The run was stopped
after approximately 150 seconds with no test result. This is verification
incomplete, not a green full-repository result.

## Fresh unattended rerun

Fixture:
`C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-review-20260822-4`

Project: `c16c1fff-2ea5-488e-873f-bc86b6a48320`

Agent A created WorkGroup
`ed61f1d9-02d8-350b-8188-e27854dc9a21`, intent
`487478da-eb01-310d-9044-0d955a911b09`, and participant
`agt_bf023686-4a04-32f3-9036-bf9b7d693741`. It claimed `todo.py` and
`test_todo.py`, implemented `complete_todo`, and ran `python -m pytest -q`
with `3 passed in 0.01s` in its isolated worktree.

Agent B participant was
`agt_7ea45a6e-8a58-3e99-add7-118c53615be7`. It discovered the WorkGroup and
`get_next_action` exposed:

```text
REVIEW_ADMISSION_REQUIRED
nextProtocolAction=request_coordination
nextProtocolKind=work_group_join
```

Agent B submitted the projected review request without manual relay. Agent A
accepted the request, and the projection issued targeted single-use grant
`2f248cda-272e-3a3f-bf9c-92d871198670` for Agent B. Agent B consumed that grant
through the projected protocol action; no write claim was taken. The next
projection was:

```text
SNAPSHOT_PENDING
nextProtocolAction=wait
nextProtocolKind=review_validation
snapshotRequired=true
```

The owner never published an immutable snapshot in this rerun. Consequently
Agent B could not inspect or validate a snapshot, no ACCEPT/REJECT decision was
recorded, and the WorkGroup remained `ACTIVE`. A second unused single-use
grant (`ed8b0f7a-6ddc-3be4-8110-020b8e750443`) remained projected. This is the
next concrete blocker: producer completion does not reliably publish the
reviewable snapshot after the reviewer admission/consumption transition.

The control checkout remained at managed baseline commit `4794183`; no Todo
implementation reached the control checkout in this rerun. The disposable
fixture's raw Codex JSONL is retained outside the repository as
`agent-a.out.jsonl` and `agent-b.out.jsonl`.

## Final diagnostics

`coordination status --project=<fixture>` returned `COORDINATION_STATUS=PASS`
with zero project tasks and zero ownerships, but the WorkGroup projection in
the active MCP sessions remained `ACTIVE`.

`doctor --project=<fixture> --json --verbose` returned `DEGRADED` with five
warnings and no critical/errors:

- two `ambiguous_session_liveness` findings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- `provider_migration_required`.

No Doctor repair or cleanup action was performed. These warnings remain later
SYN-039 work unless the next snapshot-publication fix directly requires them.

## Acceptance result

Reviewer admission and single-use grant consumption now work autonomously and
fail closed. Snapshot publication, validation, WorkGroup completion, and final
Doctor health remain unresolved. SYN-039 remains ACTIVE. No SYN-040 was
created, and nothing was pushed.
