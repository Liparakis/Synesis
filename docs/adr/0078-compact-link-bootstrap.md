# ADR-0078: Compact Link first-contact bootstrap

- Status: ACCEPTED FOR SL-D-032
- Date: 2026-09-13
- Decision scope: Link first-contact invitation, CLI onboarding, and the
  authenticated candidate-descriptor exchange

## Context

The current human-mediated SLO1/SLA2 path is correct but carries the complete
signed candidate descriptors in copyable links. A normal local or directly
reachable onboarding path does not need the complete descriptors before an
authenticated Link session exists. The existing handshake, however, requires
both durable public keys in its transcript before either proof can be
verified. Removing the inviter key from the first-contact artifact would
either require a new pre-auth handshake or turn the first received key into
trust-on-first-use; both are outside this bounded change.

The existing direct QUIC transport, expected-identity checks, invitation
admission, and `PeerSession` application-stream seam are already the correct
security and lifecycle boundaries. The change therefore needs an additive
wire format and a narrow onboarding path, not a transport or overlay rewrite.

## Decision

Add a strict versioned compact bootstrap invitation with a bounded binary
payload and a `synesis://join/SLB1-...` representation. The invitation carries
only protocol version, a fresh admission nonce, a short absolute expiry, the
inviter's compact Ed25519 public key, one signed non-relay UDP endpoint hint,
and the inviter's Ed25519 signature over those fields.

The compact form intentionally does not carry the complete signed candidate
descriptor. Its public-key/signature lower bound makes a universal
150-character URI unrealistic for Ed25519; the implementation must report
measured URI lengths rather than claim an arbitrary target. It is nevertheless
substantially smaller than the current descriptor-bearing links. The endpoint
is a hint, not a reachability guarantee, and the existing legacy SLO1/SLA2
path remains decodable and available for coordinated candidate exchange.

The compact join path uses the existing invitation admission and authenticated
QUIC handshake. After reciprocal `CONTROL_READY`, the joiner sends its signed
candidate descriptor through one bounded application-stream request. The host
validates the descriptor's signature and authenticated node-ID binding, then
returns its own signed descriptor. The joiner validates the returned descriptor
against the authenticated remote identity. Rich candidate state therefore
enters Link only after identity authentication; it is not trusted merely
because an endpoint was present in an invite.

The default terminal host/join flow uses the compact one-way path. Existing
SLO1/SLA2 parsing and the explicitly mediated APIs remain compatibility paths;
unsupported NAT/firewall topologies continue to fail or require the mediated
path. No Synesis service, rendezvous, relay, TOFU, project-membership
authority, overlay topology, or QUIC path-migration behavior is introduced.

## Alternatives rejected

- Do not compress the existing SLO1/SLA2 blob in place; that would make old
  parsers ambiguous and would keep pre-auth rich state in the normal link.
- Do not omit the inviter public key; the current transcript cannot be built
  without it, and learning it as the first key would be TOFU.
- Do not move candidate descriptors into unauthenticated UDP datagrams; the
  authenticated application stream already provides the required identity and
  bounded framing boundary.
- Do not redesign QUIC, overlay routing, membership, project authorization,
  discovery, relay, or NAT traversal in this task.

## Consequences and evidence

The normal copy/paste experience becomes one compact invite and no answer for
directly reachable endpoints. The legacy two-link flow remains available for
coordinated traversal and compatibility. Focused protocol tests must cover
canonical parsing, tamper/expiry/replay/wrong-identity rejection, measured
payload sizes, and descriptor exchange. Loopback onboarding must prove that
the endpoint hint establishes an authenticated session and that both complete
descriptors are exchanged only after control readiness. No physical Internet
or universal NAT claim follows from local evidence; that remains SL-D-035.

## SLB2 minimization refinement — 2026-09-13

The first implementation was measured before changing its wire boundary. An
IPv6 SLB1 record is 147 raw bytes: 4 magic, 1 format, 2 protocol, 4 issue
seconds, 4 expiry seconds, 16 capability, 32 raw Ed25519 public-key bytes, 1
candidate type, 1 address length, 16 address, 2 port, and 64 signature bytes.
Base64URL expands that to 196 payload characters; the
`synesis://join/SLB1-` prefix adds 20, producing the observed 216-character
URI. IPv4 is 135 raw / 180 payload / 200 URI.

The normal encoder now emits `synesis://join/SLB2-...`. SLB2 is a clean wire
revision, not a silent SLB1 mutation. Its payload is fixed to Link protocol v1
and contains issue epoch seconds (4), lifetime seconds (2, bounded to the
ten-minute maximum), a random 12-byte admission capability, the 32-byte raw
Ed25519 public key, one raw IPv4 or IPv6 address, the 2-byte UDP port, and the
64-byte Ed25519 signature. The URI revision replaces the redundant inner
magic/format/protocol fields. Exact payload length selects the address width,
so candidate type and address-length fields are not serialized; the parsed
hint is normalized to a non-relay direct type and the signed full descriptor
still arrives through SLX1. The expiry is derived from signed issue plus
lifetime, preserving the future-skew and expiry checks while removing four
bytes of repeated absolute time.

The resulting deterministic sizes are 120 raw / 160 payload / 180 URI for
IPv4 and 132 raw / 176 payload / 196 URI for IPv6. The fixed cryptographic
floor is 32 bytes of host key plus 64 bytes of Ed25519 signature. The 12-byte
capability is a 96-bit online admission guard, not a secret project credential:
the invitation is already a bearer artifact, the host signature prevents
forged field changes, the host reserves it once, and the Link transcript binds
it. 2^96 guessing remains outside the bounded ten-minute online threat model;
reducing it further would not be justified here.

The detached host signature remains necessary. The Link handshake signs and
verifies the transcript, but without the signed invitation an attacker could
replace both the endpoint and the host key in the invitation and then prove
possession of that replacement key. That would be trust-on-first-use rather
than trust-on-connect. SLB2 therefore retains the host key, endpoint, issue /
lifetime, capability, and signature before contact. Rich descriptors,
additional candidates, project identity, membership, topology, and other
application state remain post-authentication material. SLB1, SLO1, and SLA2
remain decodable; only the normal compact encoder moves to SLB2. The revision
adds no RTT, signature, relay, rendezvous, project-authority, or NAT claim.
