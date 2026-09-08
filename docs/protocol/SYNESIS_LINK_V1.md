# Synesis Link v1 Protocol

The ALPN is `synesis-link/1`. Version negotiation, identity binding, bounded
control framing, application liveness, reconnect epochs, and typed streams are
implemented incrementally. No project, task, agent, ownership, or
synchronization semantics belong here.

## Authenticated establishment

Each endpoint sends a bounded supported-version list in the first SLH1
envelope. The selected highest exact intersection is in the signed transcript,
which binds ALPN, both node identities and public keys, both nonces, session ID,
and both epochs. Expected remote identity is checked before publishing
`PeerSession`; wrong identities fail as `IDENTITY_MISMATCH`.

## Control-ready liveness

After reciprocal `CONTROL_READY`, the session becomes `LIVE` and owns one
bounded one-shot heartbeat schedule. Defaults are one-second HEARTBEAT, three
seconds to `SUSPECT`, and five seconds to `EXPIRED`. They are local `Duration`
defaults, not wire constants.

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

Human-mediated onboarding uses two copyable links and no Synesis signaling
service. The host command creates `synesis://join/SLO1-...`, which contains
the existing signed `SYN1` invitation plus the host-signed traversal offer.
The join command verifies SLO1, gathers candidates on its already-bound UDP
endpoint, and emits `synesis://answer/SLA2-...`. SLA2 contains the exact SLO1
offer digest, the joiner's signed descriptor, and the answer signature. The
host remains alive with its UDP/QUIC endpoint and imports one SLA2 from stdin;
a valid import immediately enables the existing direct race. The two links
may be moved by any user-controlled channel (chat, email, QR, or removable
media). No mailbox, account, directory, rendezvous server, or Synesis-
operated service is involved. Handles and the invitation capability are
single-use, and the links expire with the existing ten-minute invitation
lifetime.

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
Link owns `SLA1` framing, the 4,096-byte payload bound, one-operation
five-second deadline, stream limits, and cleanup. Link never interprets,
authorizes, persists, retries, or orders the payload across streams. The seam
is intended for a future higher-level protocol and does not change the
demo-only `synesis-demo-work/1` contract.
