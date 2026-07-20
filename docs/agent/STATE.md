# State

## Repository state

Contract revision 1 is ACTIVE. SL-005 through SL-008 are complete;
SL-012 is VERIFYING, SL-013 is DONE and frozen at CP-0054, and SL-014 is the
only ACTIVE task; SL-DEMO-001 is VERIFYING; SL-ARCH-001 is complete and SL-009
is deferred. The repository is a Synesis root with Link as the first
implemented transport/session subproject.

## Implementation state

The project contains bounded identity, automatic identity bootstrap, signed
candidate descriptors and single-use invitations, direct candidate
providers/racing, authenticated QUIC sessions, control readiness, graceful
close, heartbeat/liveness, compact terminal QR rendering, and the demo-only authenticated
`synesis-demo-work/1` request/result stream. `DemoCli` remains the diagnostic
fallback. The standalone `cli` module owns Picocli, terminal rendering, exit
mapping, doctor, QR rendering, and Gradle Application distributions; Link owns
onboarding through the typed `Onboarding` façade. Production installation
remains out of scope.

## Planning boundary

The CAF Phase 1 map and first signed-decision-record proposal are recorded in
`docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md` and ADR-0011. ADR-0011 is
approved only for the SL-014 Link seam prerequisite; SYN-001 remains blocked.
No record storage, sync, second transport, shared-Markdown authority, project
behavior, or CLI change is allowed in SL-014.

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
- Compact QR output skips safely when the process output charset cannot encode
  the Unicode glyphs; the copyable share link is always retained.
- Resume, doctor, and fixture validation: PASS.
- Physical two-machine diagnostic `DemoCli` Scenario A normal operation:
  `TWO_MACHINE_VERIFIED`; evidence is in
  `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`. Physical source-run onboarding
  remains unverified and is not covered by that evidence.
- Physical abrupt-loss and wrong-identity scenarios: NOT CLAIMED.
- Physical zero-configuration onboarding: NOT CLAIMED; this remains an SL-013
  completion gate.
- Generated launcher two-profile onboarding: PASS; evidence is
  `docs/evidence/CLI-DISTRIBUTION-VALIDATION.md`.

## Honest limitations

NAT traversal, router mappings, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, physical path migration, reconnect,
resumption, physical IPv6/public IPv4, temporary application-silence recovery,
all-firewall operation, production installation/signing, GUI, and production
Synesis semantics are not claimed. Abrupt child-process loss remains classified
only as the documented transport-failure or liveness-expiry category.
