# Synesis Link Threat Model

## Scope

Principals are local callers, authenticated remote nodes, unauthenticated
network actors, local filesystem attackers, and dependency/native-library
suppliers. Assets include identity keys, identity bindings, transcripts,
liveness state, stream metadata, and resource capacity.

| Threat | Boundary | Mitigation | Residual risk / verification |
|---|---|---|---|
| Forged or replayed descriptors | descriptor input | canonical bytes, signatures, expiry, node-ID binding | tamper and expiry tests |
| Impersonation or certificate substitution | QUIC handshake | expected long-term identity proof; TLS alone is insufficient | wrong-key and substitution tests |
| Downgrade or replay | protocol negotiation | explicit versions, transcript binding, no 0-RTT | replay/downgrade tests |
| Malformed/oversized input | wire parser | validate before allocation and hard bounds | parser tests |
| Stream, heartbeat, callback, or executor exhaustion | resource ownership | bounded frames, queues, schedules, cleanup | saturation/leak checks |
| Key or log leakage | storage/observability | storage abstraction and safe diagnostics | redaction tests |
| NAT rebinding, loss, sleep, or partition | transport/liveness | QUIC path validation plus bounded application SUSPECT/EXPIRED | local fault evidence; physical migration unclaimed |

## Identity and control path

TLS success, address, and QUIC connection ID are not node identity. The
application verifies the expected `sl1-` node ID against Ed25519 public-key
proofs over ALPN, version, roles, session ID, nonces, identities, and epochs.
`PeerSession` is published only after proof verification and reciprocal
CONTROL_READY. The two-process authenticated-session and wrong-identity tests
pass in the local harness; its TLS trust is intentionally test-only.

Control input is length-prefixed, bounded, versioned, and parsed without object
deserialization. Only one control stream is claimed. Wrong session IDs,
illegal flags, malformed payloads, reserved/unknown messages, and oversized
frames close the session. GOODBYE diagnostics contain no remote text. Close
writes are event-loop serialized, close requests share one terminal future, and
the two-second forced-close bound limits native-resource retention.

## SL-007 liveness mitigations

Heartbeat payloads are fixed-size, versioned, session-bound, and validated before
state mutation. New sequences must be consecutive; duplicates/stale input is
bounded diagnostic state and cannot refresh liveness. Future acknowledgements,
wrong-session payloads, malformed payloads, and sequence exhaustion fail
deterministically. Each heartbeat produces at most one ACK and an ACK never
produces an ACK, preventing response loops.

Only newest valid authenticated heartbeat activity refreshes monotonic liveness.
The sender marker is opaque and never treated as remote time. One scheduled
callback is pending at a time; no unbounded retry is created when a write fails.
A bounded daemon dispatcher isolates listener exceptions and slow callbacks from
the control read loop and is shut down with the session.

Crashes, sleep, blackholes, and partitions are inferred only after configured
suspect/expiry bounds; QUIC keepalive is not proof of application health.
Transport closure before expiry is reported as transport failure rather than
mislabelled expiry. No reconnect or session revival is performed here.

## SL-008 candidate and race mitigations

Candidate input is bounded per provider and in total, normalized before use,
deduplicated, and filtered for unsafe address scopes. Diagnostics expose stable
redacted identifiers rather than endpoint addresses. Provider timeout,
cancellation, invalid output, and failure are separate outcomes, so a broken
optional provider cannot suppress a usable provider or silently expand resource
use.

Only same-family non-relay pairs enter the race. Attempt count, concurrency,
staggering, per-attempt timeout, total timeout, and diagnostic count are
bounded. The success boundary is authenticated expected identity plus
control-ready `PeerSession`; raw transport success cannot win. Losers are
cancelled and late sessions are locally closed. Router discovery, STUN/TURN,
relays, hole punching, physical reachability, and path migration remain
unverified or unsupported rather than being represented as successful paths.

## Invitation and bootstrap mitigations

Invitations are bounded, canonical, signed by the host identity, expire after
ten minutes, and carry a random 32-byte capability. Parsing and descriptor
verification precede candidate use. The capability is bound into the existing
identity-proof transcript rather than treated as peer identity. A process-local
atomic admission reservation prevents concurrent reuse; reservations expire
after 15 seconds, release only before authentication, and are consumed after
mutual identity binding. The capability creates no permanent trust.

Long-term private identity material is stored through the atomic, overwrite-
preventing file store with restrictive permissions where supported. Public
metadata is separately checked against the loaded key. QUIC TLS keys are
ephemeral transport material and are deleted during host cleanup. QR output is
only another representation of the same link and has no separate trust path.

## Demo application mitigations

The example application stream is opened only from a usable authenticated
`PeerSession`. It has fixed protocol/version/kind fields, strict UTF-8
decoding, a 4,096-byte frame bound, bounded concurrent streams, one fixed
operation, UUID result correlation, duplicate-ID handling, and deterministic
close. Unmatched results, malformed input, oversized input, pre-ready streams,
and stream failures cannot publish a session or mutate heartbeat state.

The result message is fixed safe text; remote input is not copied into logs.
The CLI prints node IDs, session IDs, counts, statuses, and redacted pair IDs,
never private keys, proofs, full descriptors, passwords, or personal paths.
This protocol is not safe arbitrary agent execution and has no authority,
ownership, project, or task semantics.
