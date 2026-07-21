# Current Task

## Identity

- Task ID: SYN-003
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0076
- Latest checkpoint: CP-0078
- Responsible agent: fresh coding agent
- Related decisions: ADR-0010, ADR-0011, ADR-0012, ADR-0013, ADR-0014

## Objective

Implement CP-W2 only: add bounded one-shot `sync host` and `sync join` in
`:workspace`, composing the existing Link onboarding seam and CP-R4 sync APIs.
Pin the expected remote before creating B's config, preserve mismatched config,
and return safe deterministic outcomes. Do not add retries, reconnect,
background behavior, or production changes to Link, CLI, or project-record.

## Planning state

SYN-002 is DONE at CP-0075. SYN-001 is DONE at CP-R4 and CP-R5 remains
deferred. ADR-0014 selects `:workspace` as a composition layer only; it owns
no new record or protocol format.

## Work completed

Planning state is reconciled and validated. ADR-0014, the first two-person
demo script, and CP-W1/CP-W2 task scope are recorded. CP-W2 implementation is
complete in `:workspace`; frozen production boundaries remain unchanged.

## Verification

Planning-state validators and the CP-0076 checkpoint passed before code
changes. CP-W1/CP-W2 focused tests, the full strict root build, and all
repository validators now pass.

## Current failures

Physical operation, retries, reconnect, discovery, membership, background
sync, and broader CAF behavior remain unclaimed. CP-W2 has no known
verification failure.

## Work completed

Added CP-W2 `sync host` and `sync join` to the generated `synesis-workspace`
launcher. Host uses the sole configured peer; join authenticates and checks the
expected host before creating configuration, then performs exactly one CP-R4
sync. Link, `:cli`, and `:project-record` production code were not changed.

## Immediate next action

Review CP-W2 evidence and approve or promote CP-W3 separately; do not implement
search, inspection, or broader CAF behavior in this slice.
