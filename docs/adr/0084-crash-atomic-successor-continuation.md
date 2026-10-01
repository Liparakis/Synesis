# ADR-0084: Crash-atomic successor continuation

- Status: Accepted for SYN-082
- Date: 2026-09-19
- Architecture mode: EVOLUTION
- Scope: durable persistence and replay of fresh recovery continuation

## Context

ADR-0083 made an unfinished recovery-held `WorkIntent` visible and
continuable through the normal coordination surface. The fresh continuation
path originally had a residual crash window because it could persist a
derived lane grant separately from the ownership transition. A process loss
between those appends could leave a grant without a successor or make a retry
depend on in-memory state.

## Decision

The fresh successor path derives its deterministic grant identity but records
the ownership transition, successor intent, and grant consumption in one
`LANE_CONTINUATION_ACCEPTED` event. `WorkGroupProjection` reconstructs the
one-shot continuation grant from that event when replaying a fresh
continuation. Existing pre-issued grants remain valid for the reviewed path
and are consumed by the same continuation event.

`PredictionEventStore` writes each encoded event completely to a temporary
file, forces the file, atomically moves it into the numbered event log, and
only then applies projections. Tests run a separate JVM and halt it before the
move, after the durable move, and after projection. A fresh store cleans an
orphan temporary event in the pre-move case and replays the committed event in
both post-move cases. A retry reads the durable log: it either commits the
missing transition or returns the already committed successor without
creating a second authority.

The tested guarantee is process-crash recovery and deterministic replay. A
directory metadata flush after rename is not claimed as a hardware power-loss
guarantee by this ADR.

## Invariants

- A continuation event has one durable ownership transition; no fresh grant
  append can be stranded ahead of it.
- Source epoch, recovery-held state, snapshot reference, task, WorkGroup,
  selectors, role, dependencies, project, and authority lineage are checked
  again while holding the project append lock.
- At most one successor can replace a source epoch. Competing callers get
  the current committed successor or a deterministic validation failure.
- Replaying the same request is idempotent and never revives the former
  participant, runtime binding, session, or intent.
- The event log remains the authority; in-memory projections are disposable
  and rebuilt on restart.

## Consequences

Crash-boundary tests are part of the continuation acceptance slice. They prove
the source remains `RECOVERY_HELD` when no event became durable, and prove a
single successor remains authoritative when the durable event exists but the
caller lost its response. The implementation still does not make
reconciliation choose a successor and does not add an MCP tool.
