# Synesis Overlay v1

- Status: ACCEPTED FOR SL-D-040 IMPLEMENTATION
- Date: 2026-09-08
- Physical transport: existing authenticated Synesis Link sessions
- Logical transport: bounded project overlay

This document defines the first implementation contract for logical project
delivery. It does not change the existing Link handshake or imply Internet
reachability. `PeerSession` remains one authenticated physical adjacency.

## Security boundary

Membership is authorized by a signed project membership snapshot. Direct Link
authentication proves the identity of one physical neighbor. The overlay
accepts a route only when the origin, destination, and every forwarding peer
are members of the verified snapshot.

The logical payload is encrypted between the origin and destination. QUIC
protects each physical hop. A transit peer or organization relay may inspect
the project, destination, message ID, size, and timing required for routing,
but it must not receive the plaintext or the E2E session key.

## Canonical encoding rules

All records use bounded big-endian binary fields, reject trailing bytes, and
are signed or authenticated over their exact canonical bytes. Node IDs on the
wire are the 32-byte SHA-256 digest underlying the existing `sl1-` textual
form. Implementations must verify that a supplied public key derives exactly
that ID before accepting it.

Strings are not used in the v1 security records. Length fields are unsigned
and checked before allocation. The maximum member count is 32, matching the
current bounded project configuration.

## SLM1 signed membership snapshot

`SLM1` is issued by the project admission authority and is forwarded unchanged.
It is not a routing table and does not grant authority to the node that
forwards it.

```text
magic             4 bytes  ASCII SLM1
version           1 byte   1
project           16 bytes UUID
authority         32 bytes member/node ID
revision          8 bytes  positive unsigned sequence
issued-at         8 bytes  epoch milliseconds
expires-at        8 bytes  epoch milliseconds
member-count     1 byte   1..32
members           sorted member entries
signature-length  2 bytes  64 for Ed25519
signature         64 bytes authority signature
```

Each member entry is sorted by node ID and contains:

```text
node-id           32 bytes
status            1 byte   ACTIVE or REVOKED
key-length        2 bytes  bounded X.509 Ed25519 key
signing-key       key-length bytes
```

The authority public key is the member entry for `authority`; the signature
is verified against that key. A snapshot is accepted only when the project,
revision, expiry, member uniqueness, key-to-ID bindings, and signature are
valid. A node retains the highest valid revision it has seen and never applies
a lower revision as current. Revocation takes effect at the accepted snapshot
revision; existing physical sessions do not authorize new logical delivery
after the member is revoked.

The v1 contract does not define automatic authority succession. Loss or
compromise of the authority is an explicit project-management failure requiring
a separately authorized membership update.

The local `OverlayMembershipView` accepts a newer snapshot only when the
project and authority remain unchanged, the signature is valid, and the
candidate is currently usable. Lower revisions are stale, identical revisions
are idempotent, and conflicting same-revision bytes are rejected. Updating
this view does not establish sockets or reconnect a dead transport; the
direct-peer registry is refreshed only by authenticated Link/session owners.

## SLK1 end-to-end key agreement

`SLK1` records are logical messages. Transit peers forward them unchanged.
They are not application payloads and contain no private key material.

The initiator sends:

```text
magic             4 bytes  ASCII SLK1
version           1 byte   1
kind              1 byte   INIT
project           16 bytes UUID
origin            32 bytes node ID
destination       32 bytes node ID
session           16 bytes UUID
membership-rev    8 bytes  accepted SLM1 revision
init-nonce        32 bytes fresh random value
ephemeral-key     32 bytes X25519 public key
signature-length  2 bytes  64 for Ed25519
signature         64 bytes origin signature
```

The responder returns the same header identity fields and:

```text
kind              1 byte   RESPONSE
response-nonce    32 bytes fresh random value
ephemeral-key     32 bytes X25519 public key
transcript-hash   32 bytes SHA-256 of the exact INIT record without signature
signature-length  2 bytes  64 for Ed25519
signature         64 bytes destination signature
```

Both sides verify the sender against the accepted membership snapshot, exact
project/origin/destination/session values, membership revision, nonce sizes,
transcript hash, and signature before deriving a key. The derived secret is:

```text
shared = X25519(origin-ephemeral-private, destination-ephemeral-public)
salt   = SHA-256("synesis-overlay-v1" || project || session)
info   = "synesis-e2e-v1" || project || origin || destination ||
         membership-rev || init-nonce || response-nonce || transcript-hash
origin-to-destination-key = HKDF-SHA256(shared, salt,
         info || "origin-to-destination", 32 bytes)
destination-to-origin-key = HKDF-SHA256(shared, salt,
         info || "destination-to-origin", 32 bytes)
origin-to-destination-nonce-prefix = HKDF-SHA256(shared, salt,
         "synesis-nonce-v1" || info || "origin-to-destination", 4 bytes)
destination-to-origin-nonce-prefix = HKDF-SHA256(shared, salt,
         "synesis-nonce-v1" || info || "destination-to-origin", 4 bytes)
```

The exact byte concatenation uses the canonical binary fields above. A key is
valid only for this origin, destination, project, membership revision, and
session. Each endpoint uses the key and nonce prefix for its sending
direction and the independent pair for its receiving direction; envelope
`origin` and `destination` identify the direction of each logical message.
This prevents opposite-direction sequence zero from reusing an AEAD nonce.
Ephemeral private keys are erased after the session is closed or expires. No
manually distributed pairwise secret is accepted.

## SLE1 immutable E2E envelope

The plaintext is encrypted with `ChaCha20-Poly1305`. The 12-byte nonce is the
four-byte derived session prefix followed by the eight-byte big-endian sequence
number. Sequence numbers start at zero and cannot wrap. The following fields
are authenticated data:

