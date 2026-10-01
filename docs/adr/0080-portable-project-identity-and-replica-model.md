# ADR-0080: Portable project identity and replica model

- Status: Accepted for SYN-080
- Date: 2026-09-18
- Architecture mode: EVOLUTION
- Scope: workspace project identity, local discovery, project-facing Link consumers, daemon, and MCP

## Context

The repository already persists a UUID in `.synesis/project.json` and already
binds signed overlay membership to that UUID. It does not yet define a lawful
replica/import operation, an explicit fork operation, or a registry model that
can represent two local paths for one logical project. Core `PeerSession` is an
authenticated physical node session and intentionally has no project context.

The requested model must keep these concepts separate:

```text
ProjectId       stable logical project identity
Replica         local realization of that identity
Membership      authority to participate in that identity
Link session    temporary authenticated node/session binding
Git state       mutable content state
```

## Decision

1. The UUID already stored in `.synesis/project.json` remains the only
   canonical `ProjectId`. Its identity material is the UUID and immutable
   creation timestamp. Paths, directory names, remotes, branches, Git HEADs,
   runtimes, providers, credentials, and private keys are not identity.
2. Existing metadata is parsed strictly and never silently re-genesis. Writes
   remain atomic. A malformed or conflicting existing identity fails closed.
3. A legitimate replica preserves only the portable identity record and gets
   fresh local node/profile/runtime state. No private membership, authority,
   provider, or credential state is copied.
4. An explicit fork operation creates a new UUID. Git similarity, path copying,
   and remote equality never decide whether a fork occurred.
5. `KnownProjectRegistry` remains discovery metadata only. It may contain
   multiple normalized paths for one `ProjectId`; a path collision with a
   different canonical ID remains an identity mismatch.
6. Core `link` remains project-agnostic. After Link authentication and control
   readiness, a project-facing consumer may perform an authenticated,
   project-bound context/membership exchange before any project mutation. A
   project mismatch or invalid project-bound membership fails before mutation.
7. Signed `OverlayMembershipSnapshot` remains the membership authority
   boundary. Knowledge of `ProjectId` or registry presence never grants
   membership.
8. MCP keeps exactly ten tools. MCP and daemon attach to the canonical local
   identity and observe registry records; neither becomes membership authority.

## Ownership and failure boundaries

| Concern | Owner | Must not own |
| --- | --- | --- |
| Canonical identity and genesis | `ProjectApplicationService` | membership or Link sockets |
| Local replica materialization | `ProjectApplicationService` | copied private authority state |
| Discovery records | `KnownProjectRegistry` | authorization or liveness proof |
| Membership and project authorization | signed project-facing overlay/record consumer | path heuristics or inviter trust |
| Physical authenticated session | `link` / `PeerSession` | project identity and membership |
| Local selection/attachment | daemon and MCP adapters | global authority |

## Rejected alternatives

- Adding a second UUID or making a path-derived identity: duplicates or
  destabilizes the existing canonical identity.
- Adding `ReplicaId` before a stable same-machine replica addressing need is
  demonstrated: creates another identity namespace without solving authority.
- Putting project identity into core `PeerSession`: violates the existing Link
  boundary and couples transport lifecycle to project lifecycle.
- Treating the registry, Git remote, inviter, or copied metadata as authority:
  permits identity substitution or unauthorized participation.
- Creating a daemon, database, broker, or cloud service: no evidence justifies
  the operational cost for this local-first model.

## Implementation order and fitness functions

1. Introduce one canonical identity value/comparison seam and enforce the
   existing `project.conf` versus `project.json` consistency check.
2. Allow multi-path same-ID registry records and preserve mismatch diagnostics.
3. Add lawful replica materialization and explicit fork APIs with fresh private
   state and strict preconditions.
4. Add project-context admission before project sync/overlay mutation without
   changing core Link or the MCP catalog.
5. Add real process-isolated tests for same-ID replicas, different HEADs,
   copied-ID/no-authority, wrong-project membership, registry collisions, and
   explicit forks.

Fitness functions:

- relocation, branch changes, restart, and reconnect do not change `ProjectId`;
- two validated paths with the same identity can coexist in discovery;
- same path with a different identity fails closed;
- copied identity without signed membership cannot mutate project state;
- a valid member bound to another project is rejected before mutation;
- explicit fork produces a distinct identity;
- no private authority, credential, provider, or runtime state crosses replica
  materialization;
- core Link tests remain unchanged and MCP still exposes exactly ten tools.

## Invalidation conditions

Add a local opaque `ReplicaId` only if a real requirement needs stable
same-machine addressing of two replicas that cannot be represented by
`(ProjectId, normalized path)`. Revisit the authenticated context wire format
only if project consumers need stronger session binding than the existing
bounded application stream can provide.
