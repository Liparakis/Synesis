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
candidates. The default policy allows private IPv4 and global IPv6, rejects
loopback, multicast, unspecified, ambiguous link-local, and relay candidates,
and does not claim router discovery or public reachability. PCP, NAT-PMP, UPnP,
STUN, TURN, relays, hole punching, and DHT discovery are unsupported here.

Candidates are resolved and normalized before use; mapped IPv6 IPv4 addresses
become canonical IPv4, unsafe scopes are rejected, and duplicates retain the
best priority. Only same-family non-relay pairs are generated. Pair ranking and
provider/race limits are deterministic and bounded.

The race factory is a transport boundary: it may complete successfully only
after the existing authenticated handshake has verified the expected node
identity and reciprocal control readiness. A transport connection alone is not
a winner. The first valid winner atomically cancels other attempts; any late
successful loser is closed with a local-request reason. This layer does not
implement reconnection, path migration, or session revival.

## Demo application boundary

After `PeerSession` is published and reciprocal `CONTROL_READY` has completed,
the demo may open one bounded application stream using
`synesis-demo-work/1`. It accepts only the fixed `describe-session` operation
and returns one UUID-correlated bounded result. This is a validation fixture,
not RPC, arbitrary method invocation, project synchronization, or production
Synesis cooperation. Application failures do not alter control or heartbeat
state; stream limits and cleanup are local bounds.
