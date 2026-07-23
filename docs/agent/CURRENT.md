# Current Task

## Identity

- Task ID: SYN-012
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0135
- Latest checkpoint: CP-0144
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027

## Objective

Implement the smallest verified speculative-capability coordination vertical
slice: two independent local supervisors, one deterministic coordinator,
semantic ownership, ordered live events with replay, capability prediction,
provider REQUEST_OWNER enforcement, isolated worktree/speculation metadata, and
auditable retirement.

## Immediate slice

Create the new `:coordination` module with bounded domain records, canonical
signed command/event envelopes, an immutable local event store, projections,
transition validation, command idempotency, and replayable subscriptions. Do
not change Link or provider behavior until the live HTTP slice is verified.

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

## Current limitations

- Remote HTTPS, production supervisor lifecycle management, and a general
  purpose `supervisor run` command remain intentionally deferred.

## Verification target

Focused `:coordination:test`, strict Javadocs/compiler checks, root architecture
validator, and checkpoint evidence for the domain/event-store slice.

## Immediate next action

Review CP-0144 and the evidence report, then keep SYN-012's loopback-only
boundary intact while any remote enrollment work is separately activated.
