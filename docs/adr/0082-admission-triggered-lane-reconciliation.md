# ADR-0082: Admission-triggered lane reconciliation

- Status: Accepted for SYN-082
- Date: 2026-09-18
- Architecture mode: EVOLUTION
- Scope: ordinary stdio MCP admission, session-lease reconciliation, and participant recovery projection

## Context

The ordinary Codex path uses a persistent Synesis MCP child process, but Synesis
does not supervise the outer Codex process. The MCP process records an exact
connection-instance lease and its process identity. If that process disappears
without EOF, no close callback can run; the existing lease evaluator therefore
classifies the record only after the configured stale and abandonment grace
thresholds. Before this decision, the durable reconciliation service was
reachable from the CLI and the explicitly Synesis-supervised provider path, but
not from a newly admitted ordinary MCP connection.

The blind acceptance exposed that gap: a fresh replacement could be admitted
while the old participant still projected `ACTIVE`, even though the old MCP PID
was gone and the lease had become recovery-eligible. The audit also found that
plan discovery selected any known participant, so a stale lease file belonging
to a `COMPLETED` or `DETACHED` participant could be turned back into an active
recovery transition.

## Decision

1. After an exact ordinary MCP connection has passed `ensure_session` and its
   own lease renewal succeeds, the handler invokes a one-time
   `ReconciliationService.reconcileOnAdmission` hook.
2. The hook uses the existing process-identity evidence, stale threshold,
   abandonment grace period, durable plan store, execution lock, snapshot
   materialization, and `PARTICIPANT_SUSPENDED`/`RECOVERY_SNAPSHOT_HELD`
   events. It does not add an MCP tool, alter `get_next_action`, release
   claims, issue a lane grant, or choose a replacement participant.
3. Discovery creates recovery entries only for a known non-terminal participant
   that is not already `RECOVERY_HELD`. A participant already `SUSPENDED`
   receives only the snapshot-hold action.
4. Plan execution re-reads participant lifecycle state. A terminal/missing
   participant is recorded as `SKIPPED_STALE`; an already-suspended or
   already-held action is recorded as an idempotent completion. A stale plan
   cannot mutate a participant that completed, detached, was cancelled, or was
   revoked after planning.
5. Recovery remains bounded and conservative: process absence plus grace is
   evidence for fencing and snapshot preparation, not proof of human intent.
   Continuation still requires the existing exact binding, claim epoch, fresh
   target lane, and single-use lane grant.

## Invariants preserved

- Exact provider and connection-instance resolution remains authoritative; no
  latest-binding fallback is introduced.
- A live or ambiguous old process is not taken over.
- Terminal binding/session/participant state remains irreversible.
- Claims remain reserved through suspension and recovery hold.
- The ten-tool MCP catalog and provider-neutral MCP boundary remain unchanged.
- External administration and recovery snapshots remain outside the control
  checkout; no source `.synesis` state is created in this repository.

## Rejected alternatives

- Killing or supervising the outer Codex process: ordinary provider launches do
  not grant Synesis that authority, and the MCP child is the runtime whose lease
  is actually recorded.
- Shortening the grace period or treating a missing PID as immediate
  abandonment: that would weaken the existing fail-closed lifecycle evidence.
- Adding a recovery MCP tool or changing `get_next_action`: the existing plan,
  continuation, review, and integration surfaces are sufficient once recovery
  is triggered at a verified admission boundary.
- Releasing claims or auto-issuing a continuation grant: recovery must preserve
  scope and require the existing explicit authority transfer.
- Letting reconciliation suspend terminal participants: terminal lifecycle
  states are not recoverable and stale leases cannot override them.

## Verification

Focused tests cover dead-runtime admission, exactly-once suspension/snapshot
holding, stale plans after completion, terminal lease callback fencing, the
existing recovery plan, and the unchanged ten-tool catalog. Disposable blind
acceptance remains the final evidence gate for installed-artifact behavior.
