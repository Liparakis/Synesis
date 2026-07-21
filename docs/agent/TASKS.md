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
- Status: VERIFYING
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

## SL-013

- ID: SL-013
- Priority: P0
- Title: Standalone Synesis CLI and development distribution
- Status: DONE
- Purpose: Move terminal command ownership and Gradle development distribution
  into an outer `cli` module while Link retains onboarding/network orchestration
  behind one minimal public façade.
- Dependencies: SL-012 implementation and documented onboarding validation.
- Acceptance criteria: `:cli` owns Picocli, command adapters, terminal output,
  exit mapping, and Gradle Application distributions; `link` has no Picocli or
  CLI dependency; Link exposes only the typed onboarding façade; host, join,
  identity show, and doctor work through generated launchers with stable labels
  and numeric exits; QR rendering remains byte-identical to the exact
  invitation link; strict verification and isolated launcher onboarding pass;
  physical launcher onboarding remains explicitly unclaimed and is outside
  this frozen development-distribution baseline.
- Required tests: CLI parsing, command adapters, read-only readiness inspection,
  generated launcher smoke tests, local generated host/join, and existing Link
  protocol tests. Physical generated-launcher evidence is explicitly unclaimed
  by the frozen baseline.
- Required documentation: ADR-0010, distribution and physical CLI evidence,
  README/demo/operations command updates, and durable state reconciliation.
- Evidence: `:cli:installDist`, strict root check, generated launcher smoke,
  generated two-profile onboarding, façade tests, parsing tests, doctor tests,
  and `:link:dependencies` Picocli boundary PASS. Frozen at CP-0054; physical
  launcher evidence remains unclaimed and is not a completion claim.

## SL-014

- ID: SL-014
- Priority: P0
- Title: Bounded authenticated Link application-stream seam
- Status: DONE
- Purpose: Expose one transport-neutral, bounded application-stream binding
  above Link so a future higher-level module can exchange bytes only after an
  authenticated control-ready session exists.
- Dependencies: SL-013 frozen completion; ADR-0011 approval; ADR-0012.
- Acceptance criteria: the Link API exposes authenticated remote identity and
  readiness; pre-ready, over-limit, terminal-session, and cleanup behavior is
  deterministic; two isolated processes exchange bounded bytes over the
  authenticated stream; Link retains framing, limits, deadlines, liveness, and
  cleanup ownership; no project/record/sync vocabulary or `:cli` dependency is
  introduced.
- Required tests: pre-ready rejection, frame-size/bounds rejection, terminal
  session rejection, stream cleanup on success/failure/cancellation, and
  two-profile byte exchange with remote identity assertions.
- Required documentation: ADR-0012, protocol/state/security boundary notes,
  test matrix, evidence, and checkpoint state.
- Evidence: `docs/evidence/APPLICATION-STREAM-SEAM-2026-07-21.md`; focused
  seam tests and `gradlew.bat clean check --dependency-verification=strict`
  PASS.

## SL-015

- ID: SL-015
- Priority: P0
- Title: Review SYN-001 activation after Link seam verification
- Status: DONE
- Purpose: Review the verified SL-014 boundary and decide whether the blocked
  higher-level record task may be explicitly promoted.
- Dependencies: SL-014 DONE; SL-015 review gate completed; CP-R2 active.
- Acceptance criteria: SL-014 evidence and ADR-0012 were reviewed; the user
  explicitly approved promotion of SYN-001; no record storage, sync, project
  terminology, or `:cli` change occurred in this gate.
- Required tests: resume, fixture, deferred-register, and doctor validators.
- Required documentation: review decision, task-state reconciliation, and a
  checkpoint recording the choice.
- Evidence: user approval recorded in `SESSION_LOG.md`; CP-R2 is the active
  SYN-001 implementation checkpoint.

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

## SYN-001

- ID: SYN-001
- Priority: P0
- Title: First signed shared decision-record proof
- Status: DONE
- Purpose: Prove that two isolated configured profiles can authenticate,
  publish, persist, inspect, and synchronize exactly one signed decision
  record above Link, while detecting duplicates, conflicts, and stale state.
- Dependencies: frozen SL-013/CP-0054 baseline; approved ADR-0011; and
  separately verified SL-014 transport-neutral bounded Link application-stream
  seam. The existing fixed demo stream is not a substitute.
- Acceptance criteria: the complete criteria in
  `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md` are met, including
  stable identity/version/provenance/owner/status/evidence, authenticated
  transfer, deterministic duplicate/conflict/staleness results, durable local
  revision storage, and readable safe inspection.
- Required tests: canonical record/signature/store/conflict tests; Link-seam
  authentication/bounds/cleanup tests; isolated two-profile publish/sync test;
  CLI-inspection test; unchanged Link/CLI regressions.
- Required documentation: accepted ADR-0011, record protocol/storage/threat
  documentation, test matrix, deferred reconciliation, and sanitized evidence.
- Scope completed: CP-R4 project configuration and explicit peer allowlists,
  bounded SYNC_REQUEST/RECORD/RESULT/ERROR messages, one-shot authenticated
  publish/sync, deterministic duplicate/stale/conflict/rejected/applied/
  unknown outcomes, and valid divergent-record quarantine. No background
  behavior, retries, discovery, membership, extra record types, physical
  claims, or `:cli` changes.
