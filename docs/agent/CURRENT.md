# Current Task

## Identity

- Task ID: SYN-001
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0065; latest checkpoint CP-0067
- Responsible agent: fresh coding agent
- Related decisions: ADR-0001, ADR-0002, ADR-0004, ADR-0005, ADR-0006, ADR-0007, ADR-0008, ADR-0009, ADR-0010, ADR-0011, ADR-0012

## Objective

Implement CP-R2 only: one canonical signed `decision` record v1, deterministic
encoding/digest/signature/bounds, immutable per-profile revision storage with
atomic heads, restart recovery, and a JDK-only inspection launcher owned by
`:project-record`. Do not implement networking, sync, extra record types,
background behavior, or `:cli` changes.

## Work completed

SL-013 is complete and frozen at CP-0054 as the standalone
Picocli/Application distribution baseline. Physical generated-launcher
onboarding remains unclaimed by explicit scope decision.

ADR-0011's required prerequisite is complete as SL-014. The user approved
promotion of SYN-001; CP-R2 is the only active implementation scope. CP-R4
networking and synchronization remain deferred.

## Verification

- Root `gradlew.bat projects --dependency-verification=strict`: PASS; root and `:link` discovered.
- Root `gradlew.bat clean check --dependency-verification=strict`: PASS; `:link:check` and `:cli:check` executed, including the SL-014 seam tests.
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
- Unicode-capability renderer test: PASS; legacy output charsets now produce
  `QR_SKIPPED=UNICODE_UNSUPPORTED` instead of corrupted glyphs.
- `:cli:installDist --dependency-verification=strict`: PASS; both generated
  launchers exist.
- Generated launcher `--help`, `--version`, `identity show`, and `doctor`: PASS;
  all exit `0`.
- Generated launcher two-profile host/join process test: PASS; exact invitation
  handoff, control readiness, liveness, work result, cleanup, and zero exits
  are asserted without logging the full link in failures.
- `:link:dependencies` contains no Picocli: PASS.
- Root `clean check --dependency-verification=strict`: PASS for `:link` and
  `:cli`.
- Generated launcher doctor/failure checks: valid profile exit `0`, corrupt
  identity exit `10`, invalid invitation exit `11`; all outputs were sanitized.
- Generated launcher onboarding rerun after graceful close: PASS as a fresh
  new session. Transparent reconnect remains deferred.
- Link-level abrupt-loss and wrong-identity tests: PASS. A generated launcher
  early-kill attempt did not reach a bounded terminal status and is not claimed
  as generated abrupt-loss evidence.

## Current failures

- Physical diagnostic `DemoCli` Scenario A normal operation is verified on two
  computers; physical generated-launcher onboarding is not verified.
- Physical generated-launcher abrupt-loss and wrong-identity scenarios remain
  unverified and must not be claimed.
- No SL-014 verification failures remain. CP-R2 focused
  `:project-record:check` and full root strict verification pass;
  evidence is recorded in `docs/evidence/DECISION-RECORD-CP-R2-2026-07-21.md`.

## Known limitations

NAT traversal, PCP, NAT-PMP, UPnP, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, path migration, reconnect, session
resumption, physical IPv6/public IPv4, all-firewall operation, packaging, GUI,
and production Synesis cooperation remain deferred or unverified.

## Immediate next action

Keep CP-R2 complete and do not begin CP-R4 networking or sync until explicitly reviewed.
