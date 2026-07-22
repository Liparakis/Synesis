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

## SYN-010A

- ID: SYN-010A
- Priority: P0
- Title: Public GitHub developer-preview preparation
- Status: ACTIVE
- Purpose: Audit and prepare the existing repository for safe public visibility without redesigning the product, publishing release assets, or selecting a license autonomously.
- Dependencies: SYN-009C DONE at CP-0110; explicit user-supplied SYN-010A goal.
- Acceptance criteria: complete current/history secret scan, safe ignore rules, accurate preview README, SECURITY.md and CONTRIBUTING.md, workflow security audit, repository metadata review, full verification, and a clean preparation commit; public publication only after an intentional license decision and explicit external gates.
- Required tests: secret scanner or documented equivalent, focused history/path searches, strict Java build, Go test/vet, repository validators, workflow syntax/security review, and clean-tree confirmation.
- Required documentation: `docs/agent/SYN_010A_PUBLICATION_AUDIT.md`, `docs/legal/LICENSE_DECISION_REQUIRED.md`, README/public-preview docs, and durable state updates.
- Scope boundary: no new product features, protocol changes, release assets, production release, license selection, history rewrite, public push, or external announcement.
- Evidence: `docs/agent/SYN_010A_PUBLICATION_AUDIT.md`; `docs/legal/LICENSE_DECISION_REQUIRED.md`; README, SECURITY.md, CONTRIBUTING.md, and `.gitignore` preparation; validators PASS. Owner selected AGPL-3.0-only and the complete `LICENSE` is present. `gradlew` mode `100755` fixes the reported Linux CI permission failure; strict wrapper check passes. Publication remains unperformed pending explicit push authorization, author-metadata review, and target confirmation.

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
- Status: DONE
- Purpose: Evaluate the product value of Synesis through CP-0079, identify friction points, outline future milestones, and recommend the next step.
- Dependencies: SYN-003 DONE
- Acceptance criteria: A complete product review document under `docs/agent/PRODUCT_REVIEW.md` is committed and checkpointed.
- Required tests: resume, doctor, checkpoint, fixture validators.
- Required documentation: `docs/agent/PRODUCT_REVIEW.md`, tasks, state, current, next session.
- Evidence: `docs/agent/PRODUCT_REVIEW.md` and checkpoint CP-0080.

## SYN-004

- ID: SYN-004
- Priority: P0
- Title: Minimal guided workspace demo flow
- Status: DONE
- Purpose: Reduce the two-person workspace demo to the fewest safe operator steps while preserving host Node ID pinning and cryptographic identity verification.
- Dependencies: SYN-PRODUCT-REVIEW DONE, SYN-003 DONE
- Acceptance criteria: Update `:workspace` CLI commands: `sync host` takes optional `--project` and `--record` arguments and outputs a parameterized invitation link; `sync join` accepts a single invitation link, parses the project/record/host parameters, verifies the local configuration, pins the connection to the host Node ID, and runs sync. Clean error exit code `10` is accompanied by stderr contextual `HINT:` messages. No change to wire protocol formats or storage.
- Required tests: Unit tests for URI validation and query param extraction; integration process tests verifying the single-link flow, wrong host pinning rejection, and contextual next-action hints.
- Required documentation: Design document `docs/agent/SYN_004_DESIGN.md`, updated demo flow document `docs/demo/FIRST_TWO_PERSON_PROJECT_DEMO.md`, and CP-W4 evidence.
- Evidence: CP-0083; WorkspaceSyncProcessTest; full strict check PASS.

## SYN-005

- ID: SYN-005
- Priority: P0
- Title: Project-wide reconciliation over one authenticated session
- Status: DONE
- Purpose: Design and implement the smallest bounded bidirectional reconciliation protocol over a single Link session to synchronize all missing or divergent verified record heads.
- Dependencies: SYN-004 DONE
- Acceptance criteria: Design and implement PRP1 protocol over the existing authenticated Link stream seam. Exchange inventories, transfer contiguous missing revisions, prevent deletion/overwriting of divergent heads, quarantine conflict heads, verify every revision independently before storage, enforce bounds on size/records, and report per-record and project-level outcomes. Integrated `check-action` command for action-time constraint enforcement. Clean exit codes with contextual stderr `HINT=` messages.
- Required tests: Unit tests for PRP1 codec and reconciliation logic; integration process tests verifying project-wide convergence, conflict quarantining, corruption detection, and action-time checking (`projectReconciliationAndCheckActionWorkflow`).
- Required documentation: Design document `docs/agent/SYN_005_DESIGN.md`, ADR-0015, `docs/development/current-state.md`, and checkpoint CP-0091.
- Evidence: PASS — `ReconciliationMessageTest`, `ProjectReconciliationSyncProcessTest`, `WorkspaceSyncProcessTest`, and `gradlew.bat clean check --dependency-verification=strict`. Closed at CP-0091.

