# State

## Repository state

Contract revision 1 is ACTIVE. SL-005 through SL-008 are complete;
SL-012 is the only ACTIVE task; SL-DEMO-001 is VERIFYING; SL-ARCH-001 is complete and SL-009 is
deferred. The repository is a Synesis root with Link as the first implemented
transport/session subproject.

## Implementation state

The project contains bounded identity, automatic identity bootstrap, signed
candidate descriptors and single-use invitations, direct candidate
providers/racing, authenticated QUIC sessions, control readiness, graceful
close, heartbeat/liveness, compact terminal QR rendering, and the demo-only authenticated
`synesis-demo-work/1` request/result stream. `DemoCli` remains the diagnostic
fallback; `SynesisCli` is the source-run onboarding path. Neither is a
packaged production management CLI.

## Verification state

- Strict clean verification after removing the package-info gate: PASS.
- Package-info files and the package-info verification task were intentionally removed at the user's request; strict Javadocs for public/protected API elements remain enabled.
- Latest candidate-provider fix: targeted PASS; full strict check PASS.
- Source/tooling artifact verification metadata: PASS; twenty-three exact SHA-256 entries cover main, test, Gradle, and Kotlin resolution.
- Deferred register validation: PASS, 27 entries.
- Local and two-process demo request/result: PASS.
- CLI help and identity creation: PASS.
- Invitation, identity-bootstrap, admission, QR, and strict Javadoc checks: PASS.
- Two-profile two-process host/join onboarding, including work exchange and
  graceful close: PASS; this is not physical two-machine evidence.
- Resume, doctor, and fixture validation: PASS.
- Physical two-machine Scenario A normal operation: `TWO_MACHINE_VERIFIED`;
  evidence is in `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`.
- Physical abrupt-loss and wrong-identity scenarios: NOT CLAIMED.
- Physical zero-configuration onboarding: NOT CLAIMED.

## Honest limitations

NAT traversal, router mappings, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, physical path migration, reconnect,
resumption, physical IPv6/public IPv4, temporary application-silence recovery,
all-firewall operation, packaging, GUI, and production Synesis semantics are
not claimed. Abrupt child-process loss remains classified only as the documented
transport-failure or liveness-expiry category.
