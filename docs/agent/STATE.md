# State

## Repository state

Contract revision 1 is ACTIVE. SL-005 through SL-008 are complete;
SL-DEMO-001 is the only ACTIVE task and SL-009 is deferred. The repository
remains a standalone Synesis Link project with no wider Synesis code and no Git
metadata.

## Implementation state

The project contains bounded identity, signed candidate descriptors, direct
candidate providers/racing, authenticated QUIC sessions, control readiness,
graceful close, heartbeat/liveness, and the demo-only authenticated
`synesis-demo-work/1` request/result stream. The source CLI supports identity
creation and explicit server/client validation modes. It is not a production
management CLI or application protocol.

## Verification state

- Strict clean verification: PASS.
- Source/tooling artifact verification metadata: PASS; twenty-three exact SHA-256 entries cover main, test, Gradle, and Kotlin resolution.
- Deferred register validation: PASS, 27 entries.
- Local and two-process demo request/result: PASS.
- CLI help and identity creation: PASS.
- Resume, doctor, and fixture validation: PASS.
- Physical two-machine evidence: NOT CLAIMED.

## Honest limitations

NAT traversal, router mappings, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, physical path migration, reconnect,
resumption, physical IPv6/public IPv4, temporary application-silence recovery,
all-firewall operation, packaging, GUI, and production Synesis semantics are
not claimed. Abrupt child-process loss remains classified only as the documented
transport-failure or liveness-expiry category.
