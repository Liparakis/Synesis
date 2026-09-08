# ADR-0067: Local browser-facing control plane

- Status: ACCEPTED FOR SYN-052 IMPLEMENTATION
- Date: 2026-09-08
- Scope: local Synesis runtime observation and supported control actions
- Architecture mode: EVOLUTION / REVIEW

## Context

The completed `SL-D-040` overlay slice supplies bounded membership, topology,
routing, and live relay seams, but it deliberately does not expose them to a
browser. The repository already has a loopback JDK `HttpServer`, a durable
coordination service, replayable events, and a long-lived project runtime host.
The new requirement is a local backend surface for a future browser UI; it is
not a request for a frontend, public API, hosted service, or replacement
orchestrator.

## Decision

Evolve the existing loopback coordination listener with a versioned
`/api/v1` adapter. The adapter reads explicit DTO fields from existing
project, coordination, provider, diagnostic, Link, overlay, and relay seams.
Commands delegate to existing application services, especially the Link
onboarding façade; HTTP handlers do not reimplement protocol validation or
state transitions.

The default bind is loopback only. The existing deterministic coordination
port remains the default, with the already-supported ephemeral-port option for
tests and collision-free discovery. The ready diagnostic prints the actual
bound endpoint and a one-time local bootstrap credential; the credential is
never returned by a normal read endpoint.

The control session is local and short-lived. A one-time bootstrap token is
exchanged for a high-entropy session token and a separate CSRF token. Private
reads require the session header; state-changing commands require both session
and CSRF headers. Host validation is exact, untrusted `Origin` values are
rejected, and CORS is never wildcarded. Health is the only unauthenticated
read.

The live channel is SSE. It sends a bounded initial snapshot marker and
UI-safe, versioned resource-change events. A slow subscriber is disconnected
with a refresh condition rather than growing backend memory without bound.
The browser reloads the current snapshot after reconnect; durable event replay
is not added for the browser surface.

## Boundaries and invariants

- The control plane is one in-process adapter, not a second daemon or service.
- `PeerSession` remains the physical Link adjacency; overlay membership,
  topology, routes, and relay state remain distinct read models.
- JSON DTOs omit private keys, provider credentials, runtime proofs, raw
  internal tokens, and arbitrary filesystem contents.
- Unsupported operations are absent or rejected with a stable diagnostic
  envelope; HTTP cannot bypass backend authority.
- Invite, answer, expiry, identity, single-use, and replay semantics remain
  owned by `Onboarding` and its existing Link protocol types.
- The server binds only to `127.0.0.1` (and `::1` when explicitly selected);
  it never defaults to `0.0.0.0`.
- No database, broker, cache, cloud API, frontend, URI handler, or durable
  browser event log is introduced.

## Rejected alternatives

- A new web framework or JSON dependency: the existing JDK server and the
  repository's bounded JDK-only JSON utility are sufficient.
- A separate control-plane daemon: it would duplicate lifecycle ownership and
  create a second source of truth.
- Browser-specific business endpoints: they would couple presentation to
  backend semantics and duplicate validation.
- Trusting loopback without browser-origin controls: a malicious web page can
  issue requests to localhost.
- Reusing the old binary `/command` surface as the browser API: its signed
  coordination envelope is an internal protocol, not a stable UI contract.

## Validation and invalidation

The implementation must prove loopback binding, exact Host/Origin policy,
bootstrap single use, session/CSRF rejection, bounded bodies and SSE queues,
DTO secret exclusion, real project projections, real onboarding delegation,
subscriber cleanup, and clean runtime shutdown. The architecture must be
revisited if the browser needs remote administration, durable offline events,
multi-user tenancy, or independently scaling control-plane work.
