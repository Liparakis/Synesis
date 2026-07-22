# State

## Repository state

Contract revision 1 is ACTIVE. SL-005 through SL-008 are complete;
SL-012 is VERIFYING, SL-013 is DONE and frozen at CP-0054, SL-014 and SL-015
are DONE, SYN-002 is DONE at CP-0075, SYN-003 is DONE at CP-W3, SYN-009B is
  DONE at CP-0102, SYN-009B.1 is VERIFYING, SYN-009C is DONE at CP-0110, and
  SYN-010A is the only ACTIVE task at CP-0123;
SYN-001 is DONE at
CP-R4 and CP-R5 is deferred; SL-DEMO-001 is VERIFYING; SL-ARCH-001 is
complete and SL-009 is deferred. The repository is a Synesis root with Link as the first
implemented transport/session subproject. Public GitHub preview preparation has
an authorized AGPL-3.0-only license decision recorded; publication remains
unperformed pending authorization and remaining review gates.

## Implementation state

The project contains bounded identity, automatic identity bootstrap, signed
candidate descriptors and single-use invitations, direct candidate
providers/racing, authenticated QUIC sessions, control readiness, graceful
close, heartbeat/liveness, compact terminal QR rendering, and the demo-only authenticated
`synesis-demo-work/1` request/result stream. `DemoCli` remains the diagnostic
fallback. The standalone `cli` module owns Picocli, terminal rendering, exit
mapping, doctor, QR rendering, and Gradle Application distributions; Link owns
onboarding through the typed `Onboarding` façade. `:project-record` now owns
the CP-R2 canonical signed decision model, immutable local store, recovery, and
JDK-only inspection launcher. CP-R4 adds only configured peer authorization,
bounded one-shot messages, authenticated validation, deterministic outcomes,
and quarantine. Production installation remains out of scope.

## Planning boundary

The CAF Phase 1 map and first signed-decision-record proposal are recorded in
`docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`, ADR-0011, and ADR-0013.
ADR-0011 is accepted and SYN-001 is DONE at CP-R4. CP-R5 is deferred. SYN-002
is DONE at CP-0075. ADR-0014 selects SYN-003 CP-W1/CP-W2 as a thin workspace
bootstrap and one-shot sync layer; no background sync,
reconnect, discovery, membership, retries, second transport, shared-Markdown
authority, extra record types, physical two-machine claim, or CLI change is
allowed.

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
- Physical zero-configuration onboarding: NOT CLAIMED; it is outside the frozen
  SL-013 development-distribution baseline.
- Generated launcher two-profile onboarding: PASS; evidence is
  `docs/evidence/CLI-DISTRIBUTION-VALIDATION.md`.
- CP-R2 local decision record encoding, signing, storage, recovery, and
  inspection: PASS; evidence is
  `docs/evidence/DECISION-RECORD-CP-R2-2026-07-21.md`.
- CP-R4 configured peer authorization, bounded messages, one-shot publish/sync,
  authenticated owner/signature/project/predecessor checks, deterministic
  outcomes, conflict quarantine, and two-profile process scenarios: PASS;
  evidence is `docs/evidence/DECISION-RECORD-CP-R4-2026-07-21.md`.
- SYN-001 closure and SYN-002 planning comparison: PASS as documentation-only
  state; ADR-0013 defines the searchable-view boundary.
- SYN-002 implementation: PASS; `DecisionSearch` and validated-head snapshot
  remain local, on-demand, bounded, and non-mutating.
- SYN-002 closure review: PASS at CP-0075; evidence is
  `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`.
- SYN-003 planning: ADR-0014, first-demo script, task promotion, and CP-0076
  are recorded; CP-W1, CP-W2, and CP-W3 implementations are verified.
