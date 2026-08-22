# Current Task

## Identity

- Task ID: SYN-039
- Status: ACTIVE
- Priority: P0
- Activation checkpoint: CP-0459
- Previous completed task: SYN-038 at CP-0458
- Responsible agent: primary implementation engineer
- Related decisions: existing WorkGroup/LaneGrant, snapshot, integration,
  cleanup, Doctor, and provider-boundary decisions; no new ADR is created by
  activation alone

## Objective

Make two ordinary Synesis-aware coding agents complete one shared repository
task unattended through the existing Synesis coordination model. Agent A must
implement, Agent B must review and validate without conflicting write
ownership, rejection must return work to the correct implementer, accepted
work must integrate, and the WorkGroup must close with no unresolved state.

## Activation boundary

- The reviewer-validation production slice is implemented and under
  verification; reviewer admission and grant consumption are now covered.
- The existing independent Codex/Claude Code session model remains underneath
  Synesis. Do not add a central orchestrator, UI, daemon, Fleet system,
  centralized launcher, provider intelligence, or manual relay service.
- SYN-038 remains complete and its prior acceptance evidence and
  `turn_interrupted_command_remained_active` limitation are preserved.

## Evidence and known gap

- Primary failure input: the user-supplied previous unattended Todo smoke test.
- The raw Todo smoke-test artifact was not present in the checkout. The
  reproduction is now captured in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Checked-in supporting evidence is
  `docs/architecture/zero-touch-agent-collaboration.md`, where the two-process
  path is DEMO_ONLY and manually driven, and
  `docs/validation/multi-chat-provider-acceptance.md`, which does not claim
  autonomous end-to-end integration.

## Acceptance target

- Two ordinary Synesis-aware sessions discover and join one durable WorkGroup
  without user file assignment or message relay.
- A reviewer/validator can inspect and validate another agent's completed
  immutable snapshot through a read-only or explicitly delegated review path
  without acquiring the implementer's mutation ownership.
- Validation emits structured accept/reject evidence. Rejection returns
  durable work to the correct implementer with preserved lineage and
  idempotent request handling.
- Accepted work integrates through the existing guarded integration path into
  the final project state without manual intervention.
- Completion closes participants, claims, lane grants, pending requests,
  detached coordination state, and temporary artifacts.
- Final `synesis doctor` is healthy or reports only explicitly accepted
  non-blocking warnings.
- The unattended two-agent Todo experiment passes end to end: Agent A
  implements, Agent B reviews/validates, one rejected result routes back
  correctly, the corrected result is accepted, tests pass, the control
  checkout contains the completed application, the WorkGroup closes, and no
  unresolved coordination state remains.
- The existing ten-tool MCP boundary and provider model remain intact.

## Baseline result

The real two-session reproduction failed. Agent A created WorkGroup
`7c5ab815-5f05-365b-a78b-3478440036af`, implemented the Todo completion
change, passed three focused tests, and published snapshot
`snap_6162f6fd4ff4d51aadb5484609270ab3`. Integration failed with
`integration_failed` / `TESTS_FAILED`. Agent B discovered the WorkGroup but
could not obtain review authorization: `work_group_join` required an unavailable
`grantId`, and no validation request or snapshot view was exposed. The control
checkout stayed at baseline `7a5925f`; post-run Doctor was `DEGRADED` with two
`stale_session_lease` warnings and reconciliation recommended.

The baseline identified the first narrow implementation seam as reviewer
snapshot authorization plus the structured integration evidence mismatch; it
did not authorize a new orchestrator, daemon, UI, Fleet system, or launcher.

## Work completed

Implemented the first two defects and deterministic regressions. A reviewer
can now submit a typed `REVIEW` request after discovering a WorkGroup; owner
acceptance issues a targeted single-use LaneGrant without changing write
ownership. Collaboration status and next-action projections expose WorkGroups,
grants, and immutable snapshots. The integration-check adapter now recognizes
the recorded bounded passing Todo evidence instead of manufacturing
`TESTS_FAILED`.

The exact implementation and rerun evidence is recorded in
`docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`.

## Current failures

The fresh rerun used fixture
`C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-review-20260822-4`.
Agent A ran `pytest` 3/3. Agent B autonomously discovered the exact
`work_group_join` action, submitted the review request, received grant
`2f248cda-272e-3a3f-bf9c-92d871198670`, and consumed it. The next projected
action was `SNAPSHOT_PENDING` / `wait`, but Agent A never published a snapshot.
No validation decision was recorded; WorkGroup
`ed61f1d9-02d8-350b-8188-e27854dc9a21` remains `ACTIVE`. The control checkout
remained at managed baseline `4794183`. Doctor is `DEGRADED` with five
warnings. Full root `check` reached `:mcp:test` but did not complete because
`McpServerTest.setUp` remained blocked in the Git subprocess runner; it is not
reported as green.

## Implementation order

1. Reproduce and capture the supplied unattended Todo failure.
2. Implement reviewer admission and the integration evidence fix.
3. Make producer completion publish the immutable snapshot after the admitted
   reviewer consumes its grant, then let the reviewer validate through the
   existing path and preserve the next failure as a bounded blocker.
4. Implement autonomous rejection routing, handoff lineage, and WorkGroup
   cleanup/Doctor closure only as required by evidence.
5. Rerun the same unattended Todo experiment with no babysitting and record
   the complete evidence.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
trace the producer snapshot-publication transition for WorkGroup
`ed61f1d9-02d8-350b-8188-e27854dc9a21` and add the narrowest deterministic fix
that lets the admitted reviewer reach the immutable snapshot. Do not create
SYN-040 or broaden SYN-039 speculatively.
