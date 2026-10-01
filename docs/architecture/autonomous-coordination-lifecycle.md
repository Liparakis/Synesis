# Autonomous coordination lifecycle

1. `synesis init` creates or upgrades project state and provider artifacts.
2. The first provider boundary calls `ensureSession`; the hidden runtime is
   started or resumed and a worktree is allocated.
3. The task is announced and provisional semantic ownership is evaluated.
4. A safe mutation passes policy, ownership, freshness, and workspace gates.
5. `REQUEST_OWNER` creates a confirmed prediction automatically and routes it
   live, or persists it for the next verified owner boundary.
6. Owner acceptance starts implementation; requester receives a local
   speculative adapter and a dependency marker.
7. Owner evidence (owned changes, tests, clean commit, contract/base match)
   publishes `PATCH_READY` and `CAPABILITY_AVAILABLE`.
8. Requester integrates provenance-checked code, disables speculation, runs
   real validation, and retires only after validation succeeds.

Invalidation is explicit for contract revision, owner-intent change, base
change, rejection, expiry, architecture conflict, or failed validation.
Invalidation removes temporary artifacts but never deletes legitimate requester
source. Multiple predictions form a dependency graph; a failed prerequisite
blocks dependents.

The owner may be active, idle, offline, or unsupported for unsolicited input.
The inbox persists every request and injects it at the next safe boundary when
direct delivery is unavailable. Expired or conflicting requests become
operator-visible diagnostics, not silent state changes.

Pending collaboration requests fence integration before an attempt is allocated.
That project-wide fence projects `WAIT` / `integration_pending`; it cannot assign
a response obligation because it has no caller participant binding.
`get_next_action` projects `respond_coordination` for the exact request target,
with every pending request's identity, kind, proposal, requester, target, and
intent context in `pendingCoordination`. CONTRACT responses remain an explicit
owner choice. Empty recommended arguments are valid at that decision point when
the request context and the existing response schema make the call discoverable.

The MCP schema distinguishes the strict CONTRACT payloads by kind.
`contract_request` requires `conflictingIntentId` and `proposal`, with optional
`contractId` and `revision`. `coordination_response` requires
`coordinationRequest` and `coordinationStatus`, with optional `proposal`.
The schema excludes other kinds' fields from these payloads; the request target
still chooses the response status.

Review admission requires an active reviewer intent with disjoint claims and the
matching review targets. A completed producer or released reviewer does not gain
that authority merely because a sibling producer remains active. Existing grants
and pending requests retain their normal review continuations. A completed lane
retains a read-only WAIT projection while its WorkGroup is active; this neither
reopens its claims nor announces a reviewer role.

A finish rejected with `SNAPSHOT_COMPLETION_EVIDENCE_STALE` projects
`get_next_action({"completionRequested":true})` to obtain fresh server evidence.
This is a new explicit completion evaluation, not a sticky completion flag or a
retry of the stale finish arguments. Ownership, claim epoch, group version and
event revision validation remain unchanged. Ordinary WAIT polls still use `{}`.

Clean workspace recovery may relocate a worktree while preserving its session.
Subsequent session ensures retain that exact registered path and reverify its Git
identity. They do not reconstruct the original allocation path, change the
participant, or detach its existing claims. Dirty workspace recovery remains
subject to the existing continuation authority checks.

