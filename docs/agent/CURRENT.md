# Current Task

## Identity

- Task ID: SYN-013B
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0151
- Latest checkpoint: CP-0154
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027, ADR-0028, ADR-0029

## Objective

Implement automatic project-scoped provider session binding for Codex and
Antigravity, with a fail-closed workspace boundary before any meaningful hook
mutation.

## Immediate slice

Persist distinct provider session, supervisor, and worker identities; allocate
and resume a detached Git worktree; expose binding/workspace/interception
diagnostics; preserve project and node identity; and fail closed on ambiguity,
mismatch, missing interception, or control-checkout cwd.

## Evidence ledger

- VERIFIED: `NodeIdentity`, SDR2 `DecisionRecord`, `ScopeMatcher`, `ActionGuardrail`,
  provider hook adapters, `.synesis/local` layout, Git worktree support, and
  the bounded Link application-stream seam already exist.
- USER-STATED: two independent agents must coordinate without owner-scope
  mutation and without a global AI owner.
- DERIVED: a new bounded coordination module is required; Link and
  `DecisionStore` are not coordination logs.
- ASSUMED: loopback HTTP commands plus SSE replay are sufficient for the first
  two-process demonstration; remote HTTPS is a later validation task.

## Work completed

- Implemented root `AGENTS.md` bootstrap and managed-section replacement in
  `ProjectApplicationService`; malformed markers fail closed and unrelated
  text is preserved.
- Added focused fresh, repeated, existing-file, and malformed-marker tests.
- Bundled CLI smoke passed: first init created `AGENTS.md`, repeated init kept
  its SHA-256 unchanged, and exactly one marker pair remained.
- Full `./gradlew.bat check --no-daemon` passed.

- SYN-013A AGENTS.md bootstrap is complete and its focused tests pass.
- Added the first project-local provider session binding implementation and
  focused persistence/hook tests; full integration and migration verification
  remain for this task.
- Added ADR-0030 and provider-boundary, bootstrap-protocol, maturity,
  installation, migration, and zero-touch architecture updates. The global
  Link identity is documented as separate machine-level diagnostic state.
- `:coordination:check` and root `check` pass. The Windows bundle was rebuilt
  and installed locally; the external project preserved its project/node IDs
  while provider reinstall created distinct Codex and Antigravity bindings.

- Added signed command envelopes, coordinator service idempotency, loopback
  HTTP command handling, and SSE replay with an exclusive sequence cursor.
- Added focused command/subscriber and HTTP/SSE tests; `:coordination:check`
  passes with strict Javadocs and static analysis.
- Root `.\gradlew.bat check --no-daemon` also passes, including workspace
  architecture validation and the existing CLI/link/project-record checks.
- Added isolated detached Git worktree metadata and a fail-closed gate that
  rejects `git diff --check` failures and unmerged index states.
- Added an end-to-end lifecycle test through `RETIRED` with ordered sequence
  evidence and local supervisor replay.
- Final root `.\gradlew.bat check --no-daemon` passed (42 actionable tasks).
- Final `.\gradlew.bat :coordination:check --no-daemon` passed after contract
  bound and subscription-race fixes.
- Real two-process CLI acceptance passed with separate profiles, node
  identities, worktrees, live event delivery, controlled restart/replay,
  provider `REQUEST_OWNER`, both Git-gate outcomes, and retirement evidence:
  `docs/evidence/speculative-coordination-real-cli-2026-07-23/report.md`.
- Public CLI surface now exposes coordination, task, ownership, supervisor,
  events, prediction, speculation, and integration command trees. Task and
  ownership claims are durable and replayable; version-two command envelopes
  bind logical supervisor/worker actors and reject requester/owner confusion.
- External-project public CLI acceptance passed with independent coordinator,
  requester, and owner profiles; live supervisor SSE replay; task/ownership
  claims; prediction acceptance and publication; speculation preparation,
  integration gate, validation, and retirement. The reusable harness is
  `scripts/run-speculative-coordination-real.ps1`.
  Evidence is recorded in
  `docs/evidence/speculative-coordination-public-cli-2026-07-23/report.md`.
- The Codex validation retry is intentionally fail-closed: the external
  project has no committed Git `HEAD`, so status reports
  `SESSION_WORKSPACE=UNASSIGNED` and an installed hook invocation returns
  `WORKSPACE_TRANSITION_REQUIRED`; proof files were not recreated.

## Current limitations

- Remote HTTPS, signed remote event-read authorization, and production
  supervisor lifecycle management remain intentionally deferred.
- Real Codex/Antigravity mutation interception and re-planning remain unproven;
  provider status must remain degraded until an actual hook invocation proves
  interception and workspace routing. Evidence is in
  `docs/evidence/codex-real-validation-failure-2026-07-24/`.

## Verification target

Focused `:coordination:test`, strict Javadocs/compiler checks, root architecture
validator, and checkpoint evidence for the domain/event-store slice.

## Immediate next action

Run the checkpoint validator, record the final diff, and create the local
commit for the verified provider workspace-safety slice; do not push.
