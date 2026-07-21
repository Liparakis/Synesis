# 15. Project Reconciliation and Action-Time Constraint Guardrail

- Status: Accepted
- Date: 2026-07-21
- Deciders: Core Architecture Team

## Context

Synesis prevents independently running coding agents from acting on stale or conflicting project decisions.

Previously, SYN-003 provided single-record synchronization (SRP1). However, two-agent coordination requires:
1. Exchanging inventories of all project decision heads to identify equal, missing, stale, ahead, and divergent records over a single authenticated Link session.
2. Synchronizing missing contiguous revision chains without deleting valid local-only records or overwriting divergent heads.
3. Exposing an action-time check interface so an agent can verify proposed code modifications against authoritative project constraints before execution.

## Decision

1. **PRP1 Protocol**: Define the Project Reconciliation Protocol (`0x50525031`, "PRP1") as a bounded, sequential, bidirectional protocol over the Link application-stream seam:
   - Chunked inventory exchange (50 entries per chunk, maximum 1,000 entries).
   - Contiguous revision upload/download transfers (maximum 100 revisions per record).
   - Session payload cap at 10 MB.
   - Independent schema, owner, signature, predecessor, and continuity verification before local write.
   - Divergent heads are exchanged and quarantined in `conflicts/` on both peers without replacing local heads.
   - Local corrupt records prevent `SUCCESS` convergence status.

2. **Unified Dual-Protocol Host Handler**: Update `:workspace` CLI `sync host` to wrap both SRP1 (`0x53525031`) and PRP1 (`0x50525031`) application stream handlers. The host inspects the 4-byte magic header of incoming request streams and routes to the appropriate handler, enabling backwards compatibility with single-record sync while supporting project-wide reconciliation.

3. **Action-Time Constraint Guardrail (`check-action`)**: Add `check-action` to `:workspace` CLI:
   - Accepts `--scope <path-pattern>` and `--action <summary>`.
   - Reads profile-local verified decision heads (`DecisionStore`).
   - If an active or proposed decision constraint matches the affected scope or action, returns `ACTION_RESULT=BLOCKED`, prints machine-readable details on `stdout`, prints a contextual `HINT=` on `stderr`, and exits with code `10`.
   - If no constraint matches, returns `ACTION_RESULT=ALLOWED` and exits with code `0`.

## Consequences

- **Pros**: Enables two independently running coding agents to synchronize project state in one bounded session and block incompatible code modifications before execution.
- **Cons**: Action-time checking relies on agent harness integration to call `check-action` before file modification.
