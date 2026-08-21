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
- No raw Todo smoke-test artifact was found in the current checkout. The first
  implementation step must reproduce and capture the failure instead of
  inventing historical details.
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
reproduce the two-agent Todo failure and capture its exact process, task,
snapshot, rejection, handoff, integration, cleanup, and Doctor evidence before
changing production code.
