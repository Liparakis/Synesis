# Synesis Link v1 Architecture Baseline

## Mode

GREENFIELD. The repository contains only persistence documents; no existing product architecture is available to preserve.

## Selected baseline

One Gradle project with explicit Java package boundaries. Public Synesis Link APIs remain transport-independent; an internal QUIC adapter owns Netty/native types. Identity, candidate, protocol, session, liveness, transport, observability, and test support are packages, not Gradle subprojects.

The baseline has one local process, no authoritative server, no database, no broker, and no mandatory rendezvous or relay. Direct connectivity is attempted from caller-supplied and locally gathered candidates; failure is diagnostic.

## Evidence ledger

| ID | Claim | Class | Effect | Validation |
|---|---|---|---|---|
| E1 | Product is a standalone local-first direct peer library. | USER-STATED | no central service or wider Synesis boundary | two-peer and failure tests |
| E2 | Identity, protocol, liveness, and bounded resources are mandatory invariants. | USER-STATED | explicit internal modules and state machines | TEST_MATRIX |
| E3 | Netty 4.2 exposes low-level QUIC channels, streams, and path events. | VERIFIED | suitable internal transport adapter | local QUIC integration |
| E4 | Native QUIC packaging is platform-sensitive. | VERIFIED | runtime/platform matrix and honest unsupported-path diagnostics | dependency resolution and two-machine tests |
| E5 | Expected production scale is not supplied. | UNKNOWN; keep reversible | no distributed scaling architecture | benchmark before extraction |

## Trust and ownership

- Application caller owns identity-storage policy, session lifecycle, and application streams.
- Synesis Link owns long-term identity operations, signed descriptors, authenticated sessions, liveness, and resource limits.
- QUIC/native code is a transport trust zone; its types do not cross the public API.
- Remote peers control candidate descriptors, handshake input, frame bytes, stream requests, and timing; all are bounded and validated before allocation.

## Reliability and contract gates

Every candidate attempt and handshake has a deadline and cancellation path. Retries create new authenticated attempts and never resurrect a terminal session. Control messages are bounded and isolated from application streams. Duplicate, stale, reordered, malformed, and oversized input is rejected deterministically.

## Rejected alternatives

- Multiple Gradle modules: rejected because no independent release, ownership, scaling, or storage boundary is evidenced.
- Mandatory rendezvous/relay: rejected because direct-first and serverless operation are product invariants.
- Custom QUIC/TLS: rejected because it expands security and maintenance risk without product value.
- HTTP/3 as the public abstraction: rejected because Synesis Link needs raw bidirectional streams and its own control protocol.

## Fitness functions

- `scripts/agent-doctor.ps1` rejects obvious scope leakage, secrets, stale task state, and missing package-info once Java exists.
- Gradle compile/test/Javadoc tasks must pass with warnings treated as failures where tooling permits.
- Public API tests must not import internal transport types.
- Protocol tests must run without public internet access.
- Resource-limit and lifecycle tests must prove bounded cleanup.
