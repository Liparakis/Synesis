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

