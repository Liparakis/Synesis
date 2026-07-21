# Current Task

## Identity

- Task ID: SYN-009A
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0096
- Latest checkpoint: CP-0096
- Responsible agent: fresh coding agent
- Related decisions: ADR-0017, ADR-0018, ADR-0019, ADR-0020

## Objective

Unified CLI, application services, project initialization, and local state layout.

## Planning state

SYN-009A is promoted after CP-0095. No production code has been changed for this task yet.

## Architecture brief

- Mode: EVOLUTION.
- Baseline: retain the four-module modular monolith; `:cli` is the only composition root and public launcher.
- Boundaries: `:workspace` owns public application services and domain orchestration; it does not depend on Picocli or console output. `:project-record` remains domain/storage only; `:link` remains transport/identity only.
- State: discovered `<project>/.synesis/project.json` is shareable metadata; `.synesis/shared` is shareable; `.synesis/local` contains profile, records, providers, and runtime state. No secret or absolute path enters `project.json`.
- Evidence: current build files and CP-0095 verify the existing module graph and CLI/workspace split; load/scale and physical deployment claims are UNKNOWN and do not change this local modular baseline.
- Rejected: a new module or service split; both add coordination without independent ownership, scaling, or release evidence.

## Work completed

SYN-009A implementation is complete pending checkpoint creation.

- Added `ProjectApplicationService` with upward discovery, malformed/partial
  state rejection, atomic metadata initialization, identity bootstrap, local
  profile paths, and one-peer project configuration.
- Added structured workspace services for constraints, guardrails, hooks, and
  synchronization; provider adapters now live in bounded integration packages.
- Retired `WorkspaceCli` as a launcher by moving the internal compatibility
  operation path to `WorkspaceOperations`; removed `:workspace` and
  `:project-record` application plugins and distributions.
- Wired the requested command tree into the sole `:cli` `synesis` launcher,
  including `init`, project/constraint/sync/check-action/hook commands,
  `help`, and the version placeholder.
- Added package-boundary checks, project layout/CLI/package docs, ADR-0020,
  and project discovery/init tests.

## Verification

- Promotion prerequisite: `scripts/agent-resume.ps1` passed after SYN-009A was
  promoted as the sole ACTIVE task.
- Focused tests: `ProjectApplicationServiceTest`, hook adapter tests,
  `WorkspaceCliTest`, and `cli:test` PASS.
- Architecture check and strict Javadocs PASS.
- Required verification: `./gradlew.bat clean check --dependency-verification=strict` PASS
  (34 actionable tasks); `./gradlew.bat :cli:installDist --dependency-verification=strict` PASS.
- Generated launcher verification: `synesis init` clean/repeat, constraint and
  check-action help, and both hook commands PASS; only `cli/build/install/synesis`
  exists after clean build.

## Current failures

None.

## Immediate next action

Commit the verified SYN-009A implementation and documentation; do not promote SYN-009B or SYN-009C.
