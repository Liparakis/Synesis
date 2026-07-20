# Synesis Link Wire Format

Wire data uses explicit versioned framing, bounded lengths, deterministic byte
order, and structured rejection. Java object serialization is prohibited.

## Authenticated session and SL-006 frames

The session transcript uses `SLT1`, one-byte major/minor version, bounded UTF-8
ALPN, UUID session ID, non-negative epochs, bounded nonces, and each role's
node ID plus X.509 public key. Lengths are unsigned 16-bit values validated
before allocation. Proofs cover the canonical transcript and role ordinal.

The authenticated control stream uses a four-byte big-endian QUIC length
prefix followed by this frame:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `SLH1` |
| 4 | 1 | frame version `1` |
| 5 | 1 | type: READY `1`, GOODBYE `2`, ACK `3`, ERROR `4`, HEARTBEAT `5`, HEARTBEAT_ACK `6` |
| 6 | 1 | message version `1` |
| 7 | 1 | flags, always `0` |
| 8 | 4 | unsigned payload length, big-endian, `0..4096` |
| 12 | N | payload |

The complete frame is at most 4,108 bytes. READY is session UUID plus maximum
payload (`20` bytes), GOODBYE is UUID plus close reason (`17`), GOODBYE_ACK is
UUID (`16`), and PROTOCOL_ERROR is UUID plus error code (`17`).

## SL-007 heartbeat payloads

HEARTBEAT and HEARTBEAT_ACK payloads are fixed at 41 bytes:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 1 | payload version `1` |
| 1 | 16 | current session UUID |
| 17 | 8 | sender sequence, non-negative signed `long` encoding |
| 25 | 8 | related sequence; `-1` means “none yet” |
| 33 | 8 | sender-local monotonic marker, opaque to the peer |

The control-frame type distinguishes HEARTBEAT from HEARTBEAT_ACK. An ACK
repeats the acknowledged sequence in field 17, puts the receiver's highest
heartbeat sequence in field 25, and echoes field 33. The marker is only for
local RTT correlation; it is never compared as a cross-machine clock.

Sequence zero is first. New inbound heartbeats must be consecutive; duplicate
heartbeats are ACKed but do not refresh liveness. Future ACKs are protocol
failures and stale ACKs are diagnostic-only. `Long.MAX_VALUE` is valid; a
further send fails with `HEARTBEAT_SEQUENCE_EXHAUSTED`.

Unknown types, unsupported versions, non-zero flags, invalid lengths,
truncation, excess bytes, wrong-session payloads, invalid sequences, and
invalid UTF-8 (no text field exists) are rejected. Terminal state is guarded
by one-shot close state; reconnect sequencing belongs to SL-009.

## Candidate descriptor and runtime boundary

The signed candidate descriptor remains bounded by the existing descriptor
limits and is authenticated as part of the node's signed descriptor. Its
candidate address, port, type, and priority are input data only; runtime
provider IDs, failure categories, deadlines, pair identifiers, and race
diagnostics are local implementation state and are not wire fields.

`RELAY` is an appended reserved candidate type for forward-compatible enum
decoding. SL-008 direct racing rejects it; no relay transport is implemented.
Runtime normalization canonicalizes IPv4-mapped IPv6 and rejects unspecified,
multicast, disallowed loopback/private scopes, and ambiguous link-local input
before pair generation. Unsupported PCP, NAT-PMP, UPnP, STUN, TURN, and DHT
discovery do not produce wire claims or placeholder candidates.

## `synesis://join/SYN1-...` invitation

The share link is URL-safe base64 without padding over a bounded canonical
binary invitation. The binary value contains magic `SIN1`, format version `1`,
protocol major/minor, session UUID, issue and expiry epoch seconds, a 32-byte
single-use capability, the complete signed candidate descriptor, and an Ed25519
signature over every preceding field. Raw invitations are limited to 12,288
bytes and links to 16,384 characters. Private keys, session keys, reusable
authority, and Java object serialization are forbidden.

The descriptor and invitation signatures are verified before candidate use. The
invitation expiry is ten minutes by default with the existing bounded clock-skew
policy. The capability is copied into the existing signed handshake transcript;
the host reserves it for at most 15 seconds and consumes it after mutual
identity proof verification. A valid invitation pins the host identity, while
the bearer capability authorizes one admission attempt only.

## `synesis-demo-work/1` application frame

The demo frame is not part of the SLH1 control stream. It is length-prefixed by
the existing QUIC stream adapter and bounded to 4,096 unprefixed bytes:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `SDW1` |
| 4 | 1 | version `1` |
| 5 | 1 | kind: request `1`, result `2` |
| 6 | 16 | request UUID |
| 22 | 1 | result status, only for result frames |
| next | 2 | unsigned UTF-8 field length |
| next | N | operation or result message |

Requests allow only `describe-session`; result statuses are `OK`,
`DUPLICATE_REQUEST`, `UNSUPPORTED_OPERATION`, and `MALFORMED`. No object
serialization or arbitrary method names are accepted. The frame is legal only
on an authenticated control-ready session.

## Bounded opaque application stream

The SL-014 seam uses the existing four-byte outer stream length prefix and an
inner bounded `SLA1` marker so the responder can distinguish an application
stream from the control handshake and the demo fixture:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `SLA1` |
| 4 | 1 | frame version `1` |
| 5 | N | opaque payload, `0..4096` bytes |

The complete inner frame is at most 4,101 bytes. It is legal only after
reciprocal `CONTROL_READY`; it carries no project or application semantics.
Unknown versions, malformed markers, oversized payloads, missing handlers, and
terminal streams are rejected without publishing or reviving a session.
