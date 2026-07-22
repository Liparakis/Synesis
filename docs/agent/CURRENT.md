# Current Task

## Identity

- Task ID: SYN-009D
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0131
- Latest checkpoint: CP-0131
- Responsible agent: fresh coding agent
- Related decisions: ADR-0024, ADR-0025, ADR-0026

## Objective

Replace the versioned Synesis bootstrap layout with one stable flat
installation root, safe sibling staging, one temporary rollback root, stable
PATH ownership, legacy migration, and provider launcher resolution through
PATH or the stable fallback.

## Architecture brief

- Mode: EVOLUTION.
- Baseline: keep the Go standard-library bootstrapper and existing Java bundle;
  change only installation ownership and activation semantics.
- Canonical roots: Windows `%LOCALAPPDATA%\Synesis`, Linux
  `$XDG_DATA_HOME/Synesis` or `~/.local/share/Synesis`, macOS
  `~/Library/Application Support/Synesis`.
- Bundle: stable root contains `bin`, `runtime`, application files, and
  `VERSION`; the bundle launcher is the stable launcher.
- Recovery: extract to sibling staging, validate, move the old root to one
  rollback sibling, activate staged root, validate stable `version` and
  `doctor`, then remove rollback.
- Legacy: detect `current` and `versions/<version>` only for one safe migration;
  preserve the legacy root until the new bundle validates.
- Provider boundary: Java provider commands use PATH `synesis`/`synesis.cmd`
  first, then the OS stable fallback; no version path is allowed.

## Evidence ledger

- VERIFIED: stable-root activation, sibling staging and one rollback root are
  implemented in `bootstrap/main.go`, with signed-manifest, checksum,
  extraction, version, launcher, doctor, rollback, and uninstall checks.
- VERIFIED: Go tests and vet, three cross-platform builds, strict Java checks,
  disposable-root migration/rollback tests, and native Windows archive smoke
  all pass.
- VERIFIED: local Windows migration completed only after automated and
  disposable-root verification; the migrated root is flat, legacy markers are
  gone, Link identity files were preserved, PATH is user-owned, and both
  launchers resolve from the stable root.
- USER-STATED: one stable root, rollback-on-failure, user PATH ownership,
  provider version-path prohibition, and local Windows migration are required.
- DERIVED: one root plus sibling staging/rollback removes the second source of
  truth while preserving atomic activation and rollback.
- UNKNOWN: native Linux/macOS PATH/profile behavior is not executable on this
  Windows host; cross-platform Go builds/tests and documented user-local
  profile behavior are the available evidence.

## Work completed

- Promoted SYN-009D from the activated `SL-D-024` packaging capability and
  recorded ADR-0026.
- Preserved SYN-011 as VERIFYING; it is no longer the active task.
- Replaced versioned installation with flat stable-root activation, including
  legacy migration, rollback recovery, user PATH ownership, stable provider
  launcher resolution, doctor, uninstall, and project preservation.
- Completed the local Windows migration from `current`/`versions/` to
  `%LOCALAPPDATA%\Synesis` and preserved `Link\identity.bin` and
  `Link\identity.pub`.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Repository resume | PASS | `scripts/agent-resume.ps1` before promotion |
| Deferred register | PASS before promotion | resume output; promotion recorded here |
| Architecture review | PASS | ADR-0026 and this brief |
| Repository resume and fixture validators | PASS | startup and final validation |
| Go bootstrap tests and vet | PASS | `go test ./...`; `go vet ./...` |
| Cross-platform bootstrap builds | PASS | Windows amd64, Linux amd64, macOS arm64 |
| Java strict verification | PASS | `gradlew.bat clean check --dependency-verification=strict --no-daemon` |
| Disposable migration/rollback and project preservation | PASS | `go test ./...` in `bootstrap` |
| Native Windows archive smoke | PASS | `TestNativeRealBundleInstallation` |
| Local Windows migration | PASS | stable root, launcher, doctor, PATH, and legacy-marker inspection |

## Current failures

- Native Linux/macOS execution is unavailable on this Windows host; their
  user-local PATH behavior is covered by implementation policy and Go tests,
  not native smoke.
- The worktree contains unrelated pre-existing Antigravity investigation
  changes; they must not be folded into a packaging commit.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
review the generated checkpoint and mark SYN-009D complete if the durable
state remains consistent.
