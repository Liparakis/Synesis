# SYN-041 terminal-disconnect semantics investigation

Date: 2026-08-28  
Scope: design-only source and durable-state analysis  
Provider experiments: none  
Production changes: none

## Boundary and evidence

SYN-041 remains ACTIVE. SYN-039 remains CLOSED / DONE / ACCEPTED. The starting
checkout was `master` at `f5622eba03c7631a7e3c8620a5598e8037ded001`, with the
pre-existing documented dirty state and the five preserved lifecycle files
unchanged. The causal evidence used here is CP-0559 and the source/control
record in `syn041-exit-code-causal-analysis-2026-08-28.md`.

Preserved exactly:

> CP-0557 is behaviorally consistent with the abnormal/partial-input shutdown
> path, but the exact Codex-side transport event was not directly observed.

The current shutdown source still establishes clean EOF as the only path that
calls `handler.close()` and writes `CLOSED_CLEANLY`; an exception in the stdio
loop returns code 1 and skips that close path. No defect is proven in the
lease service, close handler, clean EOF handling, launcher propagation, or
Doctor classification.

## Authority-surface inventory

| Surface                                | Current terminal condition                                                                                                                                       |                                                                   Can outlive one WorkGroup? |                                                                    Required before session terminality |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------:|-------------------------------------------------------------------------------------------------------:|
| Provider binding                       | Exact binding can be `COMPLETED`, `REVOKED`, or `CANCELLED`; `COMPLETED` preserves its worktree.                                                                 | Yes. `resolveReview` intentionally permits `BOUND` or `COMPLETED` for review-only authority. |                            Exact connection/session identity and a positive no-future-authority proof. |
| Participant                            | `COMPLETED` after intent release; `REVOKED`, `CANCELLED`, and `DETACHED` are terminal lane states.                                                               |   Yes. A completed participant can still be the stable reviewer identity for a sibling lane. |                  No active or recoverable lane, and no review/coordination obligation addressed to it. |
| WorkIntent                             | `RELEASED` after integrated or authorized no-change completion; active projection removes it.                                                                    |                                              Yes. Other intents and later intents may exist. |                     All intents for the exact session must be terminal, not merely the current intent. |
| WorkGroup                              | `COMPLETED` only after group-local intents and completion/review checks pass.                                                                                    |                  Yes. Other groups, requests, commands, or session bindings are independent. |    Group completion is necessary only for a group-scoped decision, never sufficient for session scope. |
| Claims                                 | Released when the owning intent is removed; active claims are selector ownership on active intents.                                                              |                       No as ownership, but their absence does not cover non-claim authority. |                                               Zero active claims plus all non-claim obligations clear. |
| Claim epoch / lineage                  | Intent version and `authorityLineageId` bind one lane or authorized continuation.                                                                                |                                        Yes. Lineage explicitly survives repair/continuation. |                       The current epoch must have no pending snapshot, review, or recovery transition. |
| Review grants                          | Grant is consumed/revoked; a consumed grant remains unresolved until validation is recorded.                                                                     |                                                  Yes. Review can follow producer completion. |                                       No available grant and no consumed grant lacking its validation. |
| Review requests                        | Request is terminal only when responded/resolved; pending requests remain durable.                                                                               |                         Yes. `get_next_action` prioritizes pending review/coordination work. |                                                  No pending request involving the session or its lane. |
| Snapshots / completion                 | Snapshot must be integrated, or rejected snapshot must have an integrated correction.                                                                            |                                         Yes. Review and integration can outlive publication. |                     No active, prepared, rejected-without-correction, or integration-pending evidence. |
| Capability / coordination dependencies | Capability request projection has multiple nonterminal states; next-action checks them.                                                                          |                                           Yes. They are not reduced to WorkGroup completion. |                         No requester/owner dependency or validation context correlated to the session. |
| Durable commands                       | Command admission has independent anchors, requests, locks, and terminal-history diagnostics.                                                                    |                                    Yes. `get_next_action` inspects command state separately. |                              No live, pinned, dead-anchor, lease-gap, or unresolved command authority. |
| Wake / resume                          | `get_next_action` may return review, wait, finish, retry, or continuation actions; recovery continuation requires held recovery evidence and a single-use grant. |                         Yes. A completed binding is deliberately queried for review actions. | Wake must be fenced after a committed terminal seal; before that, unresolved work remains recoverable. |
| Session lease                          | Current states are `ACTIVE`, `SUSPECTED_STALE`, `RECOVERY_ELIGIBLE`, `AMBIGUOUS`, `CLOSED_CLEANLY`.                                                              |                                      Yes. Lease is process/liveness evidence, not authority. |                Never infer intentionality from PID/lease absence; require positive terminal authority. |

The current source does not expose one atomic projection that answers “this
provider/session has no remaining mutable or resumable authority.”

## Current completion ordering

`finish_lane` is dispatched to `AgentTaskCompletionService`. A successful
snapshot path first requires accepted/integrated completion, releases the exact
claims, marks the exact binding `COMPLETED`, and then calls `getNextAction` so
that a lawful review continuation can be returned. The no-change path performs
the same lane release and binding completion after its exact revision, group,
workspace, dependency, and review-obligation checks.

The projection effects are:

```text
finish_lane success
  -> WORK_INTENT_RELEASED
  -> participant COMPLETED and claims empty
  -> WorkGroup may become COMPLETED after group-local checks
  -> exact binding COMPLETED
  -> get_next_action may still return review coordination
```

