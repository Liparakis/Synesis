# SYN-057 daemon routing implementation slice — 2026-09-11

## Scope

This slice implements only the approved first routing seam:

- SLA2 v2 structurally reads the signed `ReturnTarget.projectId`, resolves it
  through `KnownProjectRegistry`, starts or reuses that exact local project
  runtime, and dispatches the untouched original URI to that runtime.
- SLA2 v1 keeps the existing live-runtime behavior: zero unresolved, one
  dispatched, and multiple ambiguous.
- SLO1 candidates come only from validated, known local registry entries. Zero
  candidates is unresolved, one starts or reuses directly, and multiple create
  a bounded ephemeral selection context without dispatching.
- Selection completion is invitation-digest-bound, candidate-bound,
  one-shot, synchronized, and fail-closed when the selected project is no
  longer eligible.

No browser/project-selection UI, persistent current-project state, daemon
coordination authority, filesystem crawl, broadcast routing, or Link crypto
change was added. The selected runtime remains responsible for full Link
signature, expiry, replay, identity, membership, and operation verification.

## Evidence

- `LocalProjectSelectionStoreTest`: invitation binding, unknown/invalid
  projects, expiry, capacity, and concurrent first-wins consumption.
- `DaemonServerRoutingTest`: two-project v2 target-only dispatch, unknown
  target fail-closed behavior, ambiguous SLO1 selection, and dispatch only
  after a valid choice.
- `DaemonServerTest`: authenticated daemon request behavior and valid empty-
  registry SLO1 unresolved behavior.
- Manual compile of the changed daemon production/test sources with `javac`
  passed.
- JUnit Platform execution of the three focused daemon test classes passed:
  9 tests successful, 0 failed.
- `git diff --check` passed.

The repository Gradle compile was attempted twice, including `--no-daemon`,
but Gradle failed before compilation with `java.io.IOException: Unable to
establish loopback connection`. This is an environment-level verification
blocker, not a source-test result.

## Remaining boundary

The internal selection completion seam is intentionally not exposed as a new
browser or general daemon wire operation in this slice. Runtime restart still
loses pending Link operation state; this routing work does not add continuity.
