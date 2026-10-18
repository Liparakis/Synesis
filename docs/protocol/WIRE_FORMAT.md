# Synesis Link Wire Format

Wire data uses explicit versioned framing, bounded lengths, deterministic byte
order, and structured rejection. Java object serialization is prohibited.

## Compatibility number

Two builds connect only if they carry the same **compatibility number**
(`LinkCompatibility.CURRENT`, currently `10`). It is raised whenever any format
sent between computers changes, and for nothing else; it is not the build
string. It is an unsigned 16-bit big-endian value in a position no later
layout may move, so a build with another number can still read it and refuse
with a clear message instead of reporting corruption:

- `synesis://join/SLB3-...`, `synesis://answer/SLA3-...` and `synesis://project/SLP1-...`: the first two bytes
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

## Returning-peer handshake (no invitation)

A host whose invitation has been consumed may admit the peer that consumed it again. No frame or field is new. The
dialer sends the same SLH1 envelope and SLT1 transcript as for an invitation, with two differences: the transcript's
invitation capability is empty (length `0`; an invitation's is always `12` bytes), and the session ID and both nonces
are fresh for the attempt. The host reads an empty capability as "returning peer", asks its local rule whether the
claimed initiator node ID may come back, and if so runs the ordinary exchange: its own proof, both proofs verified
against the transcript's node IDs and public keys, the transcript fingerprint accepted by the replay guard. The rule is
asked a second time with the proved identity before the session is published. Every refusal is the existing
`HANDSHAKE_REPLAY_REJECTED` (not named, not consumed, replayed) or `IDENTITY_PROOF_INVALID`/`IDENTITY_MISMATCH` (proof
or identity); no code is added to the table, and a host that offers no rule answers every empty-capability handshake
as `HANDSHAKE_REPLAY_REJECTED`, which is what an older build does. A host that restarted treats its invitation as consumed
before the restart and so reads empty-capability handshakes the same way; its rule then names only the nodes it admitted
before (the replay guard is loaded from the host's `replication.replay`; without it the host resumes nothing). A peer with a wrong key fails the proof check: node ID
is derived from the public key, and a transcript naming a node with another key cannot be built.

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

## `synesis://project/SLP1-...` project invitation

One link that invites a person into a project (ADR-0098, "Joining, revised"). URL-safe base64 without padding over, in
order:

| Width | Field                                                                                        |
|------:|----------------------------------------------------------------------------------------------|
|     2 | compatibility number (`10`)                                                                  |
|    16 | project UUID, most significant long first                                                    |
|     8 | project creation time, epoch seconds as a signed `long`                                      |
|     4 | project creation time, nanosecond of the second                                              |
|     2 | code remote URL length `n`, 0 to 256                                                         |
|     n | code remote URL, UTF-8; empty when the inviter's project has none                            |
|     2 | length `m` of the connection invitation that follows                                         |
|     m | the inviter's `SLB3` payload, byte for byte, with its own signature                          |
|    64 | Ed25519 signature by the inviter's node key (the key inside the `SLB3` payload)             |

The signature covers the ASCII domain `synesis-link/SLP1/project` followed by every byte before it, so the project
identity, the URL and the exact connection invitation are all signed: a signed `SLB3` payload cannot be moved onto
another project, and the URL cannot be changed. (The `SLB3` signature alone covers only its own fields; extending it
would have changed the payload the joiner hands to Link, which stays unchanged.) A parser verifies both signatures, the
inviter binding and the `SLB3` validity interval, so the link expires with its connection invitation (ten minutes at
most).

The URL never carries credentials: the user and password of an `http(s)` URL are removed by the inviter, a URL with a
password is not accepted by a parser, and neither a URL beginning with `-` nor a Git remote-helper URL (`ext::...`) is
accepted. Only `https://host/path`, `ssh://[user@]host/path` and `user@host:path` are accepted (a local path, a file URL,
a backslash or a Windows network-share path would make a later fetch or push open another share or write another
repository), both when an inviter makes a link and when a parser reads one. At most 1,200 URI characters, no trailing bytes, canonical re-encoding required.

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

Compatibility `8` (ADR-0098 step 4; until compatibility `7` a pull carried no
log, and members proposed their entries). Carried as the opaque payload of one
Link application stream between two computers of a project, and as the body
of the loopback `POST /replication` route of a computer's coordination server.
One request, one answer per stream. Big-endian, strict: every length is
checked before allocation, nothing may follow the last field, and a message is
at most 524,288 bytes (the Link application payload bound).

Header, all messages: `SRL1` (4 bytes), version `1` (1 byte), kind (1 byte).
A *node* is a length u8 `1..128` and that many UTF-8 bytes of a node ID.

| Kind | Name | Direction | Fields after the header |
|-----:|------|-----------|-------------------------|
|  `1` | PROPOSE | (step 2 only) | project UUID (16), entry length u32 `1..81920`, entry bytes. Since compatibility `8` it is answered `REFUSED REPLICATION_REQUEST_UNSUPPORTED` |
|  `2` | PULL | either way | project UUID (16), log node, after u64 (the requester's head of that log), max entries u32 `1..1024`, max bytes u32 `1..524288` (soft) |
|  `3` | NOTICE | either way | project UUID (16), head u64: how many entries the sender holds in all; advisory |
|  `4` | HEADS | either way | project UUID (16) |
|  `5` | CLAIM_SEEN | either way, and process to its own server on loopback | project UUID (16), entry length u32 `1..81920`, the signed `WORK_INTENT_ANNOUNCED` entry of a claim just appended |
|  `6` | CLAIM_INTENT | process to its own server, loopback only | project UUID (16), length u32 `1..81920`, the lane about to be claimed as `WORK_INTENT_ANNOUNCED` encodes it |
| `16` | ACCEPTED | (step 2 only) | sequence u64 |
| `17` | HEAD_MOVED | (step 2 only) | head u64 |
| `18` | REFUSED | answer to any request | code length u8 `1..64`, code (UTF-8) |
| `19` | UNREACHABLE | (step 2 only) | none |
| `20` | NO_ANSWER | (step 2 only) | none |
| `21` | ENTRIES | answer to PULL | count u16 `0..1024`, then per entry length u32 `1..81920` and bytes, in sequence order |
| `22` | NOTICED | answer to NOTICE | none |
| `23` | HEAD_LIST | answer to HEADS | count u16 `0..256`, then per log: node, head u64; nodes sorted, none twice |
| `24` | CLAIM_ANSWER | answer to CLAIM_SEEN, CLAIM_INTENT | code length u8 `0..64`, code (UTF-8): empty, or `OVERLAPPING_CLAIM` |

- **Every computer writes only its own log** and holds byte-identical copies of
  the others' (ADR-0098 step 4). A computer answers HEADS with every log it
  holds, its own under its own node ID, and PULL with the entries of the named
  log after `after`, its own or a copy: each entry is signed by its writer, so
  any computer may serve any log. The puller places them in
  `peers/<node>/events/` only if they continue its copy's chain and are signed
  by that node, else it reports `LOG_COPY_DIVERGED` and stops pulling that log.
  It never pulls its own log.
- **Who sent it is never a field.** The answering side takes the sender from
  the authenticated Link session (`PeerSession.remoteNodeId`) and serves only a
  node it admits: the owner's computer its members, a member the owner's
  computer. Anyone else is `REFUSED PROPOSER_NOT_A_MEMBER`.
- **The claim lock** (ADR-0098 step 4, designer items 4 and 19): a process
  sends CLAIM_INTENT to its own server before it appends a claim, then
  CLAIM_SEEN with the appended entry; the server sends CLAIM_SEEN to every
  connected computer and waits up to 5 seconds. A computer answers
  `OVERLAPPING_CLAIM` when it holds an overlapping active lane not of the
  claimant's computer, or checks an overlapping claim of its own and has the
  lower node ID; asked by a lower node while it checks, its own claim gives
  way. A registration whose check never starts is dropped after 15 seconds.
- **A pull page** holds at most the requested entries and about 256 KiB, plus
  at least one entry when one exists.
- **Notices are advisory.** A computer also pulls every 15 seconds, which keeps
  the path through a home router open.
- **Refusal codes:** `PROPOSER_NOT_A_MEMBER`, `PROPOSAL_PROJECT_MISMATCH`,
  `REPLICATION_REQUEST_UNSUPPORTED`, `REPLICATION_MESSAGE_INVALID`.

Local files of a computer, never sent to a peer, in the coordination folder
beside `events/`:

- `peers/<node>/events/%020d.sce`: the copy of another computer's log, the
  same files as the writer's.
- `applied.order`: one line per entry, in the order it took effect on this
  computer, appended and never rewritten: `self <sequence>` for this
  computer's own log, `<node> <sequence>` for a copy, and a third field, the
  rule's code, for an arriving entry that counted for nothing. A line's
  position among the lines without a code is the entry's local revision. A
  line without its newline is ignored, and removed by the next writer, which
  holds `order.lock`.
- `events-step2/` and `replication.properties.step2`: the log and the role
  file of a project shared with a step-2 build, kept as an archive by the
  first open of a step-4 build; the project then starts a fresh log.

- `replication.properties`: `role=member` then `leader=<node ID>`, or
  `role=leader` then any number of `member=<node ID>`. Either form may carry one
  `code.remote=<name>`, the name of the Git remote of the computer's clone through
  which code moves (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, never a URL). Absent means
  leader. A file that does not follow this exactly makes the log refuse every
  append and pull with `REPLICATION_ROLE_INVALID`.
- `replication.endpoint`: `127.0.0.1:<port>`, written by a member's
  coordination server while it runs; only a loopback host is ever used.
- `replication.status`: `CONNECTED`, `DISCONNECTED` or `DIVERGED`.
- `replication.members`: written by a leader's coordination server while it
  runs, one line per member its settings name, `<node ID> CONNECTED|DISCONNECTED
  <ISO-8601 instant>`: whether that member's session is attached, and since
  when (for a member with no session yet, since the server started). Readers
  skip lines that do not follow this. Removed when the server stops; absent
  with no members.

## Code over Link protocol `SCX1`

Compatibility `7` (ADR-0098 step 3; `8` since step 4 changed `SRL1`). Carried as the opaque payload of one Link application
stream between two computers of a project, beside `SRL1` and told apart by its
magic. One request, one answer per stream. Big-endian, strict: every length is
checked before allocation, every field against its bound, nothing may follow
the last field, and a message is at most 524,288 bytes.

Header, all messages: `SCX1` (4 bytes), version `1` (1 byte), kind (1 byte).
A *commit ID* is a length u8 `40` or `64` and that many lowercase hexadecimal
ASCII digits; a *piece ID* is a length u8 `64` and 64 such digits.

| Kind | Name | Direction | Fields after the header |
|-----:|------|-----------|-------------------------|
|  `1` | WANT | requester to holder | project UUID (16), wanted length u8 `1..255` and wanted (UTF-8), have count u16 `0..256`, that many commit IDs |
|  `2` | CHUNK | requester to holder | project UUID (16), piece ID, index u32 `0..2^31-1` |
| `16` | PIECE | answer to WANT | piece ID, total bytes u64, chunk count u32 (total bytes / 262,144 rounded up), tip commit ID, target commit ID, branch length u8 `0..255` and branch (UTF-8) |
| `17` | CHUNK_DATA | answer to CHUNK | index u32 `0..2^31-1`, length u32 `1..262144`, bytes, SHA-256 of the bytes (32) |
| `18` | UNAVAILABLE | answer to WANT, CHUNK, FETCH | code length u8 `1..64`, code (UTF-8) |
|  `3` | FETCH | process to its own coordination server, loopback only | project UUID (16), wanted length u8 and wanted: a commit ID or `HEAD` |
| `19` | FETCHED | answer to FETCH, loopback only | commit ID |

- **The holder names what it serves.** `wanted` is `refs/synesis/snapshots/<id>`
  or `refs/synesis/prepared/<id>` (`<id>` is `[A-Za-z0-9_-]{1,128}`), `HEAD`
  for the head of the holder's checked-out branch, or a commit ID that an
  entry of the holder's copy of the log names or that equals that head.
  Anything else is `UNAVAILABLE CODE_NOT_SERVED`; a served name whose commit
  the holder lacks is `CODE_NOT_HELD`. The holder resolves the name itself and
  passes Git only its own ref names and commit IDs; nothing is ever written
  into the requester's or the holder's branches.
- **A piece** is a Git bundle of the history from the haves the holder holds
  (it ignores the others) to `tip`, cut along first parents at the latest
  commit whose `rev-list --objects --disk-usage` stays within 150 MB; a single
  commit over that is a piece on its own. Its one ref is
  `refs/synesis/outgoing/<piece ID>`. `target` is the commit `wanted` resolved
  to; the requester asks again, naming `tip` as a have, until `tip` equals
  `target`. Total bytes `0` and chunk count `0` mean the haves already
  contain `target`. `branch` is the short name of the holder's checked-out
  branch when `wanted` is `HEAD` (letters, digits and `._/-`, starting with a
  letter or digit), so a computer that fetches the whole project names its
  branch alike; it is empty for any other `wanted`.
- **Chunks** are 262,144 bytes but the last. The requester checks each one's
  size and SHA-256, keeps it under `code-incoming/<piece ID>/` in its
  coordination folder, and after a lost exchange resumes from the first
  missing chunk. A whole piece then passes `git bundle verify` and a fetch
  with `fetch.fsckObjects=true` into `refs/synesis/incoming/<piece ID>`, whose
  commit must be `tip`; that ref and the files are deleted afterwards.
- **Prepared pieces** are kept under `code-pieces/` in the holder's
  coordination folder for 24 hours, named by a hash of (target, held haves),
  so a repeated WANT gets the same piece. An expired or unknown piece, or a
  chunk outside it, is `CODE_PIECE_UNKNOWN`; the requester then drops what it
  holds of it and asks again. A piece the holder cannot prepare is
  `CODE_PIECE_FAILED`.
- **Bundle commands** run for at most 10 minutes each, never under a lock.
- **FETCH and FETCHED never travel on Link.** A process of a shared project
  posts FETCH to `/code` on its coordination server (the address in
  `replication.endpoint`) and waits up to 11 minutes. The server moves the
  commit over Link, from the leader on a member, and on the leader from the
  member whose entry names it, else from each connected member; it refuses a
  commit no entry of its log names. A fetched commit that a snapshot entry
  names gets that entry's snapshot ref when the ref is absent. `HEAD` brings
  a member's checked-out branch up to the leader's by fast-forward only, and
  only when the checkout holds no change Synesis did not make; a computer
  with no repository yet gets the leader's branch checked out under its name.
  A Link peer that sends FETCH is answered `CODE_NOT_SERVED`.
