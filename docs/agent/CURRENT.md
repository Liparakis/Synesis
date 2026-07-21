# Current Task

## Identity

- Task ID: SYN-007
- Status: DONE
- Priority: P0
- Started checkpoint: CP-0092
- Latest checkpoint: CP-0093
- Responsible agent: fresh coding agent
- Related decisions: ADR-0017

## Objective

Clean Typed Constraint Model and Baseline-vs-Synesis Validation (`SDR2`, `ConstraintPayload`, removal of legacy inference, baseline-vs-synesis experiment).

## Planning state

SYN-007 implementation, testing, verification, and documentation are complete.

## Work completed

- Evolved canonical record format to `SDR2` (`MAGIC = 0x53445232`, `VERSION = 2`) with explicit `RecordType` (`DECISION` vs `PROJECT_CONSTRAINT`) and binary `ConstraintPayload`.
- Removed all unreleased development legacy compatibility behavior (`LEGACY_INFERRED`, title-prefix matching `CONSTRAINT:`).
- Updated `ProjectConstraint.fromRecord` to check explicit record typing and typed payloads. Added regression test proving title prefix records with type `DECISION` return `ALLOWED`.
- Enhanced `ClaudeCodeHookAdapter` with observable `WARNING` and `UNSUPPORTED` diagnostics on stderr while allowing execution.
- Created `scripts/run-synesis-guardrail-experiment.ps1` and `docs/validation/baseline-vs-synesis-experiment.md` proving protected file integrity preservation and zero false positives.
- Created ADR-0017 and updated `docs/development/current-state.md`.
- Verified build and test suite (`39 actionable tasks: 30 executed, 8 from cache, 1 up-to-date`).

## Verification

- Automated tests: `DecisionRecordTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, `WorkspaceSyncProcessTest`.
- Automated experiment: `scripts/run-synesis-guardrail-experiment.ps1`.
- Command: `.\gradlew.bat clean check --dependency-verification=strict`.

## Current failures

None.

## Immediate next action

Record checkpoint CP-0093, set SYN-007 to DONE, and commit.
