# ADR-0007: Demo-only bounded work exchange

Status: accepted for demo validation only

## Context

CP-0030 proves authenticated control-ready sessions but carries no application
message. The first physical demonstration needs one visible cooperative action,
not a general RPC framework or production Synesis semantics.

## Decision

Add one namespaced example protocol, `synesis-demo-work/1`, over an authenticated
application stream. It supports one fixed `describe-session` request and one
correlated bounded result. UUID request IDs, bounded UTF-8 fields, fixed status
values, one request per stream, bounded stream count, deterministic timeout,
malformed/oversized rejection, and idempotent cleanup are required.

The public surface is transport-neutral. Netty types and native handles remain
internal. A request is legal only after `PeerSession.isUsable()`; the control
stream and liveness state remain independent. This protocol is a validation
fixture, not arbitrary method invocation, project synchronization, agent
authority, task delegation, or a production work protocol.

## Rejected alternatives

No generic RPC, dynamic service registry, arbitrary operation names, Java object
serialization, application traffic before authentication, or reconnect-aware
request retry is added.

## Invalidation conditions

The decision is replaced by a separately reviewed application protocol when
Synesis cooperation semantics are promoted. Any future protocol must specify
authority, duplicate work, cancellation, recovery, and stale-session behavior.
