# ADR-0085 — Successor-scoped publication and reviewer recovery

## Status

Accepted for SYN-082 overnight continuation work on 2026-09-19.

## Context

The lawful continuation transition already creates a distinct successor
participant, binding, intent, and worktree while preserving the predecessor's
task, WorkGroup, acceptance, selectors, claim epoch lineage, and recovery
snapshot. Two downstream assumptions were narrower than that transition:

1. completion selected the first active intent for a participant and treated the
   participant worktree as if it contained only that lane's files;
2. an unresolved review grant remained addressed to a reviewer participant who
   had become recovery-held, so reviewer loss could strand an otherwise valid
   candidate.
3. an unresolved review request could remain addressed to a recovery-held
   producer, so producer loss could strand the request before a replacement
   producer could receive the existing independent review decision.
4. a late grant event could still name a predecessor intent after its
   successor had been committed, allowing stale review projection or
   consumption to outrank the current-epoch grant.

Both failures are coordination-state failures, not reasons to transfer the
predecessor's identity or to weaken review independence.

## Decision

### Successor-scoped publication

When a server-issued `finish_lane` request includes `expectedIntentId`,
completion selects that exact active intent and verifies that it belongs to the
authenticated participant. A request without exact evidence is rejected when
the participant owns multiple active lanes rather than relying on arrival
order.

If one participant lawfully owns multiple disjoint producer lanes, the server
requires every source change in the shared worktree to be covered by the union
of that participant's producer claims. The selected lane's snapshot then
materializes only its own claim-covered paths. Existing single-lane completion
retains the strict whole-worktree claim boundary.

This is scoped publication, not a claim bypass: unowned paths still fail
closed, and a lane with no claim-covered change cannot publish.

The same claim-scoped publication path is role-neutral for an active lane that
owns a changed evidence artifact. In particular, a reviewer may publish its
own claimed report or test artifact after the independent review decision; the
foreign-claim fence still prevents that reviewer from publishing the producer's
source paths. This reuses the existing snapshot, integration, and WorkGroup
terminality path without making the reviewer an implementation owner or
allowing a clean/no-evidence finish.

### Reviewer successor grants

`LANE_CONTINUATION_ACCEPTED` remains the single durable ownership transition.
When its source/target intent is a reviewer intent, the replayable
`WorkGroupProjection` marks each unresolved review grant addressed to the dead
reviewer in the same WorkGroup as superseded and deterministically derives a
new single-use grant for the successor reviewer. Grants with a durable review
decision are never migrated. The historical grant remains in the projection
for audit, but it is no longer available or an outstanding obligation.

The replacement grant keeps the original producer intent, candidate epoch, and
WorkGroup. The successor reviewer must still consume the replacement grant and
submit an independent review decision through the existing generic coordination
surface. No review is automatically accepted and no implementation owner is
made a reviewer.

The one-shot authorization reconstructed for a fresh lane continuation is a
different authority type even though it is represented by the same durable
grant record. The projection tags that consumed grant as a continuation grant;
review-obligation projection excludes it, and both the collaboration
projection and review-validation boundary reject a review decision against it
with `REVIEW_SELF_APPROVAL_FORBIDDEN`. This keeps replay/audit visibility
without allowing a successor to convert continuation authority into
self-approval.

### Producer successor review requests

When a producer intent continues, each still-pending `REVIEW` request whose
target and conflicting intent identify that producer is re-targeted to the
successor participant and successor intent in the same projection transition.
The request remains pending and keeps its original requester, proposal, and
request identity. Unrelated requests are unchanged. The successor must still
respond through the existing coordination surface, which then issues the
ordinary review grant to the independent requester.

### Historical review-grant fencing

Review projection and grant-bound completion treat any grant whose target
intent has a recorded successor as historical, even when that grant arrived
after the continuation event and was not part of the migration pass. The
coordination request, grant-issuance, and grant-consumption boundaries reject
the same stale predecessor target. This keeps late event ordering replayable
for audit while ensuring only the current successor intent and epoch can
become executable review authority.

## Consequences

* A shared successor worktree can converge one disjoint lane at a time without
  publishing a sibling lane's files accidentally.
* Completion and publication remain exact-intent and exact-participant
  operations; stale or underspecified finish requests fail closed.
 * Reviewer loss is recoverable through the existing continuation action and
   `request_coordination`/`work_group_join` path. The replacement review grant
   is deterministic across replay.
 * Producer loss cannot leave an already-issued pending review request pointing
   at a detached participant; the request follows only the exact producer
   successor and remains subject to the normal response and grant checks.
* Historical grants and predecessor identities remain visible for audit. The
  event log does not rewrite the original reviewer or candidate history.
* The event-log process-crash guarantee remains distinct from a hardware
  power-loss guarantee; directory metadata fsync is not claimed.

## Alternatives rejected

* Automatically assigning all abandoned lanes to the next participant would
  turn coordination into a planner and weaken user/agent choice.
 * Reusing the old grant for a new reviewer would make the dead reviewer appear
   to retain authority and would make review validation ambiguous.
 * Cancelling every pending request during continuation would discard valid
   independent review work and would force a new request outside the existing
   coordination record.
* Silently ignoring sibling files would fabricate a complete publication and
  could discard valid unfinished work.
* Rejecting every participant with more than one disjoint lane would avoid the
  downstream bug by narrowing the domain after the fact, but would contradict
  the existing participant projection and the concurrent continuation model.
