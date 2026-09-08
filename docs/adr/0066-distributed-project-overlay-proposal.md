# ADR-0066: Distributed project overlay and opaque routed envelopes

- Status: ACCEPTED FOR SL-D-040 IMPLEMENTATION
- Date: 2026-09-08
- Scope: Synesis Link and its project-facing consumers
- Architecture mode: EVOLUTION / REVIEW

## Context

The requested capability is larger than direct Link traversal. A project needs
to deliver one logical message through a direct peer, an authorized project
peer, or an optional organization-operated relay while keeping the logical
payload end-to-end encrypted for its destination.

Current repository evidence does not yet provide the required authority or
runtime:

- `PeerSession` represents one authenticated physical peer adjacency.
- Link identities are persistent Ed25519 signing identities; no Link E2E
  encryption/session layer exists.
- The application stream is a bounded request/response transport and Link does
  not interpret, authorize, persist, or route its bytes.
- `ProjectConfig` is a local bounded authenticated-peer allowlist, not a
  distributed signed membership authority.
- `SL-D-035` is active for direct UDP/QUIC traversal and explicitly excludes
  relay traffic and distributed Synesis state.

This ADR is the accepted architecture for staged SL-D-040 implementation. It
is not evidence that any production overlay behavior exists; each slice still
requires its own code, test, and failure evidence.

## Product invariants and non-goals

The following are hard invariants for any future implementation:

1. Project membership, direct adjacency, logical routes, E2E security, and
   physical QUIC transport remain separate concepts.
2. A bootstrap inviter is not thereby a project authority or permanent router.
3. Transit peers and organization relays forward bounded Synesis envelopes;
   they are never generic TCP/UDP proxies.
4. The origin and destination are the only application-payload decryption
   endpoints. Transit infrastructure may observe routing metadata, size, and
   timing; metadata anonymity is not promised.
5. Existing Link authentication, replay checks, liveness, and `PeerSession`
   physical-session semantics remain authoritative.
6. There is no durable mailbox, offline delivery, horizontal relay cluster,
   hosted Synesis relay, browser UI, HTTP control plane, or reconnect/path
   migration in this capability.

## Proposed architecture

Keep `PeerSession` as the physical authenticated adjacency and add a project
overlay above it:

```text
project runtime
    -> ProjectOverlay / Router
       -> verified membership view
       -> direct-adjacency view (PeerSession instances)
       -> signed topology view
       -> deterministic route table
       -> E2E logical envelope
          -> direct PeerSession, project transit, or configured relay
```

Do not turn `PeerSession` into a multi-hop logical connection. The overlay
selects one next physical hop and asks that peer to forward an opaque envelope.

### Ownership and boundaries

| Boundary | Owner | Responsibility | Must not own |
|---|---|---|---|
| Project membership | project-facing authority/record module | signed membership, keys, admission, revocation | sockets or route choice |
| Link adjacency | `link` | QUIC, handshake, liveness, direct session identity | project membership |
| Project overlay | `link` or a tightly coupled Link overlay package | topology view, route selection, hop policy, forwarding | provider credentials or generic proxying |
| E2E session/envelope | Link security layer | key agreement, AEAD, replay window, destination binding | transit plaintext |
| Organization relay | separately operated relay process | authenticated live envelope forwarding and quotas | project authority, durable mailbox, plaintext |

The relay boundary is justified only because it has a distinct operator,
privilege scope, failure domain, and deployment lifecycle. It must share no
project database with the application and should reuse the Link protocol
library rather than duplicate identity or framing logic.

## Membership authority

The existing local allowlist is sufficient for current pairwise record sync but
not for distributed admission or topology propagation. Before overlay code,
define a bounded signed membership snapshot owned by an explicit project
admission authority. The snapshot should bind:

- project identifier and protocol version;
- authority identity and monotonically increasing revision;
- member node IDs and their authenticated signing/encryption descriptors;
- validity/expiry and revocation state;
- the authority signature over the canonical snapshot.

Nodes accept topology and routed traffic only for members in a verified current
snapshot. A node forwarding a snapshot does not become authoritative. The
authority role governs membership, not routing, and is therefore not a
permanent central data path.

The exact authority lifecycle, revocation semantics, and storage owner are
architecture-changing decisions and require a separate activated task. Do not
derive membership from an inviter, a topology advertisement, an IP address,
or an unsigned peer claim.

## Topology and route selection

For a small project, a deterministic full mesh is acceptable. Above that
threshold, the initial policy should be a deterministic circular-neighbor
graph: sort stable member node IDs, connect each node to offsets `+/-1` and
`+/-2` modulo the sorted set, removing duplicate/self edges for small sets.
This yields a connected, non-centralized graph with maximum degree four and
stable tie-breaking. The policy is configurable and must be measured before
being optimized; it is not a promise that every desired edge is reachable.

Each node maintains distinct views:

- verified membership;
- currently usable direct `PeerSession`s;
- desired neighbors from the policy;
- received signed topology advertisements;
- computed routes.

Topology advertisements are canonical, bounded, signed by their origin, bound
to project and membership revision, carry an origin sequence/expiry, and are
forwarded with a propagation limit. Deduplicate by origin/sequence/digest,
reject stale or malformed records, and never allow an intermediate peer to
rewrite an advertisement. No consensus protocol is needed for transient
adjacency.

Route choice is deterministic and prefers:

1. direct usable adjacency;
2. shortest authorized project-peer path with lexicographic tie-breaking;
3. configured live organization relay;
4. `UNREACHABLE`.

Route calculation must use the current adjacency graph, not blindly assume
that every desired edge exists. Establishing a fresh desired adjacency remains
separate from reconnecting a dead session under `SL-D-036`.

