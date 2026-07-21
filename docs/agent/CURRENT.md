# Current Task

## Identity

- Task ID: SYN-006
- Status: DONE
- Priority: P0
- Started checkpoint: CP-0091
- Latest checkpoint: CP-0092
- Responsible agent: fresh coding agent
- Related decisions: ADR-0016

## Objective

Constraint Hardening and First Enforceable Harness Integration (`ProjectConstraint`, `ScopeMatcher`, `ClaudeCodeHookAdapter`, portable Gradle defaults).

## Planning state

SYN-006 implementation, testing, verification, and documentation are complete.

## Work completed

- Implemented `ProjectConstraint` typed constraint model with `BLOCK` and `WARN` effects, `ACTIVE`/`INACTIVE`/`SUPERSEDED` statuses, and `LEGACY_INFERRED` fallback.
- Implemented `ScopeMatcher` deterministic path normalization and wildcard scope matching engine (`*` and `**` glob wildcards, rejection of `..` traversal and absolute paths).
- Implemented `ClaudeCodeHookAdapter` translating Claude Code pre-tool hook JSON events into Synesis action checks (`{"decision": "deny"}` or `{"decision": "allow"}`).
- Repaired Gradle defaults in `gradle.properties` (`-Xmx2g`, `-XX:+UseG1GC`, `-XX:+ParallelRefProcEnabled`) and added `-PsynesisTestForks` property override.
- Added `ScopeMatcherTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, and updated `WorkspaceSyncProcessTest.projectReconciliationAndCheckActionWorkflow`.
- Created ADR-0016 and updated `docs/development/current-state.md`.
- Strictly verified build and test suite (`39 actionable tasks: 27 executed, 12 from cache`).

## Verification

- Automated tests: `ScopeMatcherTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, `WorkspaceSyncProcessTest`.
- Command: `.\gradlew.bat clean check --dependency-verification=strict`.

## Current failures

None.

## Immediate next action

Record checkpoint CP-0092, set SYN-006 to DONE, and commit.
