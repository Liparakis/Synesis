# Synesis Link v1 Protocol

The ALPN is `synesis-link/1`. Version negotiation, identity binding, bounded
control framing, application liveness, reconnect epochs, and typed streams are
implemented incrementally. No project, task, agent, ownership, or
synchronization semantics belong here.

## Authenticated establishment

Each endpoint states its compatibility number (see `WIRE_FORMAT.md`) in the
first SLH1 envelope, before anything else in it is parsed. If the numbers
differ, both sides fail with `INCOMPATIBLE_VERSION` naming both numbers, and the
failing side sends an `SLF1` failure frame carrying its own number so the peer
fails the same way. This happens before the transcript, admission or any
project data. Each endpoint then sends a bounded supported-version list in the
same SLH1 envelope. The selected highest exact intersection is in the signed transcript,
which binds ALPN, both node identities and public keys, both nonces, session ID,
and both epochs. Expected remote identity is checked before publishing
`PeerSession`; wrong identities fail as `IDENTITY_MISMATCH`.

### Returning peers

An invitation admits one peer once. A runtime that keeps its sessions (the project's coordination server) may in
addition tell the host endpoint which node IDs may come back after that: once the invitation is consumed the host
accepts a handshake with an empty invitation capability from exactly those nodes (`WIRE_FORMAT.md`, "Returning-peer
handshake"), on the same listening socket, so the UDP port in the invitation stays valid for as long as the host handle
is open. Both identities are authenticated as for the first session and a transcript is accepted once per host
process. Before the invitation is consumed, and on a host given no rule, nobody is admitted without the capability.
The dialing side, which kept the invitation's endpoints, dials them again (the endpoint the last session was made over
first) with a fresh session ID; when and how often is the caller's policy, not the protocol's.

Link itself keeps nothing across a restart of the process, but it can start from what its caller kept. A host can
be reopened on the UDP port it had (`Onboarding.resumeRetainedHost`) with an admission whose invitation counts as used
before the restart (`InvitationAdmission.markConsumed`); its capability is random and unknown to anyone, so it admits
without one only the nodes the caller's rule names, which the coordination server limits to the node IDs it recorded
for that port. A dialer can be restored from the invitation link its first session used
(`Onboarding.resumeRetainedJoin`): the link is checked as of its issue time, since only its signature and the host it
pins matter now, and `redial` dials its endpoints with no invitation. A retained host keeps its replay guard across a restart (`replication.replay`), and resumes no hosting without it.

## Control-ready liveness

After reciprocal `CONTROL_READY`, the session becomes `LIVE` and owns one
bounded one-shot heartbeat schedule. Defaults are one-second HEARTBEAT, three
seconds to `SUSPECT`, and five seconds to `EXPIRED`. They are local `Duration`
defaults, not wire constants. A session a runtime retains (`LivenessConfiguration.RETAINED`)
uses a five-second HEARTBEAT, fifteen seconds to `SUSPECT` and thirty to `EXPIRED`, and its QUIC
connection closes after sixty seconds without a packet; the one-shot `host` and `join` keep the
defaults and set no idle timeout. A host's QUIC server codec uses a shareable connection handler,
without which it accepted one connection and silently dropped every later one.

HEARTBEAT and HEARTBEAT_ACK are 41-byte, versioned payloads bound to the current
session UUID. Sequence zero is first and new inbound sequences must be
consecutive. Duplicate heartbeats may be ACKed but do not refresh liveness;
future ACKs fail and stale ACKs do not refresh. A valid newest heartbeat or ACK
refreshes the local monotonic peer-activity timestamp. Local writes, QUIC
keepalive, malformed/stale input, path changes, and arbitrary application
traffic do not.

`SUSPECT` is reversible uncertainty. Valid current-session activity before the
expiry decision emits recovery to `LIVE`; once `EXPIRED` is selected, late
traffic cannot revive the session. Delayed callbacks emit the meaningful
`LIVE -> SUSPECT -> EXPIRED` history. Graceful GOODBYE is not expiry; transport
closure before the application deadline is transport failure. Synesis Link
never claims instant crash detection.

## Direct candidates and racing

SL-008 adds a bounded direct-connectivity layer above authenticated transport.
The shipped providers are explicit manual candidates and local-interface
candidates. SL-D-035 adds an optional configured RFC 8489 Binding provider for
server-reflexive candidates; it owns no socket and must be attached to the
same UDP transport used by QUIC. The ordinary default policy leaves that
candidate type disabled, so no discovery service is contacted implicitly.
PCP, NAT-PMP, UPnP, TURN, relays, and DHT discovery remain unsupported.

Candidates are resolved and normalized before use; mapped IPv6 IPv4 addresses
become canonical IPv4, unsafe scopes are rejected, and duplicates retain the
best priority. Only same-family non-relay pairs are generated. Pair ranking and
provider/race limits are deterministic and bounded.

The race factory is a transport boundary: it may complete successfully only
after the existing authenticated handshake has verified the expected node
identity and reciprocal control readiness. A transport connection alone is not
a winner. The first valid winner atomically cancels other attempts; any late
successful loser is closed with a local-request reason. This layer does not
implement reconnection, path migration, or session revival. The optional
SL-D-035 traversal coordinator can retain simultaneous authenticated results
for a bounded settle window and choose one deterministic winner.

### Compact first contact

SL-D-032 adds the normal one-way first-contact artifact:
`synesis://join/SLB3-<base64url>` (ADR-0095). Its canonical payload is a
signed, bounded binary record beginning with the two-byte compatibility number
(read first, so a different number is refused before dialing and reported as a
different Synesis version, not as an invalid or expired invitation), then issue epoch seconds, a bounded
lifetime, a fresh 12-byte (96-bit) single-use admission capability, the
inviter's compact Ed25519 public key, and one to eight signed non-relay UDP
endpoint hints in the inviter's order of preference. The URI revision fixes the
Link protocol to v1, so the payload does not repeat an inner magic, protocol,
or candidate-type field. The endpoints follow the key as a count byte and, for
each endpoint, an address-width byte (4 or 16), the address, and a two-byte
port; a repeated address and port is rejected. The expiry is derived from
signed issue plus lifetime.
The public key and detached signature are retained before contact because the
joiner must authenticate the intended durable node; endpoints are retained
because a peer needs some destination before it can send the first QUIC
packet, and more than one because the inviter cannot see which of its
addresses the joiner's network reaches. The joiner dials every endpoint of an
address family it has at once and keeps the first authenticated session; the
host admits one attempt and refuses the rest. The capability, validity interval, and deterministic session ID
bind admission and replay handling to that one invitation.

The compact record contains no private key, project membership, provider
credential, full candidate set, or long-lived reconnect secret. An endpoint is
a signed routing hint, not an identity and not a promise of Internet NAT
reachability. The local candidate provider omits IPv6 link-local addresses from
wire candidates because their interface scope cannot be represented safely in
this artifact. It also omits every address of the loopback interface, and an
IPv6 address the machine does not send from: the listener is bound to the
wildcard address, the operating system chooses the source of each reply, and a
peer drops a reply from an address it did not dial. Endpoint selection orders
candidates server-reflexive, IPv6, manual, mapped, LAN, and takes one of every
kind present before a second of any kind, up to eight.

An SLB3 record with one IPv4 or one IPv6 endpoint is 124 or 136 raw bytes and
186 or 202 URI characters; each further endpoint adds 7 or 19 bytes, and a full
record of eight IPv6 endpoints is 269 bytes and 379 characters. The 32-byte
Ed25519 public key and 64-byte Ed25519 signature are the irreducible identity
trust floor for the existing self-contained non-TOFU construction. Removing
the detached signature would allow endpoint and key substitution; the normal
Link proof would then authenticate the attacker's supplied key. The 12-byte
capability remains above the defensible 96-bit online guessing floor for the
ten-minute single-use admission window.

After the existing QUIC handshake has authenticated the remote public key,
reciprocal control readiness is reached, and the session is admitted, Link
uses the bounded `SLX1` application envelope to request and return the complete
signed `CandidateDescriptor`. Each descriptor is still independently verified
and must match the already-authenticated node identity and invitation expiry.
This is an explicit descriptor exchange, not a trust-on-first-use key update.
Project identity and membership remain higher-level authorization decisions;
possession of an SLB3 endpoint hint does not grant project membership.

A project is shared with one link, `synesis://project/SLP1-<base64url>` (ADR-0098, "Joining, revised"): the same
SLB3 record, unchanged, inside a second signed record that adds the project identity and the inviter's code remote
URL (`WIRE_FORMAT.md`). The extra signature is made over everything before it, including the SLB3 bytes, so neither
the identity nor the URL can be changed or moved onto another connection invitation. The joiner dials the SLB3 inside
it exactly as before. A connection that authenticates through it is held, not served, until the inviter's person
accepts the joiner (`control-plane-v1.md`); this is the compatibility 6 change.

The one-way path is the normal UX for directly reachable endpoints. Across two
networks it is completed by an answer link (ADR-0097). Joining shows
`synesis://answer/SLA3-<base64url>`: the compatibility number, the invitation's session UUID and up to
eight of the joiner's endpoints, encoded like the invitation's. It is unsigned
routing input; the session that follows is authenticated exactly as above.
When the host's user pastes it, the host sends a one-byte datagram from its
listening socket to each endpoint four times a second for thirty seconds, so
its own router or firewall lets the joiner's attempts in. The joiner keeps
dialing until the invitation expires. Endpoints may include the
server-reflexive address a configured STUN server reports, and, when the host's
user chose it, a UPnP port forward on the host's router (`MAPPED_IPV4`).

This works through stateful firewalls, including IPv6 home routers, and
through a router forward. It does not work through two IPv4 NAT routers that
record the joiner's early packets as a connection to their own port, which
the common Linux-based ones do; ADR-0097 records the measurement. No
mailbox, account, directory, rendezvous server, or Synesis-operated service is
involved; the links move by any channel the users choose.

## Demo application boundary

After `PeerSession` is published and reciprocal `CONTROL_READY` has completed,
the demo may open one bounded application stream using
`synesis-demo-work/1`. It accepts only the fixed `describe-session` operation
and returns one UUID-correlated bounded result. This is a validation fixture,
not RPC, arbitrary method invocation, project synchronization, or production
Synesis cooperation. Application failures do not alter control or heartbeat
state; stream limits and cleanup are local bounds.

## Opaque application-stream boundary

SL-014 adds one transport-neutral `PeerSession` operation for a bounded opaque
byte exchange after reciprocal `CONTROL_READY`. The authenticated remote node
ID remains the Link session identity; `isUsable()` remains the readiness gate.
Link owns `SLA1` framing, the 512 KiB payload bound (4,096 bytes until compatibility 5), one-operation
five-second deadline, stream limits, and cleanup. Link never interprets,
authorizes, persists, retries, or orders the payload across streams. The seam
is intended for a future higher-level protocol and does not change the
demo-only `synesis-demo-work/1` contract.
