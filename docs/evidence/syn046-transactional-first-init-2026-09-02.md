# SYN-046 evidence: first initialization safety gate

Date: 2026-09-02

## Defect reproduced

An initialized Git checkout with an uncommitted `bootstrap.txt` was passed to
`ProjectApplicationService.init`. Before the fix, initialization returned
`CONTROL_CHECKOUT_DIRTY` but had already created `.synesis`. The regression
asserts that the checkout remains at its original `HEAD`, retains the
uncommitted file, and contains no new `.synesis` state.

## Correction

The Git path now calls `ManagedBaselineTransactionService.prepare` before
creating project-local directories. The non-Git internal-fixture path retains
its existing direct metadata creation because it intentionally bypasses the
user-facing Git preflight.

## Verification

- `.\gradlew.bat :workspace:test --tests
  'org.synesis.workspace.application.ProjectApplicationServiceTest.rejectsDirtyGitCheckoutWithoutCreatingProjectState'
  --no-daemon` — RED before the production change; GREEN after it.
- `.\gradlew.bat :workspace:test --tests
  'org.synesis.workspace.application.ProjectApplicationServiceTest' --no-daemon`
  — PASS, 13 tests.
- `.\gradlew.bat :cli:test --tests
  'org.synesis.cli.project.InitCommandTest' --no-daemon` — PASS.
- `git diff --check` — PASS.
- `scripts/agent-validate-deferred.ps1` — PASS.

The first Gradle attempt without the documented temporary directory failed
before test execution with `Unable to establish loopback connection`; the
retries using `TEMP` and `TMP=C:\Temp\synesis-gradle` executed the tests.

## Boundary

This evidence covers the pre-baseline partial-state defect only. Provider
migration diagnostics, stale or ambiguous session cleanup, and the incomplete
KogMaw worker run remain separate and unresolved.