```text
magic             4 bytes  ASCII SLE1
version           1 byte   1
project           16 bytes UUID
message           16 bytes UUID
origin            32 bytes node ID
destination       32 bytes node ID
session           16 bytes UUID
sequence           8 bytes unsigned sequence
ciphertext-length 2 bytes  16..3856 (plaintext 0..3840 plus tag)
ciphertext        bounded ciphertext and tag
```

The origin and destination fields, not the physical next hop, are immutable.
The destination rejects a wrong project, destination, session, sequence
window, nonce, or AEAD tag. The receiver keeps a bounded 64-entry replay
window per E2E session and delivers each message ID at most once for the
configured cache lifetime.

The 3,840-byte plaintext bound keeps one v1 logical envelope within the
existing 4,096-byte Link application payload bound after framing overhead.
Fragmentation is not part of v1; larger application objects need a later
versioned contract.

## SLF1 hop-local forwarding frame

`SLF1` wraps one `SLE1` or `SLK1` record for one physical next hop:

```text
magic             4 bytes  ASCII SLF1
version           1 byte   1
project           16 bytes UUID
destination       32 bytes node ID
message-id        16 bytes UUID or session UUID for SLK1
remaining-hops    1 byte   0..32 (0 is destination-only)
inner-length      2 bytes  bounded by the Link application payload limit
inner-record      inner-length bytes
```

The physical Link session authenticates the immediate sender. A newly created
frame must have a positive hop budget. Each forwarding node checks project
membership, destination membership, policy, frame size, and remaining hops,
then decrements the hop count before forwarding. A zero-budget frame may only
be delivered by its final destination and may not be forwarded. A node
deduplicates `(project, message-id, inner digest)` for a bounded expiry. A
malformed, exhausted, or duplicate frame fails closed or returns the
repository-equivalent bounded diagnostic; it never reaches the application a
second time.

The hop wrapper is intentionally not an E2E integrity claim. It is protected
by the authenticated physical hop and local forwarding rules. The inner
record's E2E authentication prevents a transit node from changing the logical
payload or destination without detection.

## Route policy

For a destination, the deterministic preference is:

1. direct usable `PeerSession`;
2. shortest path through authorized current project adjacencies, with stable
   node-ID tie-breaking and a maximum of 32 hops;
3. configured live organization relay;
4. `UNREACHABLE`.

The selected route is a read-model result, not part of the immutable E2E
record. Route changes do not require re-encryption. A relay is selected only
when policy permits it and the relay has an authenticated live connection to
the destination.

## Topology advertisements

`SLT1` is the canonical signed topology record:

```text
magic             4 bytes  ASCII SLT1
version           1 byte   1
project           16 bytes UUID
origin            32 bytes node ID
membership-rev    8 bytes  accepted SLM1 revision
origin-sequence   8 bytes  positive monotonic sequence
expires-at        8 bytes  epoch milliseconds
adjacency-count   1 byte   0..4
adjacencies       count * 32 bytes sorted node IDs
signature-length  2 bytes  64 for Ed25519
signature         64 bytes origin signature
```

The topology record is signed by its origin and binds project, membership
revision, origin node ID, monotonic origin sequence, expiry, and a bounded
sorted adjacency list. It is forwarded with a propagation budget and
deduplicated by origin/sequence/digest. Receivers reject invalid signatures,
wrong projects, stale membership revisions, stale sequences, expired records,
unknown members, duplicate neighbors, and oversized lists.

Direct-peer propagation uses a separate hop-local `SLP1` wrapper; the signed
`SLT1` bytes remain unchanged while the wrapper's propagation budget is
decremented. The wrapper is bounded and contains:

```text
magic             4 bytes  ASCII SLP1
version           1 byte   1
project           16 bytes UUID
origin            32 bytes node ID
origin-sequence    8 bytes positive sequence
remaining-hops     1 byte  0..8
advertisement-len  2 bytes bounded `SLT1` length
advertisement      bytes   exact signed `SLT1`
```

The local topology view admits only the newest verified advertisement for each
origin. A newly accepted record is sent to authenticated direct peers other
than its immediate sender; duplicate or stale records do not reflood. The
propagator does not create sockets, act as a membership authority, or use the
organization relay for topology gossip.

The initial desired graph is full mesh for three or fewer members and the
sorted circular offsets `+/-1` and `+/-2` for larger sets, with duplicate/self
edges removed. This gives a deterministic maximum degree of four while
retaining at least two desired neighbors when enough members exist. Unreachable
desired edges do not prevent route computation over currently usable edges.

## Relay contract

The relay authenticates the existing Link node identity and consults a
local operator configuration containing allowed project and node IDs. It
accepts only `SLF1` frames, enforces the same size/hop bounds, per-node/project
rate and queue limits, and idle/liveness deadlines, and forwards live traffic
only. It has no membership-authority role, no provider credentials, no generic
socket proxy, and no durable mailbox. An unavailable destination returns
`NO_ROUTE`; the relay never claims offline delivery.

## Required failure categories

Implementations must preserve enough distinction for diagnostics, including:

```text
MEMBERSHIP_INVALID
MEMBERSHIP_STALE
UNAUTHORIZED_PROJECT
UNAUTHORIZED_NODE
E2E_AUTH_FAILED
E2E_SESSION_UNAVAILABLE
REPLAY_REJECTED
DUPLICATE_SUPPRESSED
DESTINATION_MISMATCH
HOP_LIMIT_EXCEEDED
MALFORMED_ROUTE
NO_ROUTE
RELAY_UNAVAILABLE
FRAME_LIMIT_EXCEEDED
RATE_LIMITED
```

Diagnostics contain identifiers, categories, and bounded counters only; they
never contain private keys or plaintext.