This is task/lane completion, not provider-session finalization. The same
connection identifier can also be passed to `ProviderSessionBindingService.ensure`
after a `COMPLETED` binding; the existing completed record is excluded from the
refresh branch and a new `BOUND` binding is created. Therefore current code
does not treat `COMPLETED` as a permanent provider-session reuse fence.

## Candidate predicates

* `WorkGroup = COMPLETED`: insufficient. It is group-local and does not cover
  other groups, completed-binding review authority, capabilities, commands, or
  a later binding for the same provider connection.
* Participant `COMPLETED`: insufficient. It fences ordinary heartbeats for
  that participant, but review-only authority can remain and its identity is
  lane-scoped rather than session-scoped.
* Binding `COMPLETED`: strongest current binding signal, but insufficient. The
  review resolver explicitly accepts it, completion code uses it for idempotent
  lane retries, and `ensure` can establish a new binding for the same evidence.
* Zero active intents: necessary but insufficient. It can be true while a
  pending review, consumed-but-unvalidated grant, capability dependency,
  command, or future announcement remains possible.
* Zero claims: necessary but insufficient. Claims cover mutable selectors only;
  they do not cover resumable or review authority.
* Composite of current booleans: still insufficient as an inference. The
  booleans are read from separate projections/stores and no current durable
  session-level seal atomically prevents a new announcement, wake, review, or
  rebind between the read and the abnormal disconnect.

Accordingly, the current system has no irreversible session-terminal state.
It has irreversible or effectively terminal *lane* and *binding* states, but
not a monotonic provider-session authority seal.

## Review, wake, and recovery races

1. If a producer publishes and a peer rejects immediately, the rejected
   snapshot is not terminal evidence. The exact active producer intent is
   reactivated at the next version; the accepted SYN-039 invariant is that
   review-pending work is **not session-terminal**.
2. If a review lane completes while a sibling remains active, a completed
   binding can still issue/consume review coordination within the bounded
   completed-group scope. It must not be classified as provider-session dead.
3. If claims are released while a command or capability dependency remains,
   the session remains actionable or recovery-relevant.
4. If transport disappears before a terminal durable write commits, the result
   is indistinguishable from unexpected loss and must remain fail-closed:
   `ACTIVE` / stale / recovery-relevant.
5. If a positive terminal write commits before abnormal transport loss, later
   wake and heartbeat operations must lose by exact session/epoch fencing; a
   wake race must either win before finalization or be rejected after the
   finalization commit.
6. Missing PID, a stale lease, or a shared provider thread identifier cannot
   prove death, handoff, or intentional termination.

## Design A: derived terminal disconnect

Derived classification is attractive for compatibility with providers that
close abruptly, but it is unsafe in the current architecture. A snapshot of
“nothing active” is not a durable seal. It can misclassify a crash during a
review/rejection window, a pending non-claim obligation, or a legal later
announcement as terminal. It also risks rewriting abnormal transport history
as `CLOSED_CLEANLY`.

Design A is rejected as the primary semantics. It can only be a later
read-side optimization after an explicit terminal authority fact already
exists.

## Design B: explicit terminal-disconnect intent

An explicit provider/session intent is the safer semantic boundary:

```text
authenticated exact connection says terminal
  + durable terminal-authority proof passes under the project append lock
  -> session authority is sealed
  -> subsequent transport mode is recorded separately
```

The proof must include exact session/binding identity, no active or recoverable
intents, no claims, no pending review/request/grant/snapshot/dependency/command
obligations, and an atomic fence against later wake, heartbeat, rebind, or
continuation. If any check is stale or races, terminalization fails closed.

No current MCP tool carries this session-wide meaning. `finish_lane` currently
means completion of one lane and is followed by an explicit review-next-action
projection; it is therefore not, by itself, a session-finalization signal.
The narrowest future protocol location worth evaluating is an explicit opt-in
terminal scope on an existing completion flow, but that is a future design
question, not an implementation in SYN-041.

## Lease and forensic semantics

`CLOSED_CLEANLY` should remain reserved for observed clean stdio EOF or graceful
shutdown. A future terminal-authority proof would justify a distinct conceptual
classification such as “terminal authority confirmed, transport abnormal,”
without naming or adding that state now. Recovery would be disabled, but the
abnormal transport fact would remain queryable. Doctor should eventually report
that case as informational history, while still warning for abnormal transport
when authority is live, stale, ambiguous, or recoverable.

The lease store and Doctor behavior are unchanged by this investigation.

## Result and future tests

Primary result: **RESULT C — explicit terminal-disconnect intent is required**.
The exact minimum semantic change is not a new generalized identity system or
an inferred `CLOSED_CLEANLY` mapping. It is a future, explicit, exact-session
terminal-authority signal whose commit atomically seals future wake/resume and
preserves an abnormal transport reason separately from clean EOF.

Conceptual transition only:

```text
ACTIVE session
  -> explicit terminal intent + complete authority proof
  -> terminal authority confirmed (monotonic fence)
  -> transport ends
       clean EOF       -> CLOSED_CLEANLY
       abnormal ending -> terminal-disconnect history, non-recoverable
```

Future tests should cover active work loss, pending review loss, rejected
snapshot continuation, all-authority-terminal abnormal disconnect, stale
heartbeat after terminalization, wake after terminalization, clean EOF
regression, command/dependency races, same-connection rebind fencing, and the
unchanged exactly-10-tool catalog.

No production source file was changed. The five preserved lifecycle files were
not changed. No lease/Doctor behavior, provider migration, generalized
identity architecture, milestone, task, push, tag, release, or publication was
changed or created.
