# Speculative capability coordination MVP plan

## Delivered foundation

- bounded `:coordination` module and dependency-lock entry;
- canonical `PredictionContract` with identity, ownership, base, behavior,
  acceptance, confidence, risk, and expiry fields;
- signed/hash-chained `PredictionEvent` envelopes;
- crash-safe per-project event files and deterministic lifecycle projection;
- loopback HTTP command endpoint and replayable SSE subscriptions;
- semantic ownership registry, policy-first `REQUEST_OWNER` guardrail outcome,
  and foreground `SupervisorInbox` facade with local event copies;
- focused restart/replay and illegal-transition test.

## Delivered acceptance slice

1. ~~Add signed command envelopes and an idempotent coordinator service.~~
2. ~~Expose loopback HTTP commands and ordered SSE replay with reconnect cursors.~~
3. ~~Add foreground supervisor state/inbox and provider `REQUEST_OWNER` details.~~
4. ~~Add bounded worktree/speculation metadata and Git acceptance gates.~~
5. ~~Run the exact two-node scenario with independent identities and preserve
   the event/commit/test transcript.~~ Evidence:
   `docs/evidence/speculative-coordination-real-cli-2026-07-23/report.md`.

## Deliberate non-goals for this MVP

PostgreSQL, a message broker/WebSocket tier, Obsidian projection, a global
knowledge graph, remote multi-host trust, and automatic ownership transfer are
not part of the first evidence slice. They can be promoted only by a new task
with measured requirements and an ADR update.
