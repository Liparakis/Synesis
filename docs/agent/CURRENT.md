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

- This activation changes durable task bookkeeping only. No SYN-039 production
  code has been changed.
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

No SYN-039 production code changed. The baseline identifies the first narrow
implementation seam as reviewer snapshot authorization plus structured
validation and completion handoff; it does not authorize a new orchestrator,
daemon, UI, Fleet system, or launcher.

## Work completed

Reproduced the two-agent Todo failure, captured raw session output and exact
durable identifiers, recorded control/lane Git state, recorded final
coordination and Doctor state, and updated the SYN-039 evidence and test
matrix. No production code changed.

## Current failures

Reviewer authorization requires an unavailable `grantId`; no validation item
or snapshot projection is exposed. The published implementation reaches
`integration_failed` / `TESTS_FAILED` despite three passing isolated tests.
The control checkout remains at baseline, the implementer lane was active at
stop, and final Doctor reports two stale session leases.

## Implementation order

1. Reproduce and capture the supplied unattended Todo failure.
2. Inspect the existing reviewer/validator access and evidence boundaries.
3. Implement the smallest read-only review path and explicit validation
   decision contract.
4. Implement autonomous rejection routing, handoff lineage, accepted
   integration, and WorkGroup cleanup/Doctor closure.
5. Rerun the same unattended Todo experiment with no babysitting and record
   the complete evidence.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
inspect the existing LaneGrant, snapshot projection, validation, completion,
and integration transitions. Add the smallest deterministic regression fixture
for the missing reviewer grant and the `pytest`-passing / `TESTS_FAILED`
completion contradiction before changing broader production behavior.
