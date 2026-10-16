# Synesis Link Wire Format

Wire data uses explicit versioned framing, bounded lengths, deterministic byte
order, and structured rejection. Java object serialization is prohibited.

## Compatibility number

Two builds connect only if they carry the same **compatibility number**
(`LinkCompatibility.CURRENT`, currently `5`). It is raised whenever any format
sent between computers changes, and for nothing else; it is not the build
string. It is an unsigned 16-bit big-endian value in a position no later
layout may move, so a build with another number can still read it and refuse
with a clear message instead of reporting corruption:

- `synesis://join/SLB3-...` and `synesis://answer/SLA3-...`: the first two bytes
  of the payload. The prefixes are frozen and name only the kind of link; the
  `3` is history, not a version.
- the `SLH1` handshake envelope: the two bytes after the magic and the format
  byte (offset 5), before the role byte.
- the `SLF1` handshake failure: `INCOMPATIBLE_VERSION` (ordinal `7`) carries the
  sender's number as two further bytes (8 bytes in all); every other code stays
  6 bytes.

The invitation's number is inside the signed bytes. Builds from before this rule
cannot read the number: they report a new link as invalid or expired, and a
new build reports theirs as a different version only if the number is read at
all; this cannot be fixed (ADR-0088: prerelease formats may change).

## Authenticated session and SL-006 frames

The session transcript uses `SLT1`, one-byte major/minor version, bounded UTF-8
ALPN, UUID session ID, non-negative epochs, bounded nonces, and each role's
node ID plus X.509 public key. Lengths are unsigned 16-bit values validated
before allocation. Proofs cover the canonical transcript and role ordinal.

The authenticated control stream uses a four-byte big-endian QUIC length
prefix followed by this frame:

| Offset | Width | Field                                                                              |
|-------:|------:|------------------------------------------------------------------------------------|
|      0 |     4 | ASCII magic `SLH1`                                                                 |
|      4 |     1 | frame version `1`                                                                  |
|      5 |     1 | type: READY `1`, GOODBYE `2`, ACK `3`, ERROR `4`, HEARTBEAT `5`, HEARTBEAT_ACK `6` |
|      6 |     1 | message version `1`                                                                |
|      7 |     1 | flags, always `0`                                                                  |
|      8 |     4 | unsigned payload length, big-endian, `0..4096`                                     |
|     12 |     N | payload                                                                            |

The complete frame is at most 4,108 bytes. READY is session UUID plus maximum
payload (`20` bytes), GOODBYE is UUID plus close reason (`17`), GOODBYE_ACK is
UUID (`16`), and PROTOCOL_ERROR is UUID plus error code (`17`).

## SL-007 heartbeat payloads

HEARTBEAT and HEARTBEAT_ACK payloads are fixed at 41 bytes:

| Offset | Width | Field                                                |
|-------:|------:|------------------------------------------------------|
|      0 |     1 | payload version `1`                                  |
|      1 |    16 | current session UUID                                 |
|     17 |     8 | sender sequence, non-negative signed `long` encoding |
|     25 |     8 | related sequence; `-1` means “none yet”              |
|     33 |     8 | sender-local monotonic marker, opaque to the peer    |

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
before pair generation. The optional server-reflexive provider uses bounded
RFC 8489 STUN Binding request/response messages. Its transaction ID and mapped
address are routing inputs only, never node identity, and it must use the same
caller-owned UDP transport as the eventual QUIC path. A UPnP IGD port forward
on the host's router appears as a `MAPPED_IPV4` candidate. Unsupported PCP,
NAT-PMP, TURN, and DHT discovery do not produce wire claims or placeholder
candidates.

## `synesis://answer/SLA3-...` answer

The joiner's answer to a compact invitation (ADR-0097). URL-safe base64
without padding over: the invitation's 16-byte session UUID (most significant
long first), preceded by the two-byte compatibility number, then the endpoint list exactly as in `SLB3` (a count byte of 1 to
8 and, for each endpoint, an address-width byte of 4 or 16, the address, and a
two-byte port). Distinct endpoints only, no trailing bytes, at most 171 raw
bytes and 512 URI characters. The record is unsigned routing input and is
decoded only to find the host operation it answers and the addresses to send
toward.

## `synesis-demo-work/1` application frame

The demo frame is not part of the SLH1 control stream. It is length-prefixed by
the existing QUIC stream adapter and bounded to 4,096 unprefixed bytes:

| Offset | Width | Field                                 |
|-------:|------:|---------------------------------------|
|      0 |     4 | ASCII magic `SDW1`                    |
|      4 |     1 | version `1`                           |
|      5 |     1 | kind: request `1`, result `2`         |
|      6 |    16 | request UUID                          |
|     22 |     1 | result status, only for result frames |
|   next |     2 | unsigned UTF-8 field length           |
|   next |     N | operation or result message           |

Requests allow only `describe-session`; result statuses are `OK`,
`DUPLICATE_REQUEST`, `UNSUPPORTED_OPERATION`, and `MALFORMED`. No object
serialization or arbitrary method names are accepted. The frame is legal only
on an authenticated control-ready session.

