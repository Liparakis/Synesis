# Current Task

## Identity

- Task ID: SYN-012
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0135
- Latest checkpoint: CP-0146
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027

## Objective

Expose the verified speculative-capability coordination slice as a usable
public CLI: two independent local supervisors, one deterministic coordinator,
semantic ownership, ordered live events with replay, capability prediction,
logical actor authorization, isolated worktree/speculation metadata, and
auditable retirement from an external project.

## Immediate slice

Complete the public command adapters, actor-bound authorization, external
project acceptance harness, packaging smoke, and durable evidence. Do not
change Link or provider behavior.

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

## Current limitations

- Remote HTTPS, signed remote event-read authorization, and production
  supervisor lifecycle management remain intentionally deferred.

## Verification target

Focused `:coordination:test`, strict Javadocs/compiler checks, root architecture
validator, and checkpoint evidence for the domain/event-store slice.

## Immediate next action

Create the requested final commit `Expose speculative coordination CLI surface`
after reviewing the strict-check, installed-bundle, and external-acceptance
evidence.
