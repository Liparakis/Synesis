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

Preflight and initial inventory are complete. Maintained documentation and script references are being reconciled; historical checkpoints, evidence, ADRs, and signed records are excluded from modernization edits.

## Verification target

No broken maintained links or script references, no machine-specific paths in user-facing docs, no stale canonical-provider command examples, no inaccurate MCP tool-count claims, and no CLI/MCP/provider behavior changes.

## Immediate next action

Create the maintained-file inventory and classify current versus historical documentation before editing any user-facing Markdown.

## Work completed

`SYN-015` and `SYN-016` are complete. The prior `SYN-017` package slice is preserved as historical implementation work, but its current architecture test must not be repaired as part of hygiene. `SYN-014E` remains paused.

## Current failures

Baseline root `check --no-daemon` failed at `a67dd00`: the workspace package architecture test expects root facades that the current source has moved into subpackages, and parallel test execution also reported missing in-progress binary result files. The requested hygiene task does not alter production package behavior; final verification must report whether these failures clear under a controlled rerun. The prompt's `bootstrap\go` path is absent; equivalent Go commands run from the `bootstrap` module with the installed `go` executable.
