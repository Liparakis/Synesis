# Current Task

## Identity

- Task ID: SL-DEMO-001
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0036; latest checkpoint CP-0042
- Responsible agent: fresh coding agent
- Related decisions: ADR-0001, ADR-0002, ADR-0004, ADR-0005, ADR-0006, ADR-0007, ADR-0008

## Objective

Prepare the first physical Synesis Link cooperation demonstration without
implementing broad SL-009 reconnect/path behavior or deferred networking.

## Work completed

The Link implementation now lives in `link/` as the first Synesis Gradle
subproject. Root Gradle orchestration, command paths, ADR-0008, and durable
documentation have been updated; automated demo readiness remains complete.

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

Repeat the demo for Scenario B abrupt process loss and Scenario C wrong expected
identity; record only the results that actually occur.
