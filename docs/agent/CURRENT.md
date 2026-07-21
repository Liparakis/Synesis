# Current Task

## Identity

- Task ID: SYN-003
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0076
- Latest checkpoint: CP-0077
- Responsible agent: fresh coding agent
- Related decisions: ADR-0010, ADR-0011, ADR-0012, ADR-0013, ADR-0014

## Objective

Implement CP-W1 only: add the JDK-only `:workspace` launcher with bounded
`--profile` handling, isolated profile layout, identity inspection, atomic
one-peer project creation, and revision-1 signed decision creation with exactly
one evidence reference. Do not add sync, host/join, networking, or production
changes to Link, CLI, or project-record.

## Planning state

SYN-002 is DONE at CP-0075. SYN-001 is DONE at CP-R4 and CP-R5 remains
deferred. ADR-0014 selects `:workspace` as a composition layer only; it owns
no new record or protocol format.

## Work completed

Planning state is reconciled and validated. ADR-0014, the first two-person
demo script, and CP-W1 task scope are recorded. No production implementation
has been added yet.

## Verification

Planning-state validators and the CP-0076 checkpoint passed before code
changes. CP-W1 focused tests, the full strict root build, and all repository
validators now pass.

## Current failures

Physical generated-launcher onboarding, abrupt-loss, wrong-identity, and
physical project-record transfer remain unclaimed. CP-W1 has no known
verification failure.

## Work completed

Added `:workspace` with a generated `synesis-workspace` launcher. The launcher
supports bounded `--profile` handling, identity inspection, atomic one-peer
project creation, and signed revision-1 decision creation with exactly one
evidence reference. Link, `:cli`, and `:project-record` production code were
not changed.

## Immediate next action

Review CP-W1 evidence and approve or promote CP-W2 separately; do not implement
host/join or sync.
