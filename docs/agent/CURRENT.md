# Current Task

## Identity

- Task ID: SYN-018
- Status: ACTIVE
- Priority: P1
- Started checkpoint: pending hygiene checkpoint
- Responsible agent: primary repository-hygiene engineer
- Related decisions: ADR-0001, ADR-0008; no architecture change

## Objective

Inventory and clean maintained documentation, Markdown, and repository scripts without changing production behavior or public surfaces.

## Immediate slice

Inventory, documentation cleanup, generated-instruction update, script review,
and hygiene-check implementation are complete. Historical checkpoints,
evidence, ADRs, and signed records were excluded from modernization edits.

## Verification target

No broken maintained links or script references, no machine-specific paths in user-facing docs, no stale canonical-provider command examples, no inaccurate MCP tool-count claims, and no CLI/MCP/provider behavior changes.

## Immediate next action

Create the final durable checkpoint after recording the focused verification
passes and the pre-existing full-check architecture-test blocker.

## Work completed

`SYN-015` and `SYN-016` are complete. The prior `SYN-017` package slice is
preserved as historical implementation work, but its current architecture test
was not repaired as part of hygiene. `SYN-014E` remains paused. Documentation
commit `4b7f530` and hygiene-check commit `59f7c63` are complete. No safe script
consolidation was identified.

## Current failures

The exact final root `check --no-daemon` fails only at the pre-existing
`WorkspaceApplicationPackageArchitectureTest.rootContainsOnlyStableFacades`
assertion. The earlier parallel-test result-file race is gone in the sequential
rerun. Focused MCP tests, Go test/vet, CLI help/version, provider list, init
instruction generation, and `repositoryHygieneCheck` pass. The prompt's
`bootstrap\go` path is absent; equivalent Go commands run from the `bootstrap`
module with the installed `go` executable. This hygiene task does not alter
production package behavior to hide the architecture-test blocker.
