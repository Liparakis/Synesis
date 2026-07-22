# Current Task

## Identity

- Task ID: SYN-009C
- Status: ACTIVE (planning only; no active implementation work)
- Priority: P0
- Started checkpoint: CP-0102
- Latest checkpoint: CP-0102
- Responsible agent: fresh coding agent
- Related decisions: ADR-0017, ADR-0018, ADR-0019, ADR-0020, ADR-0021

## Objective

SYN-009B is closed. There is no active implementation work; SYN-009C is the
next queued task for portable ZIP and clean-machine smoke-test planning.

## Planning state

SYN-009B was promoted after SYN-009A completion at CP-0099 and is DONE at
CP-0102. Provider lifecycle uses the existing unified CLI, application-service,
project-layout, and shared-guardrail boundaries. SYN-009C is queued only to
keep the repository contract's single ACTIVE task explicit; implementation has
not started.

## Architecture brief

- Mode: EVOLUTION.
- Baseline: retain the four-module modular monolith; `:cli` is the only composition root and public launcher.
- Boundaries: `:workspace` owns public application services and domain orchestration; it does not depend on Picocli or console output. `:project-record` remains domain/storage only; `:link` remains transport/identity only.
- State: discovered `<project>/.synesis/project.json` is shareable metadata; `.synesis/shared` is shareable; `.synesis/local` contains profile, records, providers, and runtime state. No secret or absolute path enters `project.json`.
- Evidence: current build files and CP-0095 verify the existing module graph and CLI/workspace split; load/scale and physical deployment claims are UNKNOWN and do not change this local modular baseline.
- Rejected: a new module or service split; both add coordination without independent ownership, scaling, or release evidence.

## Work completed

SYN-009A is complete at CP-0099. SYN-009B is complete at CP-0102.

- Added `ProjectApplicationService` with upward discovery, malformed/partial
  state rejection, atomic metadata initialization, identity bootstrap, local
  profile paths, and one-peer project configuration.
- Added structured workspace services for constraints, guardrails, hooks, and
  synchronization; provider adapters now live in bounded integration packages.
- Retired `WorkspaceCli`, `WorkspaceOperations`, and `DecisionRecordCli`; the
  unified `:cli` launcher is the only supported command entry point.
- Deleted obsolete `DecisionRecordCliTest` and `WorkspaceCliTest` compatibility
  tests. Rewrote valid host/join, reconciliation, invitation-validation,
  failure-classification, and process lifecycle coverage as
  `UnifiedCliSyncProcessTest` against the generated `synesis` launcher.
- Removed the old `WorkspaceProcessMain` and `AbruptHostProcess` bridges; the
  retained process checks start the generated launcher and terminate its full
  process tree when testing abrupt host loss.
- Centralized identical Claude and Antigravity path resolution in
  `ProjectPathResolver`.
- Added provider support levels, static registry, Antigravity and Claude Code
  integrations, local metadata, atomic JSON configuration merge, provider
  application service, lifecycle commands, synthetic checks, and provider-aware
  doctor diagnostics.
- Added provider boundary, management, doctor, Claude Code integration, and
  ADR-0021 documentation.
- Wired the requested command tree into the sole `:cli` `synesis` launcher,
  including `init`, project/constraint/sync/check-action/hook commands,
  `help`, and the version placeholder.
- Added package-boundary checks, project layout/CLI/package docs, ADR-0020,
  and project discovery/init tests.
- Corrected malformed invitation mapping to `INVITE_INVALID` and hardened the
  generated Windows launcher test helper for URL query separators and wildcard
  arguments.

## Verification

- Promotion prerequisite: `scripts/agent-resume.ps1` passed after SYN-009A was
  promoted as the sole ACTIVE task.
- Focused tests: `ProjectApplicationServiceTest`, hook adapter tests,
  project/application-service tests, and `cli:test` PASS.
- Architecture check and strict Javadocs PASS.
- Required verification: `./gradlew.bat clean check --dependency-verification=strict` PASS
  (34 actionable tasks); `./gradlew.bat :cli:installDist --dependency-verification=strict` PASS.
- Generated launcher verification: `synesis init` clean/repeat, constraint and
  check-action help, and both hook commands PASS; only `cli/build/install/synesis`
  exists after clean build.
- Provider verification: registry/lifecycle tests PASS; disposable Antigravity
  and Claude Code install/status/uninstall checks PASS; idempotent reinstall,
  synthetic block/allow checks, doctor warnings, and malformed-config rejection
  PASS.
- Unified launcher process verification: five `UnifiedCliSyncProcessTest`
  scenarios PASS, including APPLIED/DUPLICATE, reconciliation/check-action,
  wrong-host/malformed/mismatch safety, missing-record/abrupt-host handling,
  and guided invitation validation/replay.

## Current failures

None.

## Immediate next action

Review SYN-009C's portable-distribution acceptance criteria before beginning implementation.
