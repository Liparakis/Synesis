# ADR-0035: Explicit Contract Revisions and Dependency Invalidation

- Status: Accepted
- Date: 2026-07-28
- Decision: SYN-022

## Context

Agents need a shared API or schema agreement that is durable, replayable, and
precise enough to invalidate consumers when the owner changes it. File names
and natural-language intent are not reliable dependency signals.

## Decision

The coordination event log records a bounded contract identity, monotonically
increasing revision, SHA-256 body hash, owner, declared selector references,
exact consumer bindings, and supersession. A supersession marks dependencies
bound to the replaced revision `REPLAN_REQUIRED`; publication and binding of a
stale revision fail closed. The projection is deterministic and rebuilt only
from signed events. This slice does not infer cross-language interfaces or
perform integration enforcement.

## Consequences

Contract history and invalidation survive restart and can be inspected by
future CLI/MCP adapters. Consumers must explicitly rebind after a revision
change. Pre-merge compatibility and provider acceptance remain later slices.
