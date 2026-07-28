# ADR-0036: Bounded Pre-Merge Compatibility Checks

- Status: Accepted
- Date: 2026-07-28
- Decision: SYN-023

## Context

Immutable task snapshots can still conflict when their bases are stale or
their changed paths overlap. The existing integration flow created an external
worktree, but unsupported projects were treated as passing and metadata
invariants were not checked before preparation.

## Decision

Add a shared compatibility service that checks explicit snapshot claims,
changed-path overlap, exact contract revisions, out-of-band paths, ancestry,
and configured test results. Integration orchestration rejects stale-base and
overlapping snapshot metadata before creating or mutating an integration
worktree. Python projects use the bounded adapter `python -m pytest -q`.
Unknown project types fail closed instead of being reported as tested.

## Consequences

Checks are deterministic and actionable without inferring general language
interfaces. The control checkout remains unchanged until the existing guarded
fast-forward stage. Full claim provenance and direct-write publication checks
remain to be wired from session snapshots in the next slice.
