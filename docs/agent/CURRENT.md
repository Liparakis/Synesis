# Current Task

## Identity

- Task ID: SYN-019
- Status: ACTIVE
- Priority: P1
- Started checkpoint: CP-0230
- Responsible agent: primary architecture-closure engineer
- Related decisions: ADR-0001, ADR-0008; no architecture change

## Objective

Close the workspace application package architecture test without changing runtime behavior or public surfaces.

## Immediate slice

Inspection found one root production type, `ProjectApplicationService`, and the
architecture test had a stale five-type allowlist after the completed package
refactor. The allowlist was narrowed to the one deliberate stable facade.

## Verification target

Only deliberate stable application facades remain at the root; internal
responsibilities remain in subpackages; no runtime, module, CLI, MCP, provider,
schema, or event-format changes occurred.

## Immediate next action

Leave the unrelated README edit untouched; no further architecture changes are
authorized under this closure task.

## Work completed

`SYN-018` hygiene is complete. `SYN-019` is corrected in `a87d3d8`; no
production type moved and the stale allowlist was narrowed to
`ProjectApplicationService.java`. `SYN-014E` remains paused.

## Current failures

All requested architecture, module, root Gradle, Go, MCP, CLI, provider, and
hygiene checks pass. An unrelated README edit is uncommitted and was preserved;
it is outside this task and must not be reverted or included.
