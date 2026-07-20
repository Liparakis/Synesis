# Tasks

Allowed statuses: `BLOCKED`, `READY`, `ACTIVE`, `VERIFYING`, `DONE`, `DEFERRED`.

## SL-SETUP-001

- ID: SL-SETUP-001
- Priority: P0
- Title: Install durable agent-memory structure
- Status: DONE
- Purpose: Install resumable execution state.
- Dependencies: none
- Acceptance criteria: Required files, startup, scripts, fixtures, resume, checkpoint, doctor, and documentation agreement.
- Required tests: persistence validator, resume, doctor, checkpoint.
- Required documentation: root and `docs/agent/`.
- Evidence: `docs/agent/checkpoints/CP-0001.md` through `CP-0004.md`; `STATE.md`.

## SL-SETUP-002

- ID: SL-SETUP-002
- Priority: P0
- Title: Install complete Synesis Link v1 contract
- Status: DONE
- Purpose: Replace the placeholder with the complete implementation contract.
- Dependencies: SL-SETUP-001
- Acceptance criteria: Contract revision 1 ACTIVE, durable files reconciled, architecture and product task graph created.
- Required tests: Resume and checkpoint validation; contract completeness review.
- Required documentation: contract, goal, state, task graph, architecture ADRs.
- Evidence: `CONTRACT.md` revision 1 ACTIVE; `docs/adr/0001-modular-monolith-and-boundaries.md`; `docs/adr/0002-quic-implementation.md`; CP-0005.

## SL-001

- ID: SL-001
- Priority: P0
- Title: Contract, architecture, and build
- Status: DONE
- Purpose: Create the smallest buildable Java 25 Gradle project with strict verification and a first passing test.
- Dependencies: SL-SETUP-001, SL-SETUP-002
- Acceptance criteria: Gradle project, wrapper, Java 25 toolchain, strict compiler/Javadoc/test configuration, dependency verification baseline, and first passing test.
- Required tests: first unit test, compile, test, Javadoc, dependency verification.
- Required documentation: README, build verification notes, package-info for every Java package.
- Evidence: PASS — `gradlew.bat clean check --dependency-verification=strict`; Gradle Wrapper; `SynesisLinkTest`.

## SL-002

- ID: SL-002
- Priority: P0
- Title: Node identity
- Status: DONE
- Purpose: Generate, load, store, sign, verify, and derive stable node IDs.
- Dependencies: SL-001
- Acceptance criteria: Contract identity requirements and mandatory identity tests pass.
- Required tests: identity generation, persistence, signing, verification, safe logging.
- Required documentation: identity ADR and API Javadocs.
- Evidence: PASS — `NodeIdentityTest`; `docs/adr/0003-ed25519-node-identity.md`; `gradlew.bat clean check --dependency-verification=strict`.

## SL-003

- ID: SL-003
- Priority: P0
- Title: Candidate descriptors
- Status: DONE
- Purpose: Model, canonicalize, sign, verify, expire, rank, and limit direct-connectivity candidates.
- Dependencies: SL-002
- Acceptance criteria: Descriptor and golden-vector requirements pass.
- Required tests: canonical equivalence, tamper, expiry, normalization, ranking, provider bounds.
- Required documentation: wire format and test vectors.
- Evidence: PASS — `CandidateDescriptorTest`; `docs/protocol/WIRE_FORMAT.md`; `gradlew.bat clean check --dependency-verification=strict`.

## SL-004

- ID: SL-004
- Priority: P0
- Title: First real local QUIC connection
- Status: DONE
- Purpose: Prove two local processes can establish and close a bounded QUIC connection.
- Dependencies: SL-001, SL-003
- Acceptance criteria: Internal adapter, listener, connector, ALPN, deterministic shutdown, and real local integration.
- Required tests: two-process local QUIC connection.
- Required documentation: transport ADR update and platform notes.
- Evidence: PASS — `NettyQuicLoopbackTest.connectsTwoSeparateJavaProcesses`; `gradlew.bat clean check --dependency-verification=strict`; ADR-0002.

## SL-005

