# ADR-0083: Lawful recovery continuation and successor authority

- Status: Accepted for SYN-082
- Date: 2026-09-19
- Architecture mode: EVOLUTION
- Scope: recovery-held unfinished collaboration lanes and the existing generic coordination action

## Context

SYN-082 now fences a stale provider session and preserves its unfinished
WorkIntent claims in an immutable recovery snapshot. The existing
`LANE_CONTINUATION_ACCEPTED` event can transfer those claims to a new intent,
but the only exposed path required an independently issued lane grant. A fresh
blind participant therefore had no normal action with which to choose the
unfinished lane. The pre-existing construction also derived a new task ID,
which would sever task and snapshot lineage.

## Decision

1. Keep `LANE_CONTINUATION_ACCEPTED`, `WorkIntentService`, exact session
   binding, recovery snapshots, and the ten-tool MCP catalog as the authority
   boundaries. Do not add a recovery-specific tool or let reconciliation select
   a successor.
2. Derive continuation eligibility read-only from an active WorkIntent whose
   participant is `RECOVERY_HELD`, whose immutable recovery snapshot is present,
   and whose WorkGroup remains active. `get_next_action` exposes the bounded
   scope, goal, acceptance, dependencies, and current claim epoch without
   exposing snapshot paths or runtime secrets.
3. Extend the existing strict `request_coordination` continuation kind with a
   fresh-successor form containing only the projected source intent and claim
   epoch. The exact authenticated caller becomes the target only when it
   explicitly submits that action. The service deterministically derives the
   successor intent and grant identities, validates the current projection
   under the project append lock, restores the verified snapshot into the
   caller's exact worktree, and records the continuation transition as one
   durable `LANE_CONTINUATION_ACCEPTED` event. A pre-issued grant remains
   supported for the legacy reviewed path; the fresh public path does not
   create a separate grant event that could be stranded by a process loss.
4. A successor keeps the source task ID, goal, acceptance, scope, role,
   dependencies, and authority lineage. Its intent ID, participant handle,
   connection instance, provider binding, and runtime authority are new. The
   source participant becomes detached with no claims; its historical terminal
   or stale authority is never revived.
5. The transition rejects stale epochs, terminal targets, scope conflicts,
   mismatched project/task/lineage/role data, replayed grants, and competing
   ownership. The append lock and projection validation permit exactly one
   winner when two fresh participants race. A read-only eligibility projection
   never creates a grant or assigns a successor.
6. Existing snapshot review and integration evidence remains authoritative.
   A successor may continue an unpublished lane from the immutable recovery
   snapshot; a published candidate keeps its original review identity and may
   be integrated only after the existing review/integration gates pass.

## Invariants preserved

- Continuation of work does not imply continuation of participant identity or
  runtime/session authority.
- Exact `RuntimeAuthenticator`/`SessionAuthorityResolver` binding remains
  required for mutation.
- Historical source participant and source intent remain auditable; only the
  active claim projection moves to the successor intent.
- The daemon/bootstrapper/reconciliation plan does not choose or execute a
  successor.
- Review separation, dependency reporting, WorkGroup terminality, and the
  provider-neutral MCP boundary remain in force.

## Rejected alternatives

- Auto-assigning the first fresh participant: presence is not consent and would
  turn reconciliation into a planner.
- Adding `recover_lane` or `takeover_agent`: the existing generic coordination
  action already carries the required authenticated transition.
- Reusing the old intent ID, participant handle, connection, or PID: that
  would erase the authority boundary and make stale actions indistinguishable
  from current work.
- Releasing claims during reconciliation: it would create an unowned gap and
  discard the source lane's durable recovery evidence.

## Verification target

Focused domain, action-projection, MCP-schema, race, replay, identity-fencing,
artifact-restore, review, and integration tests precede a fresh real Codex
targeted acceptance. The unchanged blind full acceptance remains a separate
gate and must not be reported as passed until WorkGroup and durable goal
closure are observed.
