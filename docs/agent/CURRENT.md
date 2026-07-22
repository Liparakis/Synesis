# Current Task

## Identity

- Task ID: SYN-010A
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0110
- Latest checkpoint: CP-0121
- Responsible agent: fresh coding agent
- Related decisions: ADR-0017, ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0022, ADR-0023, ADR-0024, ADR-0025

## Objective

Prepare the existing repository for safe public GitHub developer-preview
visibility without selecting a license or publishing before the license gate.

## Planning state

SYN-009B was promoted after SYN-009A completion at CP-0099 and is DONE at
CP-0102. SYN-009B.1 is VERIFYING as an EVOLUTION of the existing provider lifecycle and
hook boundary. It adds one Codex integration under `:workspace`, reuses the
shared path resolver and action guardrail, and writes only project-local
`.codex/hooks.json`. SYN-009C is complete at CP-0110. SYN-010A is active;
the AGPL-3.0-only license decision is recorded and publication remains
unperformed pending external review and explicit push authorization.

## Architecture brief

- Mode: EVOLUTION.
- Baseline: retain the four-module modular monolith; `:cli` is the only composition root and public launcher.
- Boundaries: `:workspace` owns public application services and domain orchestration; it does not depend on Picocli or console output. `:project-record` remains domain/storage only; `:link` remains transport/identity only.
- State: discovered `<project>/.synesis/project.json` is shareable metadata; `.synesis/shared` is shareable; `.synesis/local` contains profile, records, providers, and runtime state. No secret or absolute path enters `project.json`.
- Evidence: current build files and CP-0095 verify the existing module graph and CLI/workspace split; load/scale and physical deployment claims are UNKNOWN and do not change this local modular baseline.
- Rejected: a new module or service split; both add coordination without independent ownership, scaling, or release evidence.

## Work completed

SYN-009A is complete at CP-0099. SYN-009B is complete at CP-0102.
SYN-009C is complete at CP-0110 after the external-style trial fixes.

- Promoted SYN-009B.1 as the sole ACTIVE task after reviewing the attached
  Codex contract and the installed `codex-cli 0.140.0` baseline.

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
- Added the Codex `EXPERIMENTAL` registry/lifecycle entry, bounded
  `CodexApplyPatchParser`, `CodexHookAdapter`, shared path/guardrail evaluation,
  project-local `.codex/hooks.json` installation with Windows command shape,
  `synesis hook codex`, trust-degraded diagnostics, synthetic tests, generated
  launcher process coverage, and the 20-call performance measurement.
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
- SYN-009B.1 focused verification: parser, adapter, provider lifecycle, CLI,
  generated launcher, and performance tests PASS; performance evidence is
  `CODEX_HOOK_LATENCY_P50_MS=1.247` and `CODEX_HOOK_LATENCY_P95_MS=1.806` in
  the 20-call run. Disposable generated-launcher Codex list/install/status/
  doctor/uninstall checks PASS with unrelated `.codex` configuration preserved.
- Real Codex validation is not complete: the untrusted run skipped the project
  hook and changed its disposable file; the bypass diagnostic did not capture
  a payload. Codex remains EXPERIMENTAL/DEGRADED/REVIEW_REQUIRED.
- Post-CP-0109 trial: the real Windows archive passes bootstrap install,
  bundled version/init, provider lifecycle, doctor, project preservation, and
  uninstall in an isolated D: workspace. CI manifest verification was corrected
  to read the signed sidecar that the signing job actually creates. Unix native
  execution remains CI-only in this Windows environment.
- Final recheck: Java `clean check --dependency-verification=strict` passed with
  41 actionable tasks; Go native test/vet and all six cross-compiles passed;
  workflow YAML, matrix, sidecar path, deferred register, fixture validator,
  doctor, and whitespace checks pass.

## Unresolved limitations

Real `/hooks` trust review and a qualifying authenticated Codex payload/run
remain pending. This is intentionally not converted into a stronger support
claim. The distribution gate preserves this limitation. Public production
key replacement, Authenticode, Apple Developer ID signing, and notarization
are also deferred; no public release is claimed.

## SYN-010A verification

- Publication audit and license-decision-required document added.
- README, SECURITY.md, CONTRIBUTING.md, and `.gitignore` prepared for an early
  developer preview; no open-source or production claim was added.
- Equivalent current-tree/reachable-history secret scan, focused path scan,
  repository metadata review, workflow review, and repository validators pass.
- The owner selected GNU AGPL v3.0 only (`AGPL-3.0-only`); the unfinished-license
  gate is cleared. Publication remains unperformed pending explicit push
  authorization and review of author metadata and the remote target.
- CI wrapper repair: `gradlew` is now tracked executable (`100755`); Git Bash
  wrapper smoke and full strict check PASS.
- CI dependency repair: verification metadata now covers the Linux Netty QUIC
  native JAR; Unix CLI tests invoke the Unix launcher instead of `cmd.exe`.
  Native Windows strict clean check PASS; Linux remains CI validation.
- Verification metadata now also covers the macOS x86_64 Netty QUIC native JAR;
  strict macOS-classifier `:cli:startScripts` resolution PASS.
- Linux test repairs: Claude path fixtures are OS-native and QUIC tests reuse
  the shared platform-aware TLS helper. Targeted workspace and link tests PASS.
- Bundle smoke repair: asserts the Unix source launcher is executable and
  restores its mode after Gradle `tarTree` extraction; it does the same for
  bundled `runtime/bin/java`. Native Windows bundle smoke PASS.
- Unix launcher process tests now avoid Windows-only literal argument quoting;
  the affected Codex and unified-sync process tests PASS on Windows.
- Abrupt process-loss integration test now uses a short test-only liveness
  policy, keeping its bounded terminal-classification assertion deterministic
  under CI runner startup load; focused test passes three consecutive runs.
- Secondary review remains required for personal commit metadata and the
  canonical remote target. No public push or history rewrite was performed.

## Immediate next action

With the license decision recorded, rerun the publication audit and obtain
explicit push authorization only after reviewing author metadata and the target
repository.
