# ADR-0033: Explicit Roadmap Completion State

- Status: ACCEPTED
- Date: 2026-07-29
- Scope: durable agent task and checkpoint validators

## Context

The collaboration roadmap explicitly permits finalization with no active task
when the roadmap is complete. The existing resume, checkpoint, and doctor
scripts rejected zero active tasks unconditionally, which forced a completed
roadmap to retain a misleading active task or made the required checkpoint
impossible.

## Decision

When `docs/agent/GOAL.md` declares `roadmap complete`, zero `ACTIVE` tasks is a
valid terminal state. The validators continue to require exactly one active task
for all non-terminal states. Completion checkpoints record `none (roadmap
complete)` and `COMPLETE`, and `CURRENT.md`/`NEXT_SESSION.md` must still carry
an exact continuation command for any separately authorized future work.

## Consequences

- Completed durable state is honest and checkpointable.
- Normal in-progress work keeps the existing exactly-one-active invariant.
- This changes no product authorization, event, provider, or workspace behavior.
- A future task must be explicitly promoted before production changes resume.