## E2E security and routed framing

Ed25519 is retained as the durable signing identity. It must not be treated as
an encryption key. The accepted v1 contract uses X25519 plus HKDF-SHA256 plus
ChaCha20-Poly1305, all exposed by the current Java 25 runtime as recorded in
`docs/evidence/sl-d-040-java25-crypto-probe-2026-09-08.md`. No custom primitive,
ad-hoc Ed25519 conversion, or manual pairwise secret distribution is permitted.

The exact bounded records and canonical fields are defined by
`docs/protocol/SYNESIS_OVERLAY_V1.md`: `SLM1` membership snapshots, `SLK1`
E2E key agreement, `SLE1` immutable envelopes, and `SLF1` hop-local frames.

An E2E session handshake travels as an opaque logical message through existing
authorized hops. Ephemeral X25519 keys are signed by the durable Ed25519
identity and the transcript binds project, exact origin/destination IDs,
protocol version, nonces, and session epoch. The derived key is pairwise and
short-lived. The destination verifies the origin identity and transcript before
decrypting.

The immutable inner envelope contains only bounded fields such as:

```text
project, message ID, origin, destination, E2E session/epoch,
sequence, nonce, ciphertext, authentication tag
```

The hop-local forwarding frame contains routing metadata such as destination,
remaining hop budget, and the opaque inner envelope. Each authenticated hop
enforces the project policy, payload bound, hop limit, and local duplicate
cache. The destination enforces the E2E replay window and delivers a message
at most once. A transit node can drop, delay, or duplicate traffic, but cannot
decrypt or forge a valid inner envelope without the E2E key.

The current request/response application seam is not by itself a logical
delivery API. Add a typed bounded envelope-delivery seam above the physical
session, preserving the existing stream framing and keeping application
protocols such as project-record sync as consumers of the higher-level route.

## Relay

The optional relay is one vertically scalable live-forwarding process. It
authenticates Link node identities, checks a local operator configuration for
allowed projects/nodes, accepts only bounded Synesis envelopes, and routes by
destination node ID. It has no project membership authority and stores no
durable mailbox.

The relay contract must define connection identity, project authorization,
frame size, queue bounds, backpressure, idle/liveness deadlines, rate limits,
duplicate handling, error codes, observability, and shutdown behavior. No
retry may imply durable delivery. If the destination is not currently
reachable, return `NO_ROUTE` or the repository-equivalent diagnostic.

## Evidence ledger and unresolved decisions

| Claim | Class | Effect |
|---|---|---|
| `PeerSession` is a direct authenticated adjacency | VERIFIED | preserve it; add an overlay above it |
| Current project membership is a local allowlist | VERIFIED | membership authority is a prerequisite |
| Transit must not decrypt application payloads | USER-STATED / SECURITY INVARIANT | E2E envelope precedes forwarding |
| Java 25 exposes compatible X25519, HKDF-SHA256, and ChaCha20-Poly1305 APIs | VERIFIED | use the recorded probe as the implementation baseline |
| Ring plus bounded shortcuts is the best topology | ASSUMED | validate with 6–8 node route/failure tests |
| One relay process meets organization load | REQUIRES-MEASUREMENT | run representative concurrency/backpressure test |

Unknowns that affect trust or wire compatibility are architecture-changing;
they cannot be hidden behind a placeholder implementation. Performance
unknowns remain reversible until the representative benchmark exists.

## Alternatives rejected

- Mutating `PeerSession` into a multi-hop connection: conflates physical
  identity/liveness with logical routing and makes failure semantics opaque.
- Trusting the bootstrap inviter or unsigned topology claims: permits
  unauthorized membership and route injection.
- Hop-by-hop plaintext forwarding: violates the core confidentiality invariant.
- Full mesh as the permanent policy: quadratic connection growth and no
  bounded-degree guarantee.
- Raft, a DHT, Kafka, Redis, or a horizontally clustered relay: no evidence
  justifies their coordination and operational cost for developer-sized
  projects.
- Building a relay before membership and E2E contracts: creates an open-proxy
  and plaintext-confidentiality risk.

## Required implementation order and fitness functions

1. Activate an explicit task and settle membership authority, key descriptors,
   wire version, owners, and rollback boundaries.
2. Run the bounded Java crypto compatibility probe and add canonical E2E
   handshake/envelope vectors.
3. Implement direct logical delivery over one existing `PeerSession`; prove
   wrong-recipient, tamper, substitution, replay, and transit-decryption
   failures.
4. Implement explicit peer forwarding and deterministic route selection over a
   controlled static graph.
5. Add signed topology propagation and the bounded topology policy; test at
   least six to eight independent nodes.
6. Add the separately operated relay and its authorization, quota,
   backpressure, and concurrency tests.
7. Add read models/events for a future adapter, then complete Link regression,
   security, operations, and acceptance evidence.

Fitness functions must be executable or reviewable:

- every routed frame has bounded size, hop budget, and duplicate lifetime;
- a transit node's available keys cannot decrypt an A-to-C envelope;
- destination mismatch, replay, stale topology, unauthorized project, and
  malformed route all fail closed;
- no route uses a node absent from the verified membership snapshot;
- direct > peer transit > relay selection is deterministic;
- relay tests prove no durable storage and bounded queues;
- existing `:link:check` and `git diff --check` remain green.

## Activation gate

`SL-D-040` is now the sole active task and `TASKS.md`/`CURRENT.md` agree on
the scope. Production work may proceed only as bounded slices beginning with
the codecs and vectors for the accepted `SYNESIS_OVERLAY_V1` contract. No
forwarding, topology, or relay claim is valid until its matching evidence
exists.
