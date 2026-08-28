# ADR-0045: Reviewed snapshot authority gate and same-lineage correction

## Status

Accepted for the SYN-039 reviewed-lane correction slice.

## Context

The existing completion path published a task snapshot and immediately ran
integration.  A later rejected review was recorded as history after the lane,
claims, participant, and provider binding had already been terminalized.  The
task projection also retained only one snapshot per task, so a corrected
attempt could not produce a distinct immutable artifact.

## Decision

Reviewed mutable work publishes an immutable snapshot into a durable
`REVIEW_PENDING` state.  Publication does not integrate the snapshot, release
the WorkIntent, release its claims, complete the participant, or complete the
provider binding.

A rejected review is recorded by the existing
`REVIEW_VALIDATION_RECORDED` event.  When its target WorkIntent is still the
active exact lane, the collaboration projection replaces that same intent
with version plus one, preserving its intent ID, WorkGroup, participant,
selectors, provider, task, completion mode, and authority lineage.  This is a
revision of one implementation lineage, not a new identity architecture.

Snapshots are retained by immutable snapshot ID and indexed by lane revision
(`laneId`, `claimEpoch`).  Replaying the same publication for one revision
returns that revision's snapshot; a later revision produces a distinct
snapshot.  Review state is stored per snapshot as `REVIEW_PENDING`,
`REVIEW_REJECTED`, `REVIEW_ACCEPTED`, or `INTEGRATED`.  A rejected snapshot is
never eligible for integration.

The old grant remains consumed and bound to the rejected revision.  A new
review admission request and grant are required for the incremented WorkIntent
version.  Integration accepts a reviewed snapshot only when the exact
snapshot has a durable `ACCEPTED` validation.  Only after guarded integration
succeeds are session finalization, WorkIntent release, claim release,
participant completion, provider binding completion, and WorkGroup
completion reevaluation performed.

No new MCP tool, launcher, daemon, relay, reattach, succession, operator
recovery, generalized identity, or automatic provider-exit completion is added.
The explicit no-change completion contract remains on its existing path.

## Consequences

- Rejected immutable work remains auditable and cannot enter the integration
  queue.
- The implementer receives a typed correction projection carrying the current
  WorkIntent version and lineage; callbacks carrying the old epoch fail closed.
- Review admission is repeatable per revision, while each consumed grant and
  validation remains immutable history.
- The event log remains the replay authority; transient MCP responses do not
  define review state.
- A reviewed lane may remain provider-bound while waiting for review or
  correction.  Provider wake/resume behavior is not expanded by this ADR.

## Verification boundary

Focused projection, service, MCP-contract, and integration tests prove the
gate, revision identity, stale-grant rejection, terminalization ordering, and
no-change regression.  A separate disposable runtime reproduction uses the
official rebuilt bundle; it is not the final whole-system SYN-039 acceptance.
