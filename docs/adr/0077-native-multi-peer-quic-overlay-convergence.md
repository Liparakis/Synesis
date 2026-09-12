# ADR-0077: Native multi-peer QUIC stream state and bounded overlay convergence

- Status: ACCEPTED FOR SL-D-040 CHECKPOINT 2
- Date: 2026-09-13
- Decision scope: Link's existing Netty QUIC adapter and the SL-D-040 overlay

## Context

Checkpoint 2 exercises the accepted bounded overlay policy with enough members
to require peer transit. The existing QUIC adapter was written around a
single control stream per endpoint and its server stream initializer could not
allocate independent authentication completion state for multiple accepted
connections. Clients also needed a safe way to receive application streams
opened by an authenticated peer. The overlay already defines the authoritative
membership, topology, route, E2E, and physical-session boundaries; this ADR
does not revise those contracts.

## Decision

Keep one authenticated control handshake and one `StreamState` per accepted
QUIC connection. The responder initializer caches that state on the parent
QUIC channel and allocates its completion future exactly once, even when
multiple streams are initialized concurrently. A client control handshake
retains its own completion state while publishing the authenticated state on
the parent only for peer-initiated application streams. Those streams use the
existing bounded `ApplicationStreamCodec` and `ApplicationStreamTransport`
path; an unauthenticated or not-yet-ready stream is closed.

Keep the existing native QUIC and application bounds. The initial
bidirectional-stream allowance is raised only enough to accommodate bounded
multi-peer control/application activity; the application transport's own
active-stream quota remains authoritative. Topology publication is emitted on
convergence transitions and adjacency changes, and propagation sends through
the current authenticated direct-peer snapshot sequentially so one bounded
fanout does not create a stream-opening storm.

On physical terminal loss, the local owner removes the exact session by
identity, withdraws the corresponding route input, and may remove that
origin's cached topology advertisement. This is a local read-model operation;
it never changes signed membership and does not create a distributed tombstone
or a new membership authority.

## Alternatives rejected

- Do not make the bootstrap node a permanent router or add arbitrary direct
  links; the existing sorted circular `+/-1`/`+/-2` policy remains authoritative.
- Do not reuse one result future across accepted connections; that would merge
  independent authenticated sessions and break multi-peer admission.
- Do not turn peer-initiated application streams into a second handshake or
  expose endpoint plaintext to transit.
- Do not add a relay, HTTP/SSE projection, VM harness, NAT traversal, session
  resumption, path migration, or process-wide topology coordinator in this
  checkpoint.

## Consequences and evidence

The existing transport-neutral overlay can now be exercised against multiple
native QUIC endpoints while preserving Link identity and control readiness.
The reconciler's deterministic ownership and bounded retry remain the only
fresh-session recovery path. The acceptance is six native endpoints in one
JVM; separate process-isolated multi-node churn is deliberately reserved for
Checkpoint 3. Evidence is recorded in
`docs/evidence/sl-d-040-overlay-convergence-2026-09-13.md`.
