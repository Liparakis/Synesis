# Current Task

## Identity

- Task ID: SYN-017
- Status: ACTIVE
- Priority: P1
- Started checkpoint: CP-0225
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0001, ADR-0008

## Objective

Organize the flat `workspace.application` package into responsibility-based application packages without changing runtime behavior.

## Immediate slice

Inventory and implementation complete. Map: `application.agent`, `application.capability`, `application.control`, `application.guardrail`, `application.hook`, `application.integration`, `application.project`, `application.provider`, `application.sync`, `application.task`, and `application.workspace`. The root retains `ConstraintApplicationService`, `ProjectApplicationService`, `ProviderApplicationService`, and `ProviderSessionBindingService` as cross-responsibility facades.

## Verification target

No stale production FQNs, package cycles, surface changes, or module dependency changes after the completed move set.

## Immediate next action

Re-read the committed package map at `27595c1`; keep `SYN-014E` paused and do not activate later structural or quality tasks automatically.

## Work completed

`SYN-015` and `SYN-016` are complete. `SYN-017` remains the sole ACTIVE task with implementation committed at `27595c1`. `coordination.application` is one service; the 30-file application cluster was `workspace.application`. `SYN-014E` remains paused.

## Current failures

No verified failures. The first MCP rerun encountered a transient Gradle result-file race after a timed-out chained invocation; the clean rerun and final root check passed. An unrelated deletion of `mcp/src/main/java/org/synesis/mcp/transport/stdio/package-info.java` was present at startup and remains outside this task.
