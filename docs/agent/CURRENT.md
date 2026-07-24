# Current Task

## Identity

- Task ID: SYN-013B
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0151
- Latest checkpoint: CP-0157
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027, ADR-0028, ADR-0029

## Objective

Implement automatic project-scoped provider session binding for Codex and
Antigravity, with a fail-closed workspace boundary before any meaningful hook
mutation.

## Immediate slice

Persist distinct provider session, supervisor, and worker identities; resolve a
real Git base commit (or apply the documented unborn-repository init policy),
allocate and resume a detached Git worktree outside the control checkout; expose binding/workspace/interception
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
- The first Codex validation retry is intentionally fail-closed: the external
  project has an unborn Git `HEAD`, so status reported
  `SESSION_WORKSPACE=UNASSIGNED` and the installed hook returned
  `WORKSPACE_TRANSITION_REQUIRED`; proof files were not recreated.
- Implemented `WorkspaceMutationBroker` enforcing all 5 workspace mutation invariants: session binding, assigned worktree verification, `WORKSPACE_TRUST=VERIFIED`, `HOOK_INTERCEPTED=true` exact proposed mutation evaluation, and `DECISION=ALLOW`.
- Added 7 unit tests in `WorkspaceMutationBrokerTest` proving WORKSPACE_UNVERIFIED cannot mutate, missing interception cannot mutate, UNKNOWN decision cannot mutate, only ALLOW permits exact mutation, successful mutation records interception evidence and decision ID, control checkout remains unchanged, and synthetic hook execution does not count as real interception.
- Updated `ProviderApplicationService` to check recorded interception evidence and report `SESSION_INTERCEPTION`, `HOOK_INTERCEPTED`, `DECISION`, and `MUTATION_WITHOUT_ALLOW_POSSIBLE` accurately, preventing false fail-closed claims when unintercepted native mutations occur.
- Rebuilt and reinstalled the stable bundle to `%LOCALAPPDATA%\Synesis`.
- Refreshed Codex integration in `SynesisTestProject`, preserving project ID, node ID, session, branch, and assigned worktree, and removed the previous Worker A proof file.
- Full `.\gradlew.bat check --no-daemon` passed cleanly with all 42 actionable tasks.

## Current limitations

- Remote HTTPS, signed remote event-read authorization, and production
  supervisor lifecycle management remain intentionally deferred.
- Codex CLI 0.140.0 does not support native pre-apply_patch hooks (`REAL_CODEX_PRE_MUTATION_HOOK_SUPPORTED=false`); workspace mutations are enforced via `WorkspaceMutationBroker` (Strategy B). Unintercepted native Codex mutations report `MUTATION_WITHOUT_ALLOW_POSSIBLE=true` and `SESSION_INTERCEPTION=UNPROVEN` so fail-closed is not falsely claimed.

## Verification target

`WorkspaceMutationBrokerTest`, root `check`, reinstalled stable bundle, and refreshed Codex integration.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1` and present the final report.
