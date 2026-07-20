# Current Task

## Identity

- Task ID: SL-012
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0036; latest checkpoint CP-0048
- Responsible agent: fresh coding agent
- Related decisions: ADR-0001, ADR-0002, ADR-0004, ADR-0005, ADR-0006, ADR-0007, ADR-0008, ADR-0009

## Objective

Implement zero-configuration terminal onboarding above the existing Link
transport without changing the diagnostic DemoCli path or implementing SL-009.

## Work completed

Implemented the bounded onboarding orchestrator above the existing transport:
automatic identity reuse/creation, listener-first candidate and invitation
creation, signed capability admission with 15-second reservation expiry,
ephemeral transport TLS, terminal share link plus compact Unicode QR, and `host`,
`join`, and `identity show` commands. The cleanup renamed the QR renderer,
added width-aware skipping, and removed dead onboarding code without changing
invitation bytes or handshake/admission semantics. `DemoCli` remains unchanged
as a diagnostic fallback. Identity regeneration is deferred.

## Verification

- Root `gradlew.bat projects --dependency-verification=strict`: PASS; root and `:link` discovered.
- Root `gradlew.bat clean check --dependency-verification=strict`: PASS; `:link:check` and all 40 tests executed.
- Package-info files and `packageInfoCheck`: intentionally removed at the user's request; the strict build now verifies compilation, Javadocs, formatting, static analysis, and tests without that gate.
- Root `gradlew.bat :link:demoCli --args=--help --dependency-verification=strict`: PASS.
- Candidate provider regression check: PASS; skipping down interfaces restores live candidates (`10` on this host).
- Targeted `CandidateGathererTest` and `CandidateNormalizationTest`: PASS.
- Resume, doctor, fixture, and deferred validators: PASS.
- `scripts/agent-validate-deferred.ps1`: PASS; 27 entries.
- `scripts/agent-validate-fixtures.ps1`: PASS.
- `scripts/agent-doctor.ps1`: PASS.
- `scripts/agent-resume.ps1`: PASS.
- Focused invitation/bootstrap/admission/QR tests: PASS.
- Two-profile two-process onboarding with control, liveness, work exchange,
  and close: PASS; evidence is `docs/evidence/ONBOARDING-PROCESS-2026-07-20.md`;
  physical two-machine onboarding remains unverified.
- `gradlew.bat clean check --dependency-verification=strict` after the final
  admission/failure-state changes: PASS.
- Cleanup two-profile rerun with forced narrow terminal: PASS; evidence is
  `docs/evidence/ONBOARDING-PROCESS-2026-07-20.md` and physical onboarding
  remains unclaimed.

## Current failures

- Physical Scenario A normal operation is verified on two computers.
- Physical abrupt-loss and wrong-identity scenarios remain unverified and must
  not be claimed.

## Known limitations

NAT traversal, PCP, NAT-PMP, UPnP, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, path migration, reconnect, session
resumption, physical IPv6/public IPv4, all-firewall operation, packaging, GUI,
and production Synesis cooperation remain deferred or unverified.

## Immediate next action

Perform the documented two-machine onboarding validation when two physical
computers are available; until then preserve the explicit no-physical-claim
boundary.
