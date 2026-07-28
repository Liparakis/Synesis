# ADR-0033: Signed coordination-request negotiation

## Status

Accepted for SYN-021.

## Decision

Coordination requests are durable signed events in the existing project event
log. A request names the opaque requester and target participant, the exact
conflicting intent, a bounded request kind, and a proposal. Responses are
append-only and transition a pending request exactly once. Claim ownership is
unchanged by a request or timeout; only a later handoff slice may transfer it.

The coordination projection owns request replay and idempotency. Workspace,
CLI, and MCP adapters resolve the exact provider session before creating or
responding to a request and call the same application service. No broker,
database, control-checkout mutation, or inferred semantic dependency is added.

## Consequences

Agents can discover the conflicting goal and negotiate a contract before
mutating. A request is evidence of coordination, not permission to write. The
existing eleven-tool MCP contract remains stable while request/status fields
are additive.
