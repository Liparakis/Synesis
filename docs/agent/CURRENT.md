# Current Task

## Identity

- Task ID: SYN-016
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0212
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0001, ADR-0008

## Objective

Organize the flat coordination domain package into responsibility-based packages without changing runtime behavior.

## Immediate slice

Move only coordination domain types into the approved responsibility packages, then validate and checkpoint.

## Verification target

No stale production FQNs, package cycles, surface changes, or module dependency changes after the move.

## Immediate next action

Implementation and validation are complete. The next action is to checkpoint and commit SYN-016.

## Work completed

`SYN-015` is complete. `SYN-016` is the sole active task. The coordination domain map is capability, task, ownership, prediction, integration, speculation, and command. `SYN-014E` remains paused.

## Current failures

No verified failures. An unrelated deletion of `mcp/src/main/java/org/synesis/mcp/transport/stdio/package-info.java` was present at startup and remains outside this task.
