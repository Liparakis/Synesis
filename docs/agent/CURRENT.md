# Current Task

## Identity

- Task ID: SYN-017
- Status: ACTIVE
- Priority: P1
- Started checkpoint: pending activation checkpoint
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0001, ADR-0008

## Objective

Organize the flat `workspace.application` package into responsibility-based application packages without changing runtime behavior.

## Immediate slice

Inventory the 30 workspace application types and their FQN references, then define the first small coherent move set. Do not edit production code until the reference inventory is recorded.

## Verification target

No stale production FQNs, package cycles, surface changes, or module dependency changes after each move set.

## Immediate next action

Run the activation checkpoint after confirming exactly one ACTIVE task, then inventory `workspace.application` imports, fully qualified references, tests, launch strings, reflection, resources, scripts, and test-used documentation.

## Work completed

`SYN-015` and `SYN-016` are complete. `SYN-017` is the sole ACTIVE task. `coordination.application` is one service; the 30-file application cluster is `workspace.application`. `SYN-014E` remains paused.

## Current failures

No verified failures. An unrelated deletion of `mcp/src/main/java/org/synesis/mcp/transport/stdio/package-info.java` was present at startup and remains outside this task.
