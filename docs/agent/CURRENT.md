# Current Task

## Identity

- Task ID: SYN-002
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0073; latest checkpoint CP-0075
- Responsible agent: fresh coding agent
- Related decisions: ADR-0001, ADR-0002, ADR-0004, ADR-0005, ADR-0006, ADR-0007, ADR-0008, ADR-0009, ADR-0010, ADR-0011, ADR-0012, ADR-0013

## Objective

Implement only the bounded read-only searchable project view over existing
verified decision heads. Keep Link and the CP-R4 protocol frozen; do not add
production networking, new wire messages, background sync, reconnect,
discovery, membership, retries, workers, leases, autonomy, federation,
Obsidian, physical claims, or `:cli` changes.

## Work completed

SL-013 is complete and frozen at CP-0054 as the standalone
Picocli/Application distribution baseline. Physical generated-launcher
onboarding remains unclaimed by explicit scope decision.

ADR-0011's required prerequisite is complete as SL-014. SL-015 is DONE and
SYN-001 is closed as DONE at CP-R4. CP-R5 physical validation is deferred;
SYN-002 implementation is complete and remains active only for review/closure;
it adds a minimal searchable view inside `:project-record`.

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
- CP-R4 project configuration, bounded message codec, authenticated one-shot
  publish/sync, deterministic outcomes, quarantine, and isolated two-profile
  process evidence: PASS; evidence is `docs/evidence/DECISION-RECORD-CP-R4-2026-07-21.md`.
- SYN-002 planning comparison of a second record type against a read-only
  searchable decision view: recorded in ADR-0013 and the phase map.
- SYN-002 read-only view: `DecisionSearch` scans only fully validated current
  heads with bounded queries, deterministic rendering, and safe corruption
  errors; evidence is `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`.
- `gradlew.bat clean check --dependency-verification=strict`: PASS after
  SYN-002 implementation; Link and CLI regressions remain green.

## Current failures

- Physical diagnostic `DemoCli` Scenario A normal operation is verified on two
  computers; physical generated-launcher onboarding is not verified.
- Physical generated-launcher abrupt-loss and wrong-identity scenarios remain
  unverified and must not be claimed.
- No CP-R4 verification failures remain. Full strict verification and the
  isolated two-profile process scenarios pass.

## Known limitations

NAT traversal, PCP, NAT-PMP, UPnP, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, path migration, reconnect, session
resumption, physical IPv6/public IPv4, all-firewall operation, packaging, GUI,
and production Synesis cooperation remain deferred or unverified.

## Immediate next action

Review the committed SYN-002 view and close the task after confirming its
evidence; do not begin a new CAF slice.
