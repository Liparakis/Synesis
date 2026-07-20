# ADR-0012: Bounded authenticated Link application-stream seam

## Status

ACCEPTED FOR SL-014 - 2026-07-21.

## Context

Higher-level Synesis work needs a narrow way to exchange an application payload
after Link has authenticated a peer and completed control readiness. The
current public `PeerSession` exposes only the fixed demo operation. Reusing
that operation would leak higher-level semantics into Link; creating another
transport would duplicate its trust boundary.

## Decision

Expose one transport-neutral application-stream binding from `PeerSession`.
The binding accepts and returns bounded byte payloads, exposes the already
authenticated remote node ID and session readiness, and owns the stream's
framing, size validation, deadlines, liveness-independent lifecycle, and
cleanup through the existing Link control/session owner.

The binding is legal only after reciprocal control readiness. It rejects
pre-ready use, payloads over the fixed bound, malformed framing, duplicate
terminal use, and use after terminal session completion. Exactly one cleanup
path releases the stream operation on success, failure, cancellation, or
session termination. It does not reconnect, retry, persist, or interpret
payload bytes.

No project, decision, record, owner, sync, membership, task, or CLI term may
appear in Link packages, public method names, protocol message kinds, or
tests.

## Contract

- Caller: an authenticated higher-level module holding a `PeerSession`.
- Receiver: the authenticated remote Link peer's application-stream handler.
- Identity: the session's verified remote node ID; no address or TLS identity
  substitutes for it.
- Authorization: reciprocal Link control readiness; application authorization
  remains above Link.
- Payload: opaque bytes, bounded before allocation and transported with the
  existing explicit length framing; no object serialization.
- Deadline: one bounded operation deadline owned by Link; no caller-controlled
  unbounded wait.
- Ordering: one payload per opened stream, ordered by QUIC; no cross-stream
  ordering guarantee.
- Duplicate/retry: Link does not retry. A caller may retry only as a new
  application operation after deciding its own idempotency.
- Partial failure: the operation fails once and cleanup is idempotent; session
  liveness and terminal state remain authoritative.
- Compatibility: this is an additive API within Link's current protocol major;
  unknown/oversized application frames are rejected deterministically.

## Alternatives rejected

- Expanding `synesis-demo-work/1`: leaks one higher-level protocol into Link.
- Returning Netty `QuicStreamChannel`: violates the public transport-neutral
  boundary and exposes native lifecycle ownership.
- A second socket/QUIC/HTTP transport: duplicates authentication and cleanup.
- An unbounded generic message bus: creates an unsupported queue and retry
  surface before a consumer exists.

## Invalidation and fitness functions

Reopen this ADR if one payload per stream cannot support the first higher-level
consumer, if measured payload/latency needs exceed the bound, or if callers
need cross-stream ordering, retries, reconnect, or persistence. The seam is
fit only when focused tests prove pre-ready, bound, terminal, cleanup, and
two-process opaque-byte exchange behavior while the full Link/CLI strict suite
remains green.
