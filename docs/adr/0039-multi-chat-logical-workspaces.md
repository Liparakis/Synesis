# ADR-0039: Multi-chat logical workspaces and isolated mutation lanes

- Status: Accepted for SYN-027
- Date: 2026-07-29

## Decision

Support multiple chats under one durable logical `WorkGroup`, while preserving
one single-participant `WorkIntent` mutation lane per chat or independently
authenticated subagent. Every lane retains its own participant, binding, lease,
claim epoch, branch, and isolated physical Git worktree. Lanes may acquire only
non-overlapping exact-path or path-subtree claims. Immutable lane snapshots are
integrated later through a dedicated integration worktree.

Concurrent mutation of one physical Git worktree is rejected. `WorkGroup` is a
logical coordination parent, not a shared filesystem or shared model context.

## Rationale

The repository already supports independent bindings, isolated worktrees,
revision-bearing mutations, signed claim arbitration, and guarded integration.
The investigation found that several authority-sensitive paths still resolve a
provider's latest binding, and that snapshot publication and integration do not
yet carry complete lane provenance. Exact-caller authority and immutable
snapshot provenance are prerequisites for safe multi-chat collaboration.

## Consequences

Chats share durable group goals, contracts, claims, snapshots, and integration
lineage, but never private LLM context or one live physical worktree. Existing
intents replay as singleton work groups. Symbol selectors, portable prevention
of every external write, semantic correctness inference, and remote multi-user
authority remain outside this decision.