- ID: SL-005
- Priority: P0
- Title: Identity binding and protocol negotiation
- Status: DONE
- Purpose: Authenticate the expected node and establish PeerSession only after negotiation.
- Dependencies: SL-002, SL-004
- Acceptance criteria: replay, substitution, downgrade, and incompatibility behavior is deterministic.
- Required tests: handshake and version tests.
- Required documentation: protocol and security updates.
- Evidence: PASS — repeated `NettyQuicLoopbackTest.connectsTwoSeparateJavaProcesses`; transported version offer/selection; `NettyQuicLoopbackTest.rejectsWrongIdentityAndIncompatibleVersionBeforeSessionExposure`; `SessionAuthenticatorTest`; strict clean check.

## SL-006

- ID: SL-006
- Priority: P0
- Title: Control path and graceful close
- Status: DONE
- Purpose: Add bounded control framing, goodbye, close reasons, and isolated progress.
- Dependencies: SL-005
- Acceptance criteria: control path remains live during data traffic and closes safely.
- Required tests: framing, limits, goodbye, malformed input.
- Required documentation: wire format and state-machine updates.
- Evidence: PASS — `ControlFrameTest`; local control-ready, duplicate-stream, large-stream isolation, and graceful-close integration; repeated process graceful close; `gradlew.bat clean check --dependency-verification=strict`.

## SL-007

- ID: SL-007
- Priority: P0
- Title: Heartbeat and liveness
- Status: DONE
- Purpose: Implement LIVE, SUSPECT, EXPIRED, recovery, cancellation, and exactly-once transitions.
- Dependencies: SL-006
- Acceptance criteria: bounded loss detection without false instant-disconnect claims.
- Required tests: deterministic liveness and fault tests.
- Required documentation: timing and liveness bounds.
- Evidence: PASS — `HeartbeatMessageTest`, `LivenessTrackerTest`, local QUIC heartbeat exchange, two-process healthy heartbeat exchange, strict clean verification, fixture validation, and doctor.

## SL-008

- ID: SL-008
- Priority: P1
- Title: Candidate providers and racing
- Status: DONE
- Purpose: Add justified providers, bounded racing, cancellation, cleanup, and diagnostics.
- Dependencies: SL-003, SL-004
- Acceptance criteria: failed providers do not block successful candidates; unsafe/duplicate candidates are normalized; compatible pairs rank deterministically; races are bounded and cancellable; only an authenticated control-ready expected-identity session wins; losers are cleaned up.
- Required tests: provider timeout/cancellation/race tests; local and two-process QUIC candidate-pair integration.
- Required documentation: candidate provider limits, protocol boundary, threat model, ADR-0006, and operations limitations.
- Evidence: PASS — `CandidateNormalizationTest`, `CandidateGathererTest`, `CandidateRacerTest`, local and two-process QUIC harnesses selecting through bounded candidate pairs, and `gradlew.bat clean check --dependency-verification=strict`.

## SL-009

- ID: SL-009
- Priority: P0
- Title: Reconnect and path behavior
- Status: DEFERRED
- Purpose: Create new authenticated sessions, epochs, stale rejection, and path-change reporting.
- Dependencies: SL-007, SL-008
- Acceptance criteria: old sessions and streams cannot affect a new session.
- Required tests: reconnect, migration, rebinding where supported.
- Required documentation: state-machine and operations updates.
- Evidence: Deferred after CP-0030; reconnect/path behavior is intentionally postponed until after the first physical cooperation demonstration. See `docs/agent/DEFERRED.md` entries SL-D-012 through SL-D-016.

## SL-ARCH-001

- ID: SL-ARCH-001
- Priority: P0
- Title: Move Link into the Synesis root module layout
- Status: DONE
- Purpose: Make the repository root Synesis and place the existing Link transport/session implementation in the `link/` Gradle subproject without inventing wider Synesis functionality.
- Dependencies: SL-001 through SL-DEMO-001 automated readiness
- Acceptance criteria: root `clean check` delegates to `:link:check`; Link source, tests, module build, and module lockfile live under `link/`; root docs/scripts remain runnable; strict dependency verification passes; no placeholder sibling modules are added.
- Required tests: root strict clean check, root resume/doctor/fixture/deferred validators, and Link CLI help.
- Required documentation: ADR-0008, architecture baseline, contract, goal, state, current task, next session, README, and command-path updates.
- Evidence: PASS — root `projects` discovery, root `clean check --dependency-verification=strict`, `:link:demoCli --args=--help`, resume, doctor, fixture validator, and deferred validator after migration; ADR-0008.

## SL-DEMO-001

