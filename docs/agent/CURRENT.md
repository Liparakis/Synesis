# Current Task

## Identity

- Task ID: SYN-007.1
- Status: DONE
- Priority: P0
- Started checkpoint: CP-0093
- Latest checkpoint: CP-0094
- Responsible agent: fresh coding agent
- Related decisions: ADR-0018

## Objective

Real Claude Code PreToolUse Contract Conformance (`PreToolUse` JSON framing, exit 0 denial output, absolute path boundary resolution).

## Planning state

SYN-007.1 implementation, testing, verification, and documentation are complete.

## Work completed

- Aligned `ClaudeCodeHookAdapter` with official Claude Code v2.1+ `PreToolUse` command hook contract: returns `hookSpecificOutput` with `permissionDecision: "deny"` and `permissionDecisionReason`.
- Updated `WorkspaceCli` hook command to **exit with code 0** on JSON denial responses as required by Claude Code's hook engine.
- Implemented `ClaudeCodeHookAdapter.resolveRelativePath` converting absolute CWD/path inputs into project-relative normalized paths while rejecting targets outside project boundaries or across Windows drive letters.
- Implemented `ProjectConstraint.filterEffectiveActive` to exclude superseded active constraints prior to action checks.
- Created `docs/integration/claude-code-hook.json` demonstrating project-local hook configuration.
- Updated `ClaudeCodeHookAdapterTest` and `scripts/run-synesis-guardrail-experiment.ps1`.
- Created ADR-0018 and updated `docs/development/current-state.md`.
- Strictly verified build and test suite (`39 actionable tasks: 30 executed, 6 from cache, 3 up-to-date`).

## Verification

- Automated tests: `ClaudeCodeHookAdapterTest`, `ProjectConstraintTest`, `WorkspaceSyncProcessTest`.
- Automated experiment: `scripts/run-synesis-guardrail-experiment.ps1`.
- Command: `.\gradlew.bat clean check --dependency-verification=strict`.

## Current failures

None.

## Immediate next action

Record checkpoint CP-0094, set SYN-007.1 to DONE, and commit.