- SYN-003 CP-W1/CP-W2/CP-W3 implementation: PASS; `:workspace` provides bounded
  profile bootstrap, one-peer project creation, signed revision-1 decision
  creation, one-shot authenticated host/join sync, decision search, and single-record
  inspection. Evidence is in `docs/evidence/WORKSPACE-CP-W1-2026-07-21.md`,
  `docs/evidence/WORKSPACE-CP-W2-2026-07-21.md`, and
  `docs/evidence/WORKSPACE-CP-W3-2026-07-21.md`.
- SYN-PRODUCT-REVIEW: PASS; completed the product review and roadmap planning through CP-0079. The review is recorded in `docs/agent/PRODUCT_REVIEW.md`.
- SYN-004 planning: PASS; completed the minimal guided workspace demo flow design and task promotion under CP-0081. Detailed design is in `docs/agent/SYN_004_DESIGN.md`.
- SYN-009A implementation: PASS; unreleased `WorkspaceOperations`, `DecisionRecordCli`, and legacy process harnesses were removed, synchronization orchestration now belongs to `SyncApplicationService`, and the unified `synesis` launcher is the only supported command entry point. Strict clean verification passed after the removal.
- SYN-009B closure: PASS; obsolete CLI compatibility tests remain deleted, valid
  process/protocol scenarios were rewritten in `UnifiedCliSyncProcessTest`
  against the generated launcher, malformed invitations map to
  `INVITE_INVALID`, and provider lifecycle evidence includes unrelated hook
    configuration preservation. Closed at CP-0102. SYN-009C is READY with no
    implementation started.

- SYN-009B.1 promotion: PASS as a documentation/architecture gate; the
    installed Codex baseline is `codex-cli 0.140.0`, the official hook/config
    boundary and trust limitation are recorded, and no SYN-009C implementation
    has started. Parser, adapter, lifecycle, fixture, and real-agent evidence
  remain pending.

- SYN-009B.1 implementation slice: PASS for bounded parser/adapter behavior,
  provider lifecycle, Windows launcher command shape, generated process
  coverage, and 20-call synthetic latency. Codex remains
  `EXPERIMENTAL`/`DEGRADED` with `TRUST_STATUS=REVIEW_REQUIRED`; the real
  `/hooks` trust review and qualifying denial/re-plan/hash evidence are not
  complete. See `docs/validation/codex-real-agent-experiment.md`.

- SYN-009C promotion: PASS as a user-authorized task-state change. The
  distribution architecture is EVOLUTION; implementation and release evidence
  are pending. Codex remains EXPERIMENTAL/DEGRADED/REVIEW_REQUIRED.

- SYN-009C.1 implementation: PASS. `synesis version`, embedded build metadata,
  `jlink` runtime, relative launcher, ZIP/tar.gz archives, and clean-room
  Windows provider/init/doctor smoke all pass. The bootstrapper source, signed
  manifest helper, safe extraction, version validation, and six-platform CI
  matrix are present. SYN-009C.2 verification now passes: `gofmt`, `go test
  ./...`, `go vet ./...`, native Windows subprocess install/update/doctor/
  uninstall, all six cross-compiles, clean-room Java `clean check
  --dependency-verification=strict`, and platform archive generation. SYN-009C.3
  final gate: Java full build, Go full test, CI matrix definition, native smoke
  where available, bootstrap native smoke, manifest aggregation, test-key
  signature verification, release documentation, and clean working tree all
  PASS at CP-0110: archive extraction smoke is cross-platform, bundled
  launchers set their own launcher path, CI verifies the sidecar actually
  produced by signing, and the real Windows archive bootstrap trial passes.
  Production key replacement and OS vendor signing remain deferred
  release-hardening work.

## Honest limitations

NAT traversal, router mappings, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, physical path migration, reconnect,
resumption, physical IPv6/public IPv4, temporary application-silence recovery,
all-firewall operation, production installation/signing, GUI, and production
Synesis semantics are not claimed. Abrupt child-process loss remains classified
only as the documented transport-failure or liveness-expiry category.