## Bounded opaque application stream

The SL-014 seam uses the existing four-byte outer stream length prefix and an
inner bounded `SLA1` marker so the responder can distinguish an application
stream from the control handshake and the demo fixture:

| Offset | Width | Field                           |
|-------:|------:|---------------------------------|
|      0 |     4 | ASCII magic `SLA1`              |
|      4 |     1 | frame version `1`               |
|      5 |     N | opaque payload, `0..524288` bytes |

The payload bound is 512 KiB (`ApplicationStreamCodec.MAX_PAYLOAD_BYTES`); it was
4,096 bytes until compatibility `5` and is independent of the 4,096-byte
control-frame bound. The outer length prefix of an inbound stream is bounded by
the handshake envelope (8,192 bytes) until the session is authenticated and by
the application frame (524,293 bytes) after it, so an unauthenticated peer
cannot make a side buffer more than a handshake frame.

The complete inner frame is at most 524,293 bytes. It is legal only after
reciprocal `CONTROL_READY`; it carries no project or application semantics.
Unknown versions, malformed markers, oversized payloads, missing handlers, and
terminal streams are rejected without publishing or reviving a session.

## Project log replication protocol `SRL1`

Compatibility `5`. Carried as the opaque payload of one Link application stream
between a project leader and a member (ADR-0098 step 2), and as the body of the
loopback `POST /replication` route of a member computer's coordination server,
which relays a local process's request over the Link session. One request, one
answer per stream. Big-endian, strict: every length is checked before
allocation, nothing may follow the last field, and a message is at most
524,288 bytes (the Link application payload bound).

Header, all messages: `SRL1` (4 bytes), version `1` (1 byte), kind (1 byte).

| Kind | Name | Direction | Fields after the header |
|-----:|------|-----------|-------------------------|
|  `1` | PROPOSE | member to leader | project UUID (16), entry length u32 `1..81920`, entry bytes: one signed log entry exactly as it would be placed |
|  `2` | PULL | member to leader | project UUID (16), after u64 (member's head sequence), max entries u32 `1..1024`, max bytes u32 `1..524288` (soft) |
|  `3` | NOTICE | leader to member | project UUID (16), head u64 |
| `16` | ACCEPTED | answer to PROPOSE | sequence u64 of the entry on the leader's log |
| `17` | HEAD_MOVED | answer to PROPOSE | leader's head u64; nothing was placed |
| `18` | REFUSED | answer to PROPOSE, PULL, NOTICE | code length u8 `1..64`, code (UTF-8); nothing was placed |
| `19` | UNREACHABLE | made by a relay | none: the request was not sent |
| `20` | NO_ANSWER | made by a relay | none: the request may have been sent and was lost |
| `21` | ENTRIES | answer to PULL | count u16 `0..1024`, then per entry length u32 `1..81920` and bytes, in sequence order |
| `22` | NOTICED | answer to NOTICE | none |

- **Who sent it is never a field.** The leader takes the sender from the
  authenticated Link session (`PeerSession.remoteNodeId`) and requires it to
  be the signer of a proposed entry and a member of the project. A pull from a
  node that is not a member is `REFUSED PROPOSER_NOT_A_MEMBER`.
- **A pull page** holds at most the requested entries and about 256 KiB (the
  leader caps `max bytes` there), plus at least one entry when one exists.
- **A proposal sent again** carries the same bytes; the leader answers an entry
  it already holds as `ACCEPTED` with its sequence. A relay that cannot get an
  answer reports NO_ANSWER and the log sends the same bytes again, up to three
  times, then reports `LEADER_OUTCOME_UNKNOWN`.
- **Refusal codes** are the leader's: the codes a local append gives
  (`OVERLAPPING_CLAIM`, ...), `PROPOSAL_SENDER_MISMATCH`, `PROPOSER_NOT_A_MEMBER`,
  `EVENT_TYPE_LEADER_ONLY`, `PROPOSAL_PROJECT_MISMATCH`,
  `PROPOSAL_SIGNATURE_INVALID`, `PROPOSAL_MALFORMED`,
  `REPLICATION_REQUEST_UNSUPPORTED`, `REPLICATION_MESSAGE_INVALID`.
- **Notices are advisory.** A member also pulls every 15 seconds, which keeps
  the path through a home router open.

Local files of a computer, never sent to a peer, in the coordination folder
beside `events/`:

- `replication.properties`: `role=member` then `leader=<node ID>`, or
  `role=leader` then any number of `member=<node ID>`. Absent means leader. A
  file that does not follow this exactly makes the log refuse every append and
  pull with `REPLICATION_ROLE_INVALID`.
- `replication.endpoint`: `127.0.0.1:<port>`, written by a member's
  coordination server while it runs; only a loopback host is ever used.
- `replication.status`: `CONNECTED`, `DISCONNECTED` or `DIVERGED`.