## SYN-006

- ID: SYN-006
- Priority: P0
- Title: Constraint Hardening and First Enforceable Harness Integration
- Status: DONE
- Purpose: Introduce typed project constraints, deterministic scope matching, portable Gradle settings, and a Claude Code pre-tool execution hook adapter.
- Dependencies: SYN-005 DONE
- Acceptance criteria: Implement ProjectConstraint typed model with LEGACY_INFERRED fallback, ScopeMatcher path normalization and wildcard matching engine, portable 2GB heap Gradle default with test fork controls, and ClaudeCodeHookAdapter pre-tool execution hook integration.
- Required tests: ScopeMatcherTest, ProjectConstraintTest, ClaudeCodeHookAdapterTest, WorkspaceSyncProcessTest.
- Required documentation: ADR-0016, current-state.md, and checkpoint CP-0092.
- Evidence: PASS — `ScopeMatcherTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, `WorkspaceSyncProcessTest`, `gradlew.bat clean check --dependency-verification=strict`.

## SYN-007

- ID: SYN-007
- Priority: P0
- Title: Clean Typed Constraint Model and Baseline-vs-Synesis Validation
- Status: DONE
- Purpose: Remove unreleased legacy constraint inference, introduce SDR2 canonical record versioning with explicit typed constraint payloads, make adapter warnings observable, and build an automated baseline vs. Synesis experiment.
- Dependencies: SYN-006 DONE
- Acceptance criteria: Remove LEGACY_INFERRED and title-prefix fallback; evolve DecisionRecord to SDR2 (0x53445232); implement explicit RecordType and ConstraintPayload; enhance ClaudeCodeHookAdapter with WARNING and UNSUPPORTED diagnostics; create automated experiment script scripts/run-synesis-guardrail-experiment.ps1 and docs/validation/baseline-vs-synesis-experiment.md.
- Required tests: DecisionRecordTest, ProjectConstraintTest, ClaudeCodeHookAdapterTest, WorkspaceSyncProcessTest, run-synesis-guardrail-experiment.ps1.
- Required documentation: ADR-0017, baseline-vs-synesis-experiment.md, current-state.md, and checkpoint CP-0093.
- Evidence: PASS — `DecisionRecordTest`, `ProjectConstraintTest`, `ClaudeCodeHookAdapterTest`, `WorkspaceSyncProcessTest`, `run-synesis-guardrail-experiment.ps1`, `gradlew.bat clean check --dependency-verification=strict`.

## SYN-007.1

- ID: SYN-007.1
- Priority: P0
- Title: Real Claude Code PreToolUse Contract Conformance
- Status: DONE
- Purpose: Align ClaudeCodeHookAdapter with official Claude Code v2.1+ PreToolUse hook contract (permissionDecision: deny, exit code 0, absolute-to-relative path resolution, additionalContext for warnings).
- Dependencies: SYN-007 DONE
- Acceptance criteria: Update ClaudeCodeHookAdapter and WorkspaceCli to exit code 0 on JSON denial responses; implement resolveRelativePath converting absolute CWD/path inputs to project-relative scopes; add docs/integration/claude-code-hook.json; verify supersession filtering in ProjectConstraint; update automated experiment script scripts/run-synesis-guardrail-experiment.ps1.
- Required tests: ClaudeCodeHookAdapterTest, ProjectConstraintTest, WorkspaceSyncProcessTest, run-synesis-guardrail-experiment.ps1.
- Required documentation: ADR-0018, current-state.md, and checkpoint CP-0094.
- Evidence: PASS — `ClaudeCodeHookAdapterTest`, `ProjectConstraintTest`, `WorkspaceSyncProcessTest`, `run-synesis-guardrail-experiment.ps1`, `gradlew.bat clean check --dependency-verification=strict`.




## SYN-008

- ID: SYN-008
- Priority: P0
- Title: Antigravity PreToolUse Adapter and Real-Agent Validation
- Status: DONE
- Purpose: Add AntigravityHookAdapter reusing ActionGuardrail, expose via hook antigravity CLI subcommand, run automated experiment proving guardrail denial with official Antigravity PreToolUse payload shape.
- Dependencies: SYN-007.1 DONE
- Acceptance criteria: ActionGuardrail harness-neutral evaluator extracted; AntigravityHookAdapter processes toolCall.name/toolCall.args.TargetFile/workspacePaths; force_ask for WARN; deny for BLOCK; ask for ALLOWED; deny for invalid/missing TargetFile; ask+diagnostic for unsupported tools; resolveRelativePath and selectProjectRoot boundary-verified; automated experiment passes with p50/p95 latency; ADR-0019; docs/integration/antigravity-hook.md and antigravity-hooks.json; docs/validation/antigravity-real-agent-experiment.md; checkpoint CP-0095.
- Required tests: AntigravityHookAdapterTest, ClaudeCodeHookAdapterTest, ActionGuardrailTest, run-antigravity-guardrail-experiment.ps1.
- Required documentation: ADR-0019, antigravity-hook.md, antigravity-hooks.json, antigravity-real-agent-experiment.md, current-state.md, CP-0095.
- Evidence: PASS — BUILD SUCCESSFUL in 2m 4s (39 tasks); run-antigravity-guardrail-experiment.ps1 SYNESIS_ACTION_RESULT=BLOCKED, GUARDRAIL_LATENCY_P50_MS=181, GUARDRAIL_LATENCY_P95_MS=196, SYNESIS_FALSE_POSITIVE_COUNT=0.

## SYN-009A

- ID: SYN-009A
- Priority: P0
- Title: Unified CLI, application services, project initialization, and local state layout
- Status: DONE
- Purpose: Make `synesis` the sole public CLI, extract workspace application services, and establish safe discovered `.synesis` project state for the SYN-009 roadmap.
- Dependencies: SYN-008 DONE; CP-0095; existing `:link`, `:project-record`, `:workspace`, and `:cli` modules.
- Acceptance criteria: `:cli` owns the public command tree and composition; `:workspace` is a library without an application launcher; workspace business logic is exposed through structured application services without Picocli or direct console output; project discovery and `synesis init` create and validate the bounded `.synesis` layout; ordinary commands default to `.synesis/local/profile` with an explicit advanced profile override; existing host/join, decision/constraint, guardrail, and hook behavior remains covered; package and dependency checks pass.
- Required tests: service results, project discovery/init conflicts and secrets, unified command reachability and exit/output contracts, package-boundary checks, launcher retirement, and current module tests. The unreleased legacy process harness is intentionally removed rather than retained as a compatibility requirement.
- Required documentation: implementation note, ADR-0020, package boundaries, project layout, command reference, current state, checkpoints CP-0096 and CP-0099, and durable task state.
- Scope boundary: provider lifecycle, expanded doctor, portable ZIP, version injection, protocol changes, background synchronization, additional adapters, and remote publication were out of scope.
- Evidence: PASS — `ProjectApplicationServiceTest`, adapter/workspace/CLI tests, `:workspace:architectureCheck`, strict Javadocs, `gradlew.bat clean check --dependency-verification=strict` (34 actionable tasks), unified launcher smoke tests, and CP-0099. Unreleased compatibility launchers and process harnesses are deleted.

## SYN-009B

- ID: SYN-009B
- Priority: P0
- Title: Provider lifecycle management and installation diagnostics
- Status: DONE
- Purpose: Add project-local provider install, status, uninstall, registry, synthetic health checks, and doctor diagnostics for Antigravity and Claude Code.
- Dependencies: SYN-009A DONE; CP-0099; existing unified CLI, project layout, provider adapters, and shared `ActionGuardrail`.
- Acceptance criteria: provider lifecycle is application-service owned; only Antigravity (`BETA`) and Claude Code (`EXPERIMENTAL`) are listed; provider metadata remains local-only; configuration merges preserve unrelated JSON; writes are atomic; malformed configuration is never overwritten; install/status/uninstall are idempotent; synthetic checks use isolated fixtures; doctor reports project, record, provider, and known-limitations results; Codex and portable packaging remain deferred.
- Required tests: registry, provider configuration merge/atomicity, Antigravity and Claude Code lifecycle, isolated synthetic checks, status classification, uninstall preservation, doctor results, unified-launcher process coverage, and strict full verification.
- Required documentation: implementation note, ADR-0021, provider boundary, provider management, doctor, integration docs, current state, and durable task state.
- Scope boundary: no Codex, MCP, dynamic plugins, shell-command analysis, portable ZIP, release packaging, background synchronization, protocol changes, cloud services, or remote publication.
- Evidence: PASS — `ProviderApplicationServiceTest`, `UnifiedCliSyncProcessTest` (five generated-launcher process scenarios), provider Javadocs, `gradlew.bat clean check --dependency-verification=strict`, generated disposable-project Antigravity and Claude Code lifecycle checks with unrelated hook preservation, and CP-0102. Deleted legacy CLI compatibility tests remain deleted; valid protocol/process behavior was rewritten against the unified launcher.

## SYN-009C

- ID: SYN-009C
- Priority: P0
- Title: Cross-platform distribution and bootstrap installation
- Status: DONE
- Purpose: Produce platform-specific Java bundles with bundled runtimes, a Go installer/update bootstrapper, and a verified CI artifact matrix.
- Dependencies: SYN-009B DONE; user-supplied SYN-009C activation; Codex remains EXPERIMENTAL/DEGRADED and is not promoted by this task.
- Acceptance criteria: native Windows bundle smoke passes; Go bootstrap install/update/uninstall/doctor behavior is bounded and tested; detached Ed25519 manifest verification, SHA-256 verification, safe extraction, atomic activation, rollback, and project preservation pass; CI defines windows/linux/macos x64/arm64 artifact jobs and honest native/cross-compiled status; Java provider behavior remains covered; no Link protocol behavior changes.
- Required tests: `:cli:platformBundle`, bundled-runtime smoke, Go unit/integration tests, artifact/manifest checks, safe-extraction tests, and strict Java verification.
- Required documentation: implementation note, three distribution ADRs, installation/release/signing docs, smoke evidence, and durable state updates.
- Scope state: activated from the pasted SYN-009C goal; no public release or remote publication.
- Evidence: PASS — CP-0106 through CP-0110 and commit `7a40324`; Java strict
  build/archive extraction smoke, Go tests/vet/native subprocess, six
  cross-builds, real Windows Java ZIP bootstrap install and bundled CLI/provider
  lifecycle trial, CI matrix/sidecar validation, release documentation, and
  clean working tree. Production key replacement, OS signing/notarization, and
  public publication remain explicitly deferred.

## SYN-009B.1

- ID: SYN-009B.1
- Priority: P0
- Title: Codex PreToolUse adapter, provider lifecycle, and real-agent validation
- Status: VERIFYING
- Purpose: Add the smallest project-local Codex `apply_patch` PreToolUse adapter and provider lifecycle integration on top of the closed SYN-009B foundation.
- Dependencies: SYN-009B DONE at CP-0102; Codex CLI 0.140.0 is locally installed; official Codex hook/config contract review recorded in `docs/agent/SYN_009B1_IMPLEMENTATION_NOTE.md`.
- Acceptance criteria: Codex is listed after Antigravity and Claude Code as `EXPERIMENTAL`; `synesis hook codex` parses bounded Add/Update/Delete/Move patch paths, resolves `cwd` through the shared project/path guardrail boundary, denies any blocked or invalid multi-path patch with exit 0, emits bounded warnings as `additionalContext`, leaves allowed/unsupported stdout empty, and never applies patches; provider install/status/uninstall owns project-local `.codex/hooks.json` atomically and idempotently while preserving unrelated configuration; install/status/doctor report trust `REVIEW_REQUIRED`/`UNKNOWN` and stay degraded until a real validated run; synthetic tests, process-level launcher coverage, Codex version/fixture capture, and the real authenticated `/hooks` experiment are recorded honestly; Codex remains `EXPERIMENTAL` unless every promotion gate passes.
- Required tests: bounded parser tests for Add/Update/Delete/Move, duplicate normalization, malformed/traversal fail-closed cases, adapter allow/block/warning/unsupported/invalid behavior, multi-path aggregation, provider merge/atomicity/idempotence/uninstall preservation, generated-launcher hook and lifecycle process coverage, and a 20-invocation p50/p95 measurement.
- Required documentation: implementation note, ADR-0022, `docs/integration/codex-hook.md`, `docs/validation/codex-real-agent-experiment.md`, provider/doctor/current-state updates, sanitized actual payload fixture with version, checkpoint evidence, and durable state updates.
- Scope boundary: no Bash hooks, MCP, SDK/App Server, transcript parsing, patch application, trust-database edits, portable ZIP, release packaging, protocol changes, dynamic plugins, or remote publication.
- Evidence: Synthetic parser/adapter/provider tests, generated launcher process
  coverage, 20-call latency measurement (`p50=1.247 ms`, `p95=1.806 ms`),
  strict clean check, and disposable generated-launcher lifecycle checks PASS.
  Real `/hooks` trust review and authenticated denial/re-plan/hash evidence are
  not complete; see `docs/validation/codex-real-agent-experiment.md`.

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