- ID: SL-DEMO-001
- Priority: P0
- Title: First physical cooperation demonstration readiness
- Status: VERIFYING
- Purpose: Add only the bounded demo application request/result path, reproducible safe CLI operation, durable deferral enforcement, and physical-validation evidence capture required to demonstrate authenticated cooperative behavior.
- Dependencies: SL-007, SL-008
- Acceptance criteria: deferred register is validated; one bounded authenticated `synesis-demo-work/1` request/result succeeds locally and in two processes; safe CLI/demo instructions exist; physical two-machine evidence is either recorded as `TWO_MACHINE_VERIFIED` or the task remains blocked without overstated claims; no deferred networking or higher-level Synesis semantics are implemented.
- Required tests: demo codec bounds/correlation, pre-auth/pre-ready rejection, local QUIC request/result, two-process request/result, wrong-identity rejection, cleanup, and durable-register fixtures.
- Required documentation: `DEFERRED.md`, `DEMO_GAP_ANALYSIS.md`, `FIRST_DEMO.md`, operations/security updates, release-readiness notes, and dependency-verification metadata for main and test classpaths.
- Evidence: automated PASS — deferred validator, `DemoWorkProtocolTest`, `DemoWorkBindingTest`, local/two-process request/result, `DemoCliTest`, CLI help, and strict clean check. Physical Scenario A normal operation is `TWO_MACHINE_VERIFIED` in `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`; abrupt-loss and wrong-identity physical validation remain pending.

## SL-012

- ID: SL-012
- Priority: P0
- Title: Zero-configuration terminal onboarding
- Status: ACTIVE
- Purpose: Add automatic local identity bootstrap and signed single-use terminal invitations above the existing Link transport while preserving the diagnostic `DemoCli` path.
- Dependencies: SL-005, SL-006, SL-007, SL-008
- Acceptance criteria: `host` binds before invitation creation; `join <link>` verifies a bounded signed invitation; identity creation/reuse is automatic; capability admission is single-use with bounded reservation; existing mutual identity binding remains mandatory; control readiness, liveness, demo work, and graceful close complete through the existing path; QR and link encode the same invitation; no physical two-machine claim is made until executed.
- Required tests: identity bootstrap/reuse/corruption, invitation canonical encoding/signature/tamper/expiry/size/version/missing fields, reservation timeout/replay/concurrency, expected-peer mismatch, QR input identity, two-profile two-process onboarding, and all existing Link/DemoCli tests.
- Required documentation: onboarding protocol/wire format, threat model, CLI operations, ADR for invitation/transcript changes, test matrix, state, and checkpoint evidence.
- Evidence: implementation compiled; focused invitation/bootstrap/admission/QR
  tests PASS; strict full verification and two-profile two-process onboarding
  PASS, including the cleanup narrow-terminal rerun and unsupported-output
  charset QR skip test. Invitation bytes,
  handshake semantics, identity behavior, admission/replay semantics, and
  physical claim boundaries are unchanged. Physical two-machine onboarding is
  not claimed.

## SL-010

- ID: SL-010
- Priority: P0
- Title: Hardening
- Status: READY
- Purpose: Complete limits, malformed-input handling, fault injection, leak checks, and threat reconciliation.
- Dependencies: SL-009
- Acceptance criteria: mandatory security, resource, and lifecycle tests pass.
- Required tests: fuzz/property, saturation, repeated-cycle, leak tests.
- Required documentation: threat model reconciled with implementation.
- Evidence: pending.

## Deferred capability register

Deliberately postponed, unsupported, partially verified, and physically
unverified capabilities are tracked in
[`DEFERRED.md`](DEFERRED.md).

Deferred entries are not committed implementation tasks and are not release
promises. A deferred capability enters the task graph only after its activation
trigger is satisfied, required evidence or research exists, the item is
explicitly promoted, a concrete task with acceptance criteria is created, and
exactly one task is made `ACTIVE`. Keep the register entry until the promoted
task replaces it; then mark it `SUPERSEDED` with the task and completion
checkpoint. Use `CANCELLED` only for a deliberate permanent scope decision.

## SL-011

- ID: SL-011
- Priority: P0
- Title: CLI and release verification
- Status: READY
- Purpose: Deliver the public-API-only two-peer CLI and release evidence.
- Dependencies: SL-010
- Acceptance criteria: clean build, CLI workflow, generated Javadocs, protocol docs, vectors, release notes/checklist, and two-machine evidence.
- Required tests: CLI and two-machine tests.
- Required documentation: operations guide, release notes, release checklist.
- Evidence: pending.
