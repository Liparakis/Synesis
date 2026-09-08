# Synesis Link Threat Model

## Scope

Principals are local callers, authenticated remote nodes, unauthenticated
network actors, local filesystem attackers, and dependency/native-library
suppliers. Assets include identity keys, identity bindings, transcripts,
liveness state, stream metadata, and resource capacity.

| Threat                                              | Boundary              | Mitigation                                                    | Residual risk / verification                       |
|-----------------------------------------------------|-----------------------|---------------------------------------------------------------|----------------------------------------------------|
| Forged or replayed descriptors                      | descriptor input      | canonical bytes, signatures, expiry, node-ID binding          | tamper and expiry tests                            |
| Impersonation or certificate substitution           | QUIC handshake        | expected long-term identity proof; TLS alone is insufficient  | wrong-key and substitution tests                   |
| Downgrade or replay                                 | protocol negotiation  | explicit versions, transcript binding, no 0-RTT               | replay/downgrade tests                             |
| Malformed/oversized input                           | wire parser           | validate before allocation and hard bounds                    | parser tests                                       |
| Stream, heartbeat, callback, or executor exhaustion | resource ownership    | bounded frames, queues, schedules, cleanup                    | saturation/leak checks                             |
| Key or log leakage                                  | storage/observability | storage abstraction and safe diagnostics                      | redaction tests                                    |
| NAT rebinding, loss, sleep, or partition            | transport/liveness    | QUIC path validation plus bounded application SUSPECT/EXPIRED | local fault evidence; physical migration unclaimed |

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

Only same-family non-relay pairs enter the race. Optional STUN Binding
responses are matched to their transaction and parsed under a strict size
bound; the provider is caller-owned and must share the eventual QUIC UDP
socket. Attempt count, concurrency,
staggering, per-attempt timeout, total timeout, and diagnostic count are
bounded. The success boundary is authenticated expected identity plus
control-ready `PeerSession`; raw transport success cannot win. Losers are
cancelled and late sessions are locally closed. Router discovery, TURN,
relays, hole punching, physical reachability, and path migration remain
unverified or unsupported rather than being represented as successful paths.
Signed traversal offers/answers bind session, both durable identities,
invitation context, expiry, nonce, and descriptors; rendezvous metadata is
untrusted routing input and cannot carry normal Link application traffic.

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

## Opaque application-stream mitigations

The SL-014 seam accepts bytes only from an authenticated control-ready Link
session. The session's verified remote node ID is available to the higher-level
handler; addresses and TLS success are not substitutes. `SLA1`, explicit outer
length framing, a 4,096-byte payload bound, a five-second deadline, a four
stream limit, and idempotent release constrain memory, time, and native
resources. Invalid markers, versions, oversized input, missing handlers,
terminal use, and handler failure close the stream without changing liveness or
publishing a session. Link does not interpret, persist, authorize, or retry the
payload; those risks belong to a later consumer.

## CP-R2 decision-record mitigations

`SDR1` is bounded before field allocation, uses strict UTF-8 and fixed status
values, and signs every canonical field. The embedded Ed25519 public key must
derive the declared owner ID; author and owner are equal in v1. SHA-256
predecessor digests make revision chains explicit. Revision bytes are
immutable and forced before an atomic head replacement. Startup validates the
whole local chain and rejects corrupt bytes rather than guessing a head.
The inspection launcher prints no private key or endpoint.

## CP-R4 decision-exchange mitigations

Project configuration is strict, bounded, atomically replaced, and uses an
explicit authenticated-node allowlist; there is no implicit membership. The
`SRP1` envelope has four message kinds, strict lengths/UTF-8, no trailing
bytes, and a 4,096-byte application bound. Every received record is checked
against the Link-authenticated remote node, project namespace, owner/author
binding, canonical signature, revision, and predecessor digest before storage.
Exact repeats are deterministic duplicates; stale and divergent revisions do
not replace the head. Valid divergent bytes are quarantined for inspection,
while invalid or unauthorized bytes are rejected. A stream completion without
a result is reported as `UNKNOWN`; there are no retries, reconnects, or
background workers. These controls provide pairwise two-process evidence only,
not replay protection across a future synchronization protocol or physical
network claims.

## SL-D-040 logical overlay and relay mitigations

The distributed overlay keeps five boundaries separate: signed project
membership, direct `PeerSession` adjacency, logical route selection, E2E
encryption, and physical transport. A bootstrap inviter is not promoted to
membership authority, an IP address is not a node identity, and a topology
advertisement cannot rewrite another node's statement.

`SLM1` membership snapshots are bounded, canonical, revisioned, expiring, and
Ed25519-signed by the declared authority. `SLT1` topology advertisements are
similarly bounded, origin-signed, membership-revision-bound, and rejected when
stale, conflicting, expired, unauthorized, duplicated, or oversized. The
local topology view stores one newest advertisement per origin and derives
routes only from currently valid entries. `SLP1` forwards the unchanged signed
`SLT1` bytes through authenticated direct peers with a separate bounded
propagation budget; newly accepted records reflood only to peers other than the
immediate sender. The deterministic policy is a full
mesh for at most three members and a sorted circular `+/-1` and `+/-2` graph
otherwise; it has maximum desired degree four and does not require consensus.

`SLK1` signs an ephemeral X25519 transcript with the durable Ed25519 identity.
The project, exact origin/destination, membership revision, session, nonces,
and transcript are bound into HKDF-SHA256 output. `SLE1` uses the derived key
with ChaCha20-Poly1305. Transit peers and relays see only bounded routing
metadata and ciphertext; they do not receive the E2E key. Wrong-recipient,
signature, transcript, ciphertext, and replay failures are fail-closed.

`SLF1` is a hop-local wrapper around only `SLK1` or `SLE1`. New frames require a
positive hop budget; forwarding consumes one hop and a zero-budget frame is
destination-only. Each forwarder keeps a bounded expiring duplicate key made
from project, message ID, and inner digest. The E2E receiver also maintains a
bounded sequence replay window. These controls terminate cycles and prevent a
duplicate from reaching application delivery twice without retaining an
unbounded message history.

Peer transit is authorized project traffic only. It uses existing authenticated
direct peer sessions through `OverlayPeerSessionBridge`; it is not a generic
TCP/UDP proxy and is not reconnect or path migration. The optional
`synesis-relay` module is one live Netty event-loop process. Its pinned
Ed25519 hello handshake, project/node allowlist, connection/frame/rate/queue
bounds, idle timeout, and live-only destination lookup prevent open-proxy
behavior. It stores no mailbox and does not claim offline delivery. A relay
cannot decrypt the immutable inner envelope; localhost socket acceptance is
separately dependent on the workstation's current JDK loopback compatibility.