- Evidence: `docs/evidence/DECISION-RECORD-CP-R4-2026-07-21.md`; focused
  CP-R4 tests and full strict root verification PASS. Closed at CP-R4.
- CP-R5 physical two-profile record transfer is explicitly deferred; see
  `SL-D-028`.

## SYN-001-CP-R5

- ID: SYN-001-CP-R5
- Priority: P1
- Title: Physical two-profile decision-record transfer claim
- Status: DEFERRED
- Purpose: Validate the existing CP-R4 one-shot decision exchange across two
  physical machines only if a real two-machine claim becomes necessary.
- Dependencies: SYN-001 CP-R4 DONE; explicit operator demand for a physical
  record-transfer claim; network/security evidence.
- Acceptance criteria: two physical profiles complete the existing CP-R4
  scenarios with sanitized evidence, or the claim remains explicitly absent.
- Required tests: physical initial publish, duplicate retry, successor,
  stale, conflict, and cleanup; no protocol expansion is implied.
- Required documentation: `SL-D-027`, physical evidence, threat review, and
  checkpoint state.
- Evidence: deferred; no physical record-transfer claim is made.

## SYN-002

- ID: SYN-002
- Priority: P1
- Title: Minimal searchable project view over signed decisions
- Status: DONE
- Purpose: Define and, only after review, expose a bounded read-only view of
  existing verified decision heads so a human can find and compare shared
  project truth without adding another record type or protocol.
- Dependencies: SYN-001 CP-R4 DONE; frozen Link seam; frozen
  `:project-record` storage and signature rules.
- Acceptance criteria: bounded query grammar, deterministic result order,
  corruption/failure behavior, verified-head-only reads, safe rendering, and
  no-mutation behavior are implemented and verified inside `:project-record`.
  The task is closed after CP-0075 verification review; no further production
  scope is open.
- Required tests: query bounds/encoding, deterministic matching and ordering,
  verified-head-only results, corruption fail-closed behavior, no-mutation
  checks, conflicts, stale revisions, temporary files, and restart-equivalent
  results.
- Required documentation: ADR-0013, phase-map update, test matrix, deferred
  reconciliation, and a planning checkpoint.
- Scope boundary: no new signed record type, wire message, sync behavior,
  background process, Link change, `:cli` change, index persistence, or
  Obsidian integration.
- Evidence: `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`; focused
  `DecisionSearchTest` and full strict verification PASS.

## SYN-003

- ID: SYN-003
- Priority: P0
- Title: Workspace bootstrap and first two-person decision demo
- Status: DONE
- Purpose: Provide the smallest operator-owned composition layer that creates
  isolated profiles, local signed decisions, and one-shot sync using the
  existing frozen Link and project-record APIs.
- Dependencies: SYN-002 DONE; frozen SL-013/CP-0054, SL-014, and
  `:project-record` CP-R4 boundary.
- Acceptance criteria: CP-W1 adds a JDK-only `:workspace` launcher with bounded
  profile handling, isolated `<profile>/link`, `<profile>/project.conf`, and
  `<profile>/records` layout, identity inspection, atomic one-peer project
  creation with overwrite/mismatch refusal, and revision-1 signed decision
  creation with exactly one evidence reference. Output is stable and safe and
  includes `NODE_ID`, `PROJECT_ID`, `RECORD_ID`, and `DIGEST`.
- Required tests: profile isolation/restart, argument bounds, identity reuse,
  project overwrite and mismatch refusal, atomic config persistence, signed
  decision/evidence validation, stable output, and sensitive-output redaction.
- Required documentation: ADR-0014, first two-person demo script, CP-W1
  evidence, phase-map update, test matrix, and durable state files.
- Scope boundary: no retries, reconnect, discovery, membership, new record
  type, Link or CLI production changes, background behavior, workers, leases,
  autonomy,
  federation, Obsidian, or physical-machine claim.
- Implementation checkpoints: CP-W1 is local bootstrap and decision creation;
  CP-W2 is the separately approved authenticated host/join and sync slice.
- Evidence: `docs/evidence/WORKSPACE-CP-W1-2026-07-21.md`,
  `docs/evidence/WORKSPACE-CP-W2-2026-07-21.md`, and
  `docs/evidence/WORKSPACE-CP-W3-2026-07-21.md`; focused workspace tests and
  full strict root verification PASS. Closed at CP-W3.
- CP-W2 acceptance: `sync host` uses the sole configured peer; `sync join`
  authenticates and pins the expected host before creating B's configuration,
  performs exactly one CP-R4 sync, treats only APPLIED and DUPLICATE as
  success, and returns nonzero for UNKNOWN, REJECTED, REMOTE_STALE, CONFLICT,
  authentication, configuration, invitation, or transport failures.

## SYN-PRODUCT-REVIEW

- ID: SYN-PRODUCT-REVIEW
- Priority: P0
- Title: Product review and future planning through CP-0079
- Status: ACTIVE
- Purpose: Evaluate the product value of Synesis through CP-0079, identify friction points, outline future milestones, and recommend the next step.
- Dependencies: SYN-003 DONE
- Acceptance criteria: A complete product review document under `docs/agent/PRODUCT_REVIEW.md` is committed and checkpointed.
- Required tests: resume, doctor, checkpoint, fixture validators.
- Required documentation: `docs/agent/PRODUCT_REVIEW.md`, tasks, state, current, next session.

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
