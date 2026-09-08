# ADR-0065: Coordinated direct UDP/QUIC traversal

- Status: ACCEPTED FOR IMPLEMENTATION
- Date: 2026-09-08
- Scope: Synesis Link only
- Architecture mode: EVOLUTION

## Context

Link already provides the security and transport boundaries that a direct
session needs: durable Ed25519 node identities, signed candidate descriptors
and invitations, Netty native QUIC, transcript-bound mutual authentication,
replay protection, control/application framing, bounded candidate racing, and
post-connect liveness. Its current candidate set is limited to local/manual
addresses. A host listens while a joiner initiates, so neither endpoint
discovery nor coordinated simultaneous traversal exists.

The requested capability is direct peer-to-peer connectivity across ordinary
NATs where the topology permits it. A rendezvous/bootstrap service may help
peers discover each other and exchange endpoint data, but it must not become a
Synesis authority or carry normal application traffic. An address, port,
invitation token, or rendezvous record is routing material, not identity.

## Decision

Extend the existing onboarding and candidate path with an optional direct
traversal layer. Keep the existing QUIC transport, `Candidate*` model,
`CandidateRacer` winner boundary, `SessionAuthenticator`, `PeerSession`, and
control/application protocols as the source of truth for an authenticated
usable session.

The extension has four bounded parts:

1. **Endpoint discovery.** Add an injected/configured server-reflexive
   candidate provider. It uses RFC 8489 STUN Binding semantics and must be
   bound to the same local UDP socket used for the QUIC attempt whenever the
   transport permits it. The STUN URI is configuration, never a hard-coded
   Synesis service. STUN output is an untrusted candidate and never an
   identity assertion. The provider is optional and has explicit timeout,
   response-size, and server-list limits.
2. **Bilateral signaling.** Add versioned, signed traversal offer/answer
   records around the existing one-way invitation. Each record binds the
   session to both node identities, the invitation admission context, a
   short expiry, a fresh attempt nonce, and bounded candidate descriptors.
   Rendezvous transports opaque records only; it cannot authorize a node,
   select application work, or terminate a session. Manual exchange remains
   supported.
3. **Coordinated attempts.** Both peers bind a bounded UDP socket and expose
   the QUIC server path while also making client attempts over that same
   socket. The coordinator forms only compatible, non-relay pairs and runs a
   bounded, staggered/simultaneous checklist. Duplicate authenticated
   sessions are expected during a race; a deterministic tie-breaker and the
   existing control-ready winner rule retain exactly one session and close
   the rest.
4. **Diagnostics.** Report discovery, signaling, traversal, authentication,
   timeout, and unsupported-topology failures distinctly. Once a session is
   usable, existing liveness states apply unchanged. A failed traversal must
   never be reported as a successful direct session.

The first implementation slice is deliberately staged: implement the signed
records, provider/coordinator seams, bounded state machine, and deterministic
local/simulated tests before claiming Internet traversal. The concrete STUN
adapter must pass a socket-ownership compatibility check against the selected
Netty QUIC path. Do not add a second network stack merely to hide that
compatibility question.

## Security constraints

- Verify signatures, node IDs, public keys, session binding, expiry, nonce,
  and invitation admission before using remote candidate data.
- Require the existing mutual Link handshake and replay guard before exposing
  application bytes; no 0-RTT or pre-auth application data is introduced.
- Treat rendezvous, STUN responses, addresses, ports, and tokens as attacker-
  controlled routing inputs.
- Bound record size, candidate count, pair count, concurrent attempts,
  response size, and all wait times. Redact private key material and
  invitation capability material from diagnostics.
- A topology failure is a supported outcome. The implementation must not
  silently fall back to a relay or claim universal NAT/CGNAT/firewall support.

## Alternatives considered

### Replace Link with a new ICE/QUIC stack

Rejected. It would discard existing identity, replay, handshake, winner, and
application evidence and would expand the change beyond the requested Link
boundary.

### Add ice4j as the first implementation

Deferred. ice4j is a credible Java ICE/STUN/TURN implementation, but its full
ICE agent and socket ownership model must be proven compatible with the
existing Netty QUIC channel before adding the dependency. Its use would also
need an explicit decision about TURN, which is outside this activation.

### Invent a proprietary discovery or hole-punching protocol

Rejected. Use standard STUN Binding semantics for server-reflexive discovery
and keep Link-specific signaling versioned, signed, bounded, and opaque to
rendezvous. Do not implement more protocol surface than the transport
integration requires.

### Make a hosted Synesis service mandatory

Rejected. Bootstrap is optional and non-authoritative. The core must retain a
manual/local path and must not depend on centralized normal traffic.

## Explicit non-goals

This ADR does not authorize TURN/relay traffic, router port mapping, DHT,
distributed Synesis state synchronization, provider credential handling,
transparent reconnect/path migration, or changes to the 10-tool MCP
contract. Reconnect remains deferred under `SL-D-036`.

## Verification gates

The evidence ladder is separate and cumulative:

1. Unit/property vectors for canonicalization, signatures, expiry, replay,
   wrong-peer binding, limits, and state transitions.
2. Two independent local processes with authenticated A→B and B→A
   application messages and clean/abrupt shutdown classification.
3. Controlled NAT simulation showing both a permitted direct path and an
   explicitly classified unsupported topology.
4. Physical two-network validation, if available, with captured endpoint,
   authentication, bidirectional-message, and shutdown evidence. Local or
   simulated evidence must never be labeled physical Internet success.

## References

- [RFC 8489 — Session Traversal Utilities for NAT (STUN)](https://www.rfc-editor.org/info/rfc8489/)
- [RFC 8445 — Interactive Connectivity Establishment (ICE)](https://www.rfc-editor.org/info/rfc8445/)
- [RFC 5128 — State of Peer-to-Peer Communication across NATs](https://www.rfc-editor.org/info/rfc5128/)
- [Netty 4.2 QUIC package API](https://netty.io/4.2/api/io/netty/handler/codec/quic/package-summary.html)
